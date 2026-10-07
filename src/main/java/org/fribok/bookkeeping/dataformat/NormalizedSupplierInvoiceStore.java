package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SSDB-shaped reads/writes over the normalized supplier invoice and supplier credit invoice
 * tables, including ordered rows, default accounts, and the main/correction voucher snapshots.
 */
public final class NormalizedSupplierInvoiceStore {

    /** Which supplier invoice table family an operation targets. */
    private enum Kind {
        INVOICE("supplier_invoice", "supplierinvoice"),
        CREDIT("supplier_credit_invoice", "suppliercreditinvoice");

        final String table;
        final String counter;

        Kind(String table, String counter) {
            this.table = table;
            this.counter = counter;
        }

        String rowTable() {
            return table + "_row";
        }

        String defaultAccountTable() {
            return table + "_default_account";
        }

        String voucherTable() {
            return table + "_voucher";
        }

        String voucherRowTable() {
            return table + "_voucher_row";
        }

        String idColumn() {
            return this == CREDIT ? "supplier_credit_invoice_id" : "supplier_invoice_id";
        }
    }

    private final Connection connection;
    private final NormalizedRegisterStore registers;

    public NormalizedSupplierInvoiceStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.registers = new NormalizedRegisterStore(connection);
    }

    /** Returns the next supplier invoice number (legacy counter-floor contract). */
    public int nextSupplierInvoiceNumber(int companyLegacyId) throws SQLException {
        return nextNumber(Kind.INVOICE, companyLegacyId);
    }

    /** Returns the next supplier credit invoice number (legacy counter-floor contract). */
    public int nextSupplierCreditInvoiceNumber(int companyLegacyId) throws SQLException {
        return nextNumber(Kind.CREDIT, companyLegacyId);
    }

    private int nextNumber(Kind kind, int companyLegacyId) throws SQLException {
        int highest = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(number),0) FROM " + kind.table
                        + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                highest = result.getInt(1);
            }
        }
        int counter = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counter_value FROM company_auto_increment WHERE counter_name=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setString(1, kind.counter);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    counter = result.getInt(1);
                }
            }
        }
        return Math.max(highest, counter) + 1;
    }

    /**
     * Returns the summed total of all supplier credit invoices crediting a supplier invoice,
     * mirroring {@code SSSupplierCreditInvoiceMath.getSumForInvoice} (net + vat + rounding).
     */
    public java.math.BigDecimal supplierCreditInvoiceSum(int companyLegacyId, int invoiceNumber)
            throws SQLException {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (SSSupplierCreditInvoice creditInvoice
                : getSupplierCreditInvoices(companyLegacyId, invoiceNumber)) {
            sum = sum.add(se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath
                    .getTotalSum(creditInvoice));
        }
        return sum;
    }

    /** Marks a supplier invoice as booked and replaces its main voucher snapshot. */
    public void markSupplierInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        markEntered(Kind.INVOICE, companyLegacyId, number, voucher);
    }

    /** Marks a supplier credit invoice as booked and replaces its main voucher snapshot. */
    public void markSupplierCreditInvoiceEntered(int companyLegacyId, int number,
            SSVoucher voucher) throws SQLException {
        markEntered(Kind.CREDIT, companyLegacyId, number, voucher);
    }

    private void markEntered(Kind kind, int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + kind.table + " SET entered=true WHERE number=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No " + kind.table + " " + number + " for company "
                        + companyLegacyId);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + kind.table + " WHERE number=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                id = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + kind.voucherRowTable() + " WHERE " + kind.idColumn() + "=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + kind.voucherTable() + " WHERE " + kind.idColumn() + "=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        if (voucher != null) {
            insertVoucher(kind, id, "main", voucher);
        }
    }

    // ------------------------------------------------------------------ reads

    public List<SSSupplierInvoice> getSupplierInvoices(int companyLegacyId) throws SQLException {
        return readAll(Kind.INVOICE, companyLegacyId, null);
    }

    public List<SSSupplierCreditInvoice> getSupplierCreditInvoices(int companyLegacyId)
            throws SQLException {
        return readAll(Kind.CREDIT, companyLegacyId, null);
    }

    /** Reads the supplier credit invoices crediting a given supplier invoice number. */
    public List<SSSupplierCreditInvoice> getSupplierCreditInvoices(int companyLegacyId,
            int creditingNumber) throws SQLException {
        return readAll(Kind.CREDIT, companyLegacyId, creditingNumber);
    }

    private <T extends SSSupplierInvoice> List<T> readAll(Kind kind, int companyLegacyId,
            Integer creditingNumber) throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency currency : registers.getCurrencies()) {
            currencies.put(currency.getName(), currency);
        }
        Map<String, SSPaymentTerm> paymentTerms = new HashMap<>();
        for (SSPaymentTerm term : registers.getPaymentTerms()) {
            paymentTerms.put(term.getName(), term);
        }
        Map<String, SSUnit> units = new HashMap<>();
        for (SSUnit unit : registers.getUnits()) {
            units.put(unit.getName(), unit);
        }

        Map<Long, T> byId = new LinkedHashMap<>();
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        String creditFilter = creditingNumber == null ? "" : " AND crediting_invoice_number=?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,number," + creditColumn + "invoice_date,due_date,payment_term_name,"
                        + "supplier_number,supplier_name,reference_number,currency_code,"
                        + "currency_rate,tax_sum,rounding_sum,entered,bgc_entered,"
                        + "stock_influencing FROM " + kind.table
                        + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                        + creditFilter + " ORDER BY number")) {
            int parameter = 1;
            statement.setInt(parameter++, companyLegacyId);
            if (creditingNumber != null) {
                statement.setInt(parameter, creditingNumber);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    @SuppressWarnings("unchecked")
                    T invoice = (T) (kind == Kind.CREDIT
                            ? new SSSupplierCreditInvoice() : new SSSupplierInvoice());
                    int column = 1;
                    long id = result.getLong(column++);
                    int number = result.getInt(column++);
                    invoice.setNumber(result.wasNull() ? null : number);
                    if (kind == Kind.CREDIT) {
                        int crediting = result.getInt(column++);
                        ((SSSupplierCreditInvoice) invoice).setCreditingNr(
                                result.wasNull() ? null : crediting);
                    }
                    invoice.setLocalDate(result.getObject(column++, LocalDate.class));
                    invoice.setLocalDueDate(result.getObject(column++, LocalDate.class));
                    invoice.setPaymentTerm(paymentTerms.get(result.getString(column++)));
                    invoice.setSupplierNr(result.getString(column++));
                    invoice.setSupplierName(result.getString(column++));
                    invoice.setReferencenumber(result.getString(column++));
                    invoice.setCurrency(currencies.get(result.getString(column++)));
                    invoice.setCurrencyRate(result.getBigDecimal(column++));
                    invoice.setTaxSum(result.getBigDecimal(column++));
                    invoice.setRoundingSum(result.getBigDecimal(column++));
                    invoice.setEntered(result.getBoolean(column++));
                    invoice.setBGCEntered(result.getBoolean(column++));
                    invoice.setStockInfluencing(result.getBoolean(column));
                    invoice.getDefaultAccounts();
                    byId.put(id, invoice);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        String idFilter = "SELECT id FROM " + kind.table + " WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=" + companyLegacyId + ")";
        readRows(kind, idFilter, byId, units);
        readDefaultAccounts(kind, idFilter, byId);
        readVouchers(kind, idFilter, byId);
        return new ArrayList<>(byId.values());
    }

    // ------------------------------------------------------------------ writes

    public void addSupplierInvoice(int companyLegacyId, SSSupplierInvoice invoice)
            throws SQLException {
        add(Kind.INVOICE, companyLegacyId, invoice);
    }

    public void addSupplierCreditInvoice(int companyLegacyId,
            SSSupplierCreditInvoice creditInvoice) throws SQLException {
        add(Kind.CREDIT, companyLegacyId, creditInvoice);
    }

    private void add(Kind kind, int companyLegacyId, SSSupplierInvoice invoice)
            throws SQLException {
        long id;
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + kind.table + " (legacy_id,company_id,number," + creditColumn
                        + "invoice_date,due_date,payment_term_name,supplier_number,supplier_name,"
                        + "reference_number,currency_code,currency_rate,tax_sum,rounding_sum,"
                        + "entered,bgc_entered,stock_influencing) SELECT ?,id,"
                        + repeat("?,", 13 + (kind == Kind.CREDIT ? 1 : 0))
                        + "? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId(kind));
            if (invoice.getNumber() == null) {
                statement.setNull(i++, java.sql.Types.INTEGER);
            } else {
                statement.setInt(i++, invoice.getNumber());
            }
            if (kind == Kind.CREDIT) {
                Integer crediting = ((SSSupplierCreditInvoice) invoice).getCreditingNr();
                if (crediting == null) {
                    statement.setNull(i++, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(i++, crediting);
                }
            }
            setLocalDate(statement, i++, invoice.getLocalDate());
            setLocalDate(statement, i++, invoice.getLocalDueDate());
            statement.setString(i++, invoice.getPaymentTerm() == null
                    ? null : invoice.getPaymentTerm().getName());
            statement.setString(i++, invoice.getSupplierNr());
            statement.setString(i++, invoice.getSupplierName());
            statement.setString(i++, invoice.getReferencenumber());
            statement.setString(i++, invoice.getCurrency() == null
                    ? null : invoice.getCurrency().getName());
            statement.setBigDecimal(i++, invoice.getCurrencyRate());
            statement.setBigDecimal(i++, invoice.getTaxSum());
            statement.setBigDecimal(i++, invoice.getRoundingSum());
            statement.setBoolean(i++, invoice.isEntered());
            statement.setBoolean(i++, invoice.isBGCEntered());
            statement.setBoolean(i++, invoice.isStockInfluencing());
            statement.setInt(i, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for " + kind.table);
                }
                id = keys.getLong(1);
            }
        }
        int rowNumber = 0;
        for (SSSupplierInvoiceRow row : invoice.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.rowTable() + " (" + kind.idColumn() + ",company_id,"
                            + "row_number,product_number,description,unit_price,quantity,unit_name,"
                            + "unit_freight,account_number,project_number,result_unit_number) "
                            + "SELECT ?,id,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
                statement.setLong(1, id);
                statement.setInt(2, rowNumber++);
                statement.setString(3, row.getProductNr());
                statement.setString(4, row.getDescription());
                statement.setBigDecimal(5, row.getUnitprice());
                if (row.getQuantity() == null) {
                    statement.setNull(6, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(6, row.getQuantity());
                }
                statement.setString(7, row.getUnit() == null ? null : row.getUnit().getName());
                statement.setBigDecimal(8, row.getUnitFreight());
                if (row.getAccountNr() == null) {
                    statement.setNull(9, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(9, row.getAccountNr());
                }
                statement.setString(10, row.getProjectNr());
                statement.setString(11, row.getResultUnitNr());
                statement.setInt(12, companyLegacyId);
                statement.executeUpdate();
            }
        }
        for (Map.Entry<SSDefaultAccount, Integer> account
                : invoice.getDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.defaultAccountTable() + " (" + kind.idColumn()
                            + ",account_type,account_number) VALUES (?,?,?)")) {
                statement.setLong(1, id);
                statement.setString(2, account.getKey().name());
                if (account.getValue() == null) {
                    statement.setNull(3, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(3, account.getValue());
                }
                statement.executeUpdate();
            }
        }
        insertVoucher(kind, id, "main", invoice.getVoucher());
        insertVoucher(kind, id, "correction", invoice.getCorrection());
    }

    // ------------------------------------------------------------------ internals

    private void readRows(Kind kind, String idFilter,
            Map<Long, ? extends SSSupplierInvoice> byId, Map<String, SSUnit> units)
            throws SQLException {
        Map<Long, List<SSSupplierInvoiceRow>> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",product_number,description,unit_price,quantity,"
                        + "unit_name,unit_freight,account_number,project_number,result_unit_number "
                        + "FROM " + kind.rowTable() + " WHERE " + kind.idColumn() + " IN ("
                        + idFilter + ") ORDER BY " + kind.idColumn() + ",row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
                    row.setProductNr(result.getString(2));
                    row.setDescription(result.getString(3));
                    row.setUnitprice(result.getBigDecimal(4));
                    int quantity = result.getInt(5);
                    row.setQuantity(result.wasNull() ? null : quantity);
                    row.setUnit(units.get(result.getString(6)));
                    row.setUnitFreight(result.getBigDecimal(7));
                    row.setAccountNr(result.getObject(8, Integer.class));
                    row.setProjectNr(result.getString(9));
                    row.setResultUnitNr(result.getString(10));
                    rows.computeIfAbsent(result.getLong(1), key -> new ArrayList<>()).add(row);
                }
            }
        }
        rows.forEach((id, list) -> {
            SSSupplierInvoice invoice = byId.get(id);
            if (invoice != null) {
                invoice.setRows(list);
            }
        });
    }

    private void readDefaultAccounts(Kind kind, String idFilter,
            Map<Long, ? extends SSSupplierInvoice> byId) throws SQLException {
        Map<Long, Map<SSDefaultAccount, Integer>> accounts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",account_type,account_number FROM "
                        + kind.defaultAccountTable() + " WHERE " + kind.idColumn() + " IN ("
                        + idFilter + ") ORDER BY " + kind.idColumn() + ",account_type")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int number = result.getInt(3);
                    accounts.computeIfAbsent(result.getLong(1), key -> new HashMap<>())
                            .put(SSDefaultAccount.valueOf(result.getString(2)),
                                    result.wasNull() ? null : number);
                }
            }
        }
        accounts.forEach((id, map) -> {
            SSSupplierInvoice invoice = byId.get(id);
            if (invoice != null) {
                invoice.setDefaultAccounts(map);
            }
        });
    }

    private void readVouchers(Kind kind, String idFilter,
            Map<Long, ? extends SSSupplierInvoice> byId) throws SQLException {
        record VoucherKey(long invoiceId, String voucherKind) {}
        Map<VoucherKey, SSVoucher> vouchers = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",voucher_kind,number,voucher_date,description FROM "
                        + kind.voucherTable() + " WHERE " + kind.idColumn() + " IN (" + idFilter
                        + ") ORDER BY " + kind.idColumn() + ",voucher_kind")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = new SSVoucher(result.getInt(3));
                    voucher.setLocalDate(result.getObject(4, LocalDate.class));
                    voucher.setDescription(result.getString(5));
                    vouchers.put(new VoucherKey(result.getLong(1), result.getString(2)), voucher);
                }
            }
        }
        if (vouchers.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",voucher_kind,account_number,project_number,"
                        + "result_unit_number,debit,credit,edited_at,edited_signature,crossed,"
                        + "added FROM " + kind.voucherRowTable() + " WHERE " + kind.idColumn()
                        + " IN (" + idFilter + ") ORDER BY " + kind.idColumn()
                        + ",voucher_kind,row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = vouchers.get(
                            new VoucherKey(result.getLong(1), result.getString(2)));
                    if (voucher != null) {
                        voucher.getRows().add(NormalizedInvoiceStore.voucherRowFrom(result, 3));
                    }
                }
            }
        }
        vouchers.forEach((key, voucher) -> {
            SSSupplierInvoice invoice = byId.get(key.invoiceId());
            if (invoice == null) {
                return;
            }
            if ("main".equals(key.voucherKind())) {
                invoice.setVoucher(voucher);
            } else {
                invoice.setCorrection(voucher);
            }
        });
    }

    private void insertVoucher(Kind kind, long invoiceId, String voucherKind, SSVoucher voucher)
            throws SQLException {
        if (voucher == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + kind.voucherTable() + " (" + kind.idColumn()
                        + ",voucher_kind,number,voucher_date,description) VALUES (?,?,?,?,?)")) {
            statement.setLong(1, invoiceId);
            statement.setString(2, voucherKind);
            statement.setInt(3, voucher.getNumber());
            setLocalDate(statement, 4, voucher.getLocalDate());
            statement.setString(5, voucher.getDescription());
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.voucherRowTable() + " (" + kind.idColumn()
                            + ",voucher_kind,row_number,account_number,project_number,"
                            + "result_unit_number,debit,credit,edited_at,edited_signature,"
                            + "crossed,added) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, invoiceId);
                statement.setString(2, voucherKind);
                statement.setInt(3, rowNumber++);
                NormalizedInvoiceStore.bindVoucherRow(statement, 4, row);
                statement.executeUpdate();
            }
        }
    }

    private int nextLegacyId(Kind kind) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0)+1 FROM " + kind.table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String repeat(String token, int times) {
        return token.repeat(times);
    }

    private static void setLocalDate(PreparedStatement statement, int index, LocalDate date)
            throws SQLException {
        if (date == null) {
            statement.setNull(index, java.sql.Types.DATE);
        } else {
            statement.setObject(index, date);
        }
    }
}
