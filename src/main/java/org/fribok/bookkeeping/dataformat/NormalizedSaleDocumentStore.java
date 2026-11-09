package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSTender;
import se.swedsoft.bookkeeping.data.base.SSSale;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Complete normalized order/tender snapshots. Uses only the supplied connection, never SSDB.
 * Writes are atomic within the caller's transaction: auto-commit must be disabled and this store
 * never commits or closes the connection. Document numbers and counters remain caller-owned.
 */
public final class NormalizedSaleDocumentStore {
    private static final List<String> COMMON = List.of("number", "sale_date", "customer_number", "customer_name",
            "our_contact_person", "customer_contact_person", "delay_interest", "currency_code", "currency_rate",
            "payment_term_name", "delivery_term_name", "delivery_way_name", "tax_free", "sale_text",
            "tax_rate_1", "tax_rate_2", "tax_rate_3", "eu_sale_commodity", "eu_sale_third_party_commodity", "printed");

    private enum Kind {
        ORDER("customer_order", "order_id", List.of("customer_order_number", "estimated_delivery",
                "invoice_number", "periodic_invoice_number", "purchase_order_number", "hide_unit_price")),
        TENDER("tender", "tender_id", List.of("expires_on", "order_number"));

        final String table;
        final String parent;
        final List<String> columns;

        Kind(String table, String parent, List<String> extra) {
            this.table = table;
            this.parent = parent;
            columns = new ArrayList<>(COMMON);
            columns.addAll(extra);
        }
    }

    /**
     * Stable SQL identity plus the legacy identity retained by migration. Numbers may be null or
     * duplicated in historical data, so editing/deleting uses id, never a displayed document number.
     * @param id normalized storage identity
     * @param legacyId migration identity, retained on updates
     * @param document detached editable snapshot
     * @param <T> order or tender
     */
    public record Stored<T extends SSSale>(long id, int legacyId, T document) {}

    private final Connection connection;
    private final NormalizedRegisterStore registers;

    /** Uses the caller-owned normalized connection and transaction. */
    public NormalizedSaleDocumentStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        registers = new NormalizedRegisterStore(connection);
    }

    /** Returns detached orders in stable storage order, scoped to one company. */
    public List<Stored<SSOrder>> getOrders(int companyLegacyId) throws SQLException {
        return read(Kind.ORDER, companyLegacyId, null);
    }

    /** Returns detached tenders in stable storage order, scoped to one company. */
    public List<Stored<SSTender>> getTenders(int companyLegacyId) throws SQLException {
        return read(Kind.TENDER, companyLegacyId, null);
    }

    /** Looks up an order by storage identity within the selected company. */
    public Optional<Stored<SSOrder>> findOrder(int companyLegacyId, long id) throws SQLException {
        return this.<SSOrder>read(Kind.ORDER, companyLegacyId, id).stream().findFirst();
    }

    /** Looks up a tender by storage identity within the selected company. */
    public Optional<Stored<SSTender>> findTender(int companyLegacyId, long id) throws SQLException {
        return this.<SSTender>read(Kind.TENDER, companyLegacyId, id).stream().findFirst();
    }

    /** Inserts an order atomically and returns its storage identity; document numbering remains caller-owned. */
    public long addOrder(int companyLegacyId, SSOrder order) throws SQLException {
        return insert(Kind.ORDER, companyLegacyId, Objects.requireNonNull(order, "order"));
    }

    /** Inserts a tender atomically and returns its storage identity; document numbering remains caller-owned. */
    public long addTender(int companyLegacyId, SSTender tender) throws SQLException {
        return insert(Kind.TENDER, companyLegacyId, Objects.requireNonNull(tender, "tender"));
    }

    /** Replaces an order and all children atomically, retaining its storage and migration identities. */
    public void updateOrder(int companyLegacyId, long id, SSOrder order) throws SQLException {
        replace(Kind.ORDER, companyLegacyId, id, Objects.requireNonNull(order, "order"));
    }

    /** Replaces a tender and all children atomically, retaining its storage and migration identities. */
    public void updateTender(int companyLegacyId, long id, SSTender tender) throws SQLException {
        replace(Kind.TENDER, companyLegacyId, id, Objects.requireNonNull(tender, "tender"));
    }

    /** Deletes an order and its children atomically; rejects missing or foreign-company identities. */
    public void deleteOrder(int companyLegacyId, long id) throws SQLException {
        delete(Kind.ORDER, companyLegacyId, id);
    }

    /** Deletes a tender and its children atomically; rejects missing or foreign-company identities. */
    public void deleteTender(int companyLegacyId, long id) throws SQLException {
        delete(Kind.TENDER, companyLegacyId, id);
    }

    private <T extends SSSale> List<Stored<T>> read(Kind kind, int company, Long selectedId) throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency value : registers.getCurrencies()) currencies.put(value.getName(), value);
        Map<String, SSPaymentTerm> payments = new HashMap<>();
        for (SSPaymentTerm value : registers.getPaymentTerms()) payments.put(value.getName(), value);
        Map<String, SSDeliveryTerm> terms = new HashMap<>();
        for (SSDeliveryTerm value : registers.getDeliveryTerms()) terms.put(value.getName(), value);
        Map<String, SSDeliveryWay> ways = new HashMap<>();
        for (SSDeliveryWay value : registers.getDeliveryWays()) ways.put(value.getName(), value);
        Map<Long, Stored<T>> stored = new LinkedHashMap<>();
        String sql = "SELECT id,legacy_id," + String.join(",", kind.columns) + " FROM " + kind.table
                + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                + (selectedId == null ? "" : " AND id=?") + " ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, company);
            if (selectedId != null) statement.setLong(2, selectedId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    @SuppressWarnings("unchecked")
                    T sale = (T) (kind == Kind.ORDER ? SSOrder.forPersistence() : SSTender.forPersistence());
                    sale.setNumber(integer(result, "number"));
                    sale.setLocalDate(result.getObject("sale_date", LocalDate.class));
                    sale.setCustomerNr(result.getString("customer_number"));
                    sale.setCustomerName(result.getString("customer_name"));
                    sale.setOurContactPerson(result.getString("our_contact_person"));
                    sale.setYourContactPerson(result.getString("customer_contact_person"));
                    sale.setDelayInterest(result.getBigDecimal("delay_interest"));
                    sale.setCurrency(currencies.get(result.getString("currency_code")));
                    sale.setPaymentTerm(payments.get(result.getString("payment_term_name")));
                    sale.setDeliveryTerm(terms.get(result.getString("delivery_term_name")));
                    sale.setDeliveryWay(ways.get(result.getString("delivery_way_name")));
                    sale.setTaxFree(result.getBoolean("tax_free"));
                    sale.setText(result.getString("sale_text"));
                    sale.setStoredTaxRates(result.getBigDecimal("tax_rate_1"),
                            result.getBigDecimal("tax_rate_2"), result.getBigDecimal("tax_rate_3"));
                    sale.setEuSaleCommodity(result.getBoolean("eu_sale_commodity"));
                    sale.setEuSaleYhirdPartCommodity(result.getBoolean("eu_sale_third_party_commodity"));
                    sale.setPrinted(result.getBoolean("printed"));
                    if (sale instanceof SSOrder order) {
                        order.setCurrencyRate(result.getBigDecimal("currency_rate"));
                        order.setYourOrderNumber(result.getString("customer_order_number"));
                        order.setEstimatedDelivery(result.getString("estimated_delivery"));
                        order.setInvoiceNr(integer(result, "invoice_number"));
                        order.setPeriodicInvoiceNr(integer(result, "periodic_invoice_number"));
                        order.setPurchaseOrderNr(integer(result, "purchase_order_number"));
                        order.setHideUnitprice(result.getBoolean("hide_unit_price"));
                    } else {
                        SSTender tender = (SSTender) sale;
                        tender.setCurrencyRate(result.getBigDecimal("currency_rate"));
                        tender.setLocalExpires(result.getObject("expires_on", LocalDate.class));
                        tender.setOrderNr(integer(result, "order_number"));
                    }
                    long id = result.getLong("id");
                    stored.put(id, new Stored<>(id, result.getInt("legacy_id"), sale));
                }
            }
        }
        if (!stored.isEmpty()) readChildren(kind, company, stored);
        return new ArrayList<>(stored.values());
    }

    private void readChildren(Kind kind, int company, Map<Long, ? extends Stored<?>> stored) throws SQLException {
        String filter = " JOIN " + kind.table + " p ON p.id=x." + kind.parent
                + " WHERE p.company_id=(SELECT id FROM company WHERE legacy_id=?)";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x.* FROM " + kind.table + "_address x" + filter + " ORDER BY x." + kind.parent
                        + ",x.address_type")) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored<?> value = stored.get(result.getLong(kind.parent));
                    if (value == null) continue;
                    SSAddress address = new SSAddress();
                    address.setName(result.getString("name"));
                    address.setAddress1(result.getString("address_line_1"));
                    address.setAddress2(result.getString("address_line_2"));
                    address.setZipCode(result.getString("postal_code"));
                    address.setCity(result.getString("city"));
                    address.setCountry(result.getString("country"));
                    if ("invoice".equals(result.getString("address_type"))) {
                        value.document().setInvoiceAddress(address);
                    } else {
                        value.document().setDeliveryAddress(address);
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x.* FROM " + kind.table + "_default_account x" + filter)) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored<?> value = stored.get(result.getLong(kind.parent));
                    if (value != null) value.document().getDefaultAccounts().put(
                            SSDefaultAccount.valueOf(result.getString("account_type")),
                            integer(result, "account_number"));
                }
            }
        }
        Map<String, SSUnit> units = new HashMap<>();
        for (SSUnit unit : registers.getUnits()) units.put(unit.getName(), unit);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x.* FROM " + kind.table + "_row x" + filter + " ORDER BY x." + kind.parent + ",x.row_number")) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored<?> value = stored.get(result.getLong(kind.parent));
                    if (value == null) continue;
                    SSSaleRow row = new SSSaleRow();
                    row.setProductNr(result.getString("product_number"));
                    row.setDescription(result.getString("description"));
                    row.setUnitprice(result.getBigDecimal("unit_price"));
                    row.setQuantity(result.getBigDecimal("quantity"));
                    row.setUnit(units.get(result.getString("unit_name")));
                    row.setDiscount(result.getBigDecimal("discount"));
                    String tax = result.getString("tax_code");
                    row.setTaxCode(tax == null ? null : SSTaxCode.valueOf(tax));
                    row.setAccountNr(integer(result, "account_number"));
                    row.setProjectNr(result.getString("project_number"));
                    row.setResultUnitNr(result.getString("result_unit_number"));
                    value.document().getRows().add(row);
                }
            }
        }
    }

    private long insert(Kind kind, int company, SSSale sale) throws SQLException {
        return atomic(() -> {
            int legacyId;
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT COALESCE(MAX(legacy_id),-1)+1 FROM " + kind.table)) {
                result.next();
                long next = result.getLong(1);
                if (next > Integer.MAX_VALUE) throw new SQLException("Sale-document legacy identity exhausted");
                legacyId = (int) next;
            }
            String placeholders = String.join(",", Collections.nCopies(kind.columns.size(), "?"));
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.table + " (legacy_id,company_id," + String.join(",", kind.columns)
                            + ") SELECT ?,id," + placeholders + " FROM company WHERE legacy_id=?",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, legacyId);
                int next = bind(statement, 2, values(sale));
                statement.setInt(next, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + company);
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing generated sale-document identity");
                    id = keys.getLong(1);
                }
            }
            writeChildren(kind, company, id, sale);
            return id;
        });
    }

    private void replace(Kind kind, int company, long id, SSSale sale) throws SQLException {
        atomic(() -> {
            String assignments = kind.columns.stream().map(column -> column + "=?").collect(Collectors.joining(","));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + kind.table + " SET " + assignments + " WHERE id=? AND company_id="
                            + "(SELECT id FROM company WHERE legacy_id=?)")) {
                int next = bind(statement, 1, values(sale));
                statement.setLong(next++, id);
                statement.setInt(next, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing " + kind + " " + id
                        + " in company " + company);
            }
            deleteChildren(kind, id);
            writeChildren(kind, company, id, sale);
            return null;
        });
    }

    private void delete(Kind kind, int company, long id) throws SQLException {
        atomic(() -> {
            // Validate ownership before touching child rows, including default accounts without a company column.
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + kind.table
                    + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setLong(1, id);
                statement.setInt(2, company);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Missing " + kind + " " + id + " in company " + company);
                }
            }
            deleteChildren(kind, id);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + kind.table
                    + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setLong(1, id);
                statement.setInt(2, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Sale-document disappeared during deletion");
            }
            return null;
        });
    }

    private void deleteChildren(Kind kind, long id) throws SQLException {
        for (String suffix : List.of("_row", "_address", "_default_account")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + kind.table + suffix + " WHERE " + kind.parent + "=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
    }

    private void writeChildren(Kind kind, int company, long id, SSSale sale) throws SQLException {
        address(kind, company, id, "invoice", sale.getInvoiceAddress());
        address(kind, company, id, "delivery", sale.getDeliveryAddress());
        for (var entry : sale.getDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table
                    + "_default_account (" + kind.parent + ",account_type,account_number) VALUES (?,?,?)")) {
                bind(statement, 1, Arrays.asList(id, entry.getKey().name(), entry.getValue()));
                statement.executeUpdate();
            }
        }
        int number = 0;
        for (SSSaleRow row : sale.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table + "_row ("
                    + kind.parent + ",company_id,row_number,product_number,description,unit_price,quantity,unit_name,"
                    + "discount,tax_code,account_number,project_number,result_unit_number) "
                    + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
                bind(statement, 1, Arrays.asList(id, number++, row.getProductNr(), row.getDescription(),
                        row.getUnitprice(), row.getQuantity(), row.getUnit() == null ? null : row.getUnit().getName(),
                        row.getDiscount(), row.getTaxCode() == null ? null : row.getTaxCode().name(),
                        row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(), company));
                if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + company);
            }
        }
    }

    private void address(Kind kind, int company, long id, String type, SSAddress address) throws SQLException {
        if (address == null) return;
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table + "_address ("
                + kind.parent + ",company_id,address_type,name,address_line_1,address_line_2,postal_code,city,country) "
                + "SELECT ?,id,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            bind(statement, 1, Arrays.asList(id, type, address.getName(), address.getAddress1(), address.getAddress2(),
                    address.getZipCode(), address.getCity(), address.getCountry(), company));
            if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + company);
        }
    }

    private static List<Object> values(SSSale sale) {
        List<BigDecimal> tax = sale.getStoredTaxRates();
        List<Object> values = new ArrayList<>(Arrays.asList(sale.getNumber(), sale.getLocalDate(), sale.getCustomerNr(),
                sale.getCustomerName(), sale.getOurContactPerson(), sale.getYourContactPerson(),
                sale.getStoredDelayInterest(), sale.getCurrency() == null ? null : sale.getCurrency().getName(),
                sale instanceof SSOrder order ? order.getStoredCurrencyRate()
                        : ((SSTender) sale).getStoredCurrencyRate(),
                sale.getPaymentTerm() == null ? null : sale.getPaymentTerm().getName(),
                sale.getDeliveryTerm() == null ? null : sale.getDeliveryTerm().getName(),
                sale.getDeliveryWay() == null ? null : sale.getDeliveryWay().getName(),
                sale.getTaxFree(), sale.getText(), tax.size() > 0 ? tax.get(0) : null,
                tax.size() > 1 ? tax.get(1) : null, tax.size() > 2 ? tax.get(2) : null,
                sale.getEuSaleCommodity(), sale.getEuSaleThirdPartCommodity(), sale.isPrinted()));
        if (sale instanceof SSOrder order) {
            values.addAll(Arrays.asList(order.getYourOrderNumber(), order.getEstimatedDelivery(), order.getInvoiceNr(),
                    order.getPeriodicInvoiceNr(), order.getPurchaseOrderNr(), order.getHideUnitprice()));
        } else {
            SSTender tender = (SSTender) sale;
            values.addAll(Arrays.asList(tender.getLocalExpires(), tender.getOrderNr()));
        }
        return values;
    }

    private static int bind(PreparedStatement statement, int index, List<?> values) throws SQLException {
        for (Object value : values) statement.setObject(index++, value);
        return index;
    }

    private static Integer integer(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private <T> T atomic(SqlOperation<T> operation) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Sale-document writes require auto-commit disabled");
        Savepoint savepoint = connection.setSavepoint();
        try {
            T value = operation.run();
            connection.releaseSavepoint(savepoint);
            return value;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }
}
