package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSPurchaseOrder;
import se.swedsoft.bookkeeping.data.SSPurchaseOrderRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;

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
 * Complete normalized purchase-order snapshots, without global SSDB lookups.
 * Writes require auto-commit disabled and use local savepoints; the caller owns the connection,
 * document numbering, counters and outer transaction. This store never commits or closes it.
 */
public final class NormalizedPurchaseOrderStore {
    private static final List<String> COLUMNS = List.of("number", "order_date", "supplier_number", "supplier_name",
            "our_contact_person", "supplier_contact_person", "payment_term_name", "delivery_term_name",
            "delivery_way_name",
            "currency_code", "currency_rate", "order_text", "estimated_delivery_on", "printed", "stock_influencing",
            "invoice_number");

    /**
     * Detached snapshot with stable identities independent of nullable/duplicate historical document numbers.
     * @param id SQL storage identity used for edits and deletion
     * @param legacyId migration identity, retained on edits
     * @param document editable purchase-order snapshot
     */
    public record Stored(long id, int legacyId, SSPurchaseOrder document) {}

    private final Connection connection;
    private final NormalizedRegisterStore registers;

    /** Uses the caller-owned normalized connection and transaction. */
    public NormalizedPurchaseOrderStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        registers = new NormalizedRegisterStore(connection);
    }

    /** Loads all purchase orders for one company in stable storage order. */
    public List<Stored> getPurchaseOrders(int companyLegacyId) throws SQLException {
        return read(companyLegacyId, null);
    }

    /** Looks up a storage identity only within the supplied company. */
    public Optional<Stored> findPurchaseOrder(int companyLegacyId, long id) throws SQLException {
        return read(companyLegacyId, id).stream().findFirst();
    }

    /** Inserts a complete snapshot atomically, returning its SQL identity without allocating a document number. */
    public long addPurchaseOrder(int companyLegacyId, SSPurchaseOrder order) throws SQLException {
        Objects.requireNonNull(order, "order");
        return atomic(() -> {
            int legacyId;
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT COALESCE(MAX(legacy_id),-1)+1 FROM purchase_order")) {
                result.next();
                long next = result.getLong(1);
                if (next > Integer.MAX_VALUE) throw new SQLException("Purchase-order migration identity exhausted");
                legacyId = (int) next;
            }
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO purchase_order (legacy_id,company_id," + String.join(",", COLUMNS)
                            + ") SELECT ?,id," + String.join(",", Collections.nCopies(COLUMNS.size(), "?"))
                            + " FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, legacyId);
                int next = bind(statement, 2, values(order));
                statement.setInt(next, companyLegacyId);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + companyLegacyId);
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing generated purchase-order identity");
                    id = keys.getLong(1);
                }
            }
            writeChildren(companyLegacyId, id, order);
            return id;
        });
    }

    /** Replaces all header/child fields atomically, retaining SQL and migration identities. */
    public void updatePurchaseOrder(int companyLegacyId, long id, SSPurchaseOrder order) throws SQLException {
        Objects.requireNonNull(order, "order");
        atomic(() -> {
            String assignments = COLUMNS.stream().map(column -> column + "=?").collect(Collectors.joining(","));
            try (PreparedStatement statement = connection.prepareStatement("UPDATE purchase_order SET " + assignments
                    + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                int next = bind(statement, 1, values(order));
                statement.setLong(next++, id);
                statement.setInt(next, companyLegacyId);
                if (statement.executeUpdate() != 1) throw missing(companyLegacyId, id);
            }
            deleteChildren(id);
            writeChildren(companyLegacyId, id, order);
            return null;
        });
    }

    /** Deletes only the supplied company's purchase order and its children, atomically. */
    public void deletePurchaseOrder(int companyLegacyId, long id) throws SQLException {
        atomic(() -> {
            // Validate ownership before touching child accounts, which have no company column.
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM purchase_order WHERE id=?"
                    + " AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setLong(1, id);
                statement.setInt(2, companyLegacyId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw missing(companyLegacyId, id);
                }
            }
            deleteChildren(id);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM purchase_order WHERE id=?"
                    + " AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setLong(1, id);
                statement.setInt(2, companyLegacyId);
                if (statement.executeUpdate() != 1) throw missing(companyLegacyId, id);
            }
            return null;
        });
    }

    private List<Stored> read(int company, Long selectedId) throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency value : registers.getCurrencies()) currencies.put(value.getName(), value);
        Map<String, SSPaymentTerm> payments = new HashMap<>();
        for (SSPaymentTerm value : registers.getPaymentTerms()) payments.put(value.getName(), value);
        Map<String, SSDeliveryTerm> terms = new HashMap<>();
        for (SSDeliveryTerm value : registers.getDeliveryTerms()) terms.put(value.getName(), value);
        Map<String, SSDeliveryWay> ways = new HashMap<>();
        for (SSDeliveryWay value : registers.getDeliveryWays()) ways.put(value.getName(), value);
        Map<Long, Stored> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,legacy_id,"
                + String.join(",", COLUMNS) + " FROM purchase_order WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=?)" + (selectedId == null ? "" : " AND id=?")
                + " ORDER BY id")) {
            statement.setInt(1, company);
            if (selectedId != null) statement.setLong(2, selectedId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSPurchaseOrder order = SSPurchaseOrder.forPersistence();
                    order.setNumber(integer(result, "number"));
                    order.setLocalDate(result.getObject("order_date", LocalDate.class));
                    order.setSupplierNr(result.getString("supplier_number"));
                    order.setSupplierName(result.getString("supplier_name"));
                    order.setOurContact(result.getString("our_contact_person"));
                    order.setYourContact(result.getString("supplier_contact_person"));
                    order.setPaymentTerm(payments.get(result.getString("payment_term_name")));
                    order.setDeliveryTerm(terms.get(result.getString("delivery_term_name")));
                    order.setDeliveryWay(ways.get(result.getString("delivery_way_name")));
                    order.setCurrency(currencies.get(result.getString("currency_code")));
                    order.setCurrencyRate(result.getBigDecimal("currency_rate"));
                    order.setText(result.getString("order_text"));
                    order.setLocalEstimatedDelivery(result.getObject("estimated_delivery_on", LocalDate.class));
                    order.setPrinted(result.getBoolean("printed"));
                    order.setStockInfluencing(result.getBoolean("stock_influencing"));
                    order.setInvoiceNr(integer(result, "invoice_number"));
                    long id = result.getLong("id");
                    stored.put(id, new Stored(id, result.getInt("legacy_id"), order));
                }
            }
        }
        if (!stored.isEmpty()) readChildren(company, stored);
        return new ArrayList<>(stored.values());
    }

    private void readChildren(int company, Map<Long, Stored> stored) throws SQLException {
        String filter = " JOIN purchase_order p ON p.id=x.purchase_order_id"
                + " WHERE p.company_id=(SELECT id FROM company WHERE legacy_id=?)";
        try (PreparedStatement statement = connection.prepareStatement("SELECT x.* FROM purchase_order_address x"
                + filter + " ORDER BY x.purchase_order_id,x.address_type")) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored value = stored.get(result.getLong("purchase_order_id"));
                    if (value == null) continue;
                    SSAddress address = new SSAddress();
                    address.setName(result.getString("name"));
                    address.setAddress1(result.getString("address_line_1"));
                    address.setAddress2(result.getString("address_line_2"));
                    address.setZipCode(result.getString("postal_code"));
                    address.setCity(result.getString("city"));
                    address.setCountry(result.getString("country"));
                    if ("delivery".equals(result.getString("address_type"))) {
                        value.document().setDeliveryAddress(address);
                    } else {
                        value.document().setSupplierAddress(address);
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x.* FROM purchase_order_default_account x" + filter)) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored value = stored.get(result.getLong("purchase_order_id"));
                    if (value != null) value.document().getDefaultAccounts().put(
                            SSDefaultAccount.valueOf(result.getString("account_type")),
                            integer(result, "account_number"));
                }
            }
        }
        Map<String, SSUnit> units = new HashMap<>();
        for (SSUnit unit : registers.getUnits()) units.put(unit.getName(), unit);
        try (PreparedStatement statement = connection.prepareStatement("SELECT x.* FROM purchase_order_row x"
                + filter + " ORDER BY x.purchase_order_id,x.row_number")) {
            statement.setInt(1, company);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Stored value = stored.get(result.getLong("purchase_order_id"));
                    if (value == null) continue;
                    SSPurchaseOrderRow row = new SSPurchaseOrderRow();
                    row.setProductNr(result.getString("product_number"));
                    row.setDescription(result.getString("description"));
                    row.setSupplierArticleNr(result.getString("supplier_article_number"));
                    row.setUnit(units.get(result.getString("unit_name")));
                    row.setQuantity(integer(result, "quantity"));
                    row.setUnitPrice(result.getBigDecimal("unit_price"));
                    row.setAccountNr(integer(result, "account_number"));
                    value.document().getRows().add(row);
                }
            }
        }
    }

    private void writeChildren(int company, long id, SSPurchaseOrder order) throws SQLException {
        address(company, id, "delivery", order.getDeliveryAddress());
        address(company, id, "supplier", order.getSupplierAddress());
        for (var entry : order.getDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO purchase_order_default_account"
                    + " (purchase_order_id,account_type,account_number) VALUES (?,?,?)")) {
                bind(statement, 1, Arrays.asList(id, entry.getKey().name(), entry.getValue()));
                statement.executeUpdate();
            }
        }
        int number = 0;
        for (SSPurchaseOrderRow row : order.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO purchase_order_row"
                    + " (purchase_order_id,row_number,product_number,description,supplier_article_number,"
                    + "unit_name,quantity,unit_price,account_number) VALUES (?,?,?,?,?,?,?,?,?)")) {
                bind(statement, 1, Arrays.asList(id, number++, row.getProductNr(), row.getDescription(),
                        row.getSupplierArticleNr(), row.getUnit() == null ? null : row.getUnit().getName(),
                        row.getQuantity(),
                        row.getUnitPrice(), row.getAccountNr()));
                statement.executeUpdate();
            }
        }
    }

    private void address(int company, long id, String type, SSAddress address) throws SQLException {
        if (address == null) return;
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO purchase_order_address"
                + " (purchase_order_id,company_id,address_type,name,address_line_1,address_line_2,"
                + "postal_code,city,country)"
                + " SELECT ?,id,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            bind(statement, 1, Arrays.asList(id, type, address.getName(), address.getAddress1(), address.getAddress2(),
                    address.getZipCode(), address.getCity(), address.getCountry(), company));
            if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + company);
        }
    }

    private void deleteChildren(long id) throws SQLException {
        for (String table : List.of("purchase_order_row", "purchase_order_address", "purchase_order_default_account")) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table
                    + " WHERE purchase_order_id=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
    }

    private static List<Object> values(SSPurchaseOrder order) {
        return Arrays.asList(order.getNumber(), order.getLocalDate(), order.getSupplierNr(), order.getSupplierName(),
                order.getOurContact(), order.getYourContact(),
                order.getPaymentTerm() == null ? null : order.getPaymentTerm().getName(),
                order.getDeliveryTerm() == null ? null : order.getDeliveryTerm().getName(),
                order.getDeliveryWay() == null ? null : order.getDeliveryWay().getName(),
                order.getCurrency() == null ? null : order.getCurrency().getName(), order.getStoredCurrencyRate(),
                order.getText(), order.getLocalEstimatedDelivery(), order.isPrinted(), order.isStockInfluencing(),
                order.getInvoiceNr());
    }

    private static int bind(PreparedStatement statement, int index, List<?> values) throws SQLException {
        for (Object value : values) statement.setObject(index++, value);
        return index;
    }

    private static Integer integer(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static SQLException missing(int company, long id) {
        return new SQLException("Missing purchase order " + id + " in company " + company);
    }

    private <T> T atomic(SqlOperation<T> operation) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Purchase-order writes require auto-commit disabled");
        Savepoint savepoint = connection.setSavepoint();
        try {
            T value = operation.run();
            connection.releaseSavepoint(savepoint);
            return value;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                // HSQLDB removes this savepoint when rolling back to it.
                connection.rollback(savepoint);
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
