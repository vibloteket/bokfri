package org.fribok.bookkeeping.dataformat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

/**
 * SSDB-shaped reads/writes over the normalized supplier invoice and supplier credit invoice tables,
 * including ordered rows, default accounts, and the main/correction voucher snapshots. Writes
 * require the current normalized schema, a caller-owned transaction and exclusive application
 * access. Local savepoints do not establish whole-journal atomicity.
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

    /**
     * Stable SQL/legacy identities with a detached, mutable snapshot; never a displayed row index.
     */
    public record Stored<T extends SSSupplierInvoice>(long id, int legacyId, T document) {}

    private final Connection connection;
    private final NormalizedRegisterStore registers;

    /**
     * Uses caller-owned connections; writes require a transaction and exclusive application access.
     */
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
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COALESCE(MAX(number),0) FROM "
                                + kind.table
                                + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                highest = result.getInt(1);
            }
        }
        int counter = 0;
        try (PreparedStatement statement =
                connection.prepareStatement(
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
        for (SSSupplierCreditInvoice creditInvoice :
                getSupplierCreditInvoices(companyLegacyId, invoiceNumber)) {
            sum =
                    sum.add(
                            se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getTotalSum(
                                    creditInvoice));
        }
        return sum;
    }

    /** Marks a supplier invoice as booked and replaces its main voucher snapshot. */
    public void markSupplierInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        atomic(
                () -> {
                    markEntered(Kind.INVOICE, companyLegacyId, number, voucher);
                    return null;
                });
    }

    /** Marks a supplier credit invoice as booked and replaces its main voucher snapshot. */
    public void markSupplierCreditInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        atomic(
                () -> {
                    markEntered(Kind.CREDIT, companyLegacyId, number, voucher);
                    return null;
                });
    }

    private void markEntered(Kind kind, int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        validateVoucher(voucher);
        long id;
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE "
                                + kind.table
                                + " SET entered=true WHERE number=? "
                                + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "No " + kind.table + " " + number + " for company " + companyLegacyId);
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT id FROM "
                                + kind.table
                                + " WHERE number=? "
                                + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                id = result.getLong(1);
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM "
                                + kind.voucherRowTable()
                                + " WHERE "
                                + kind.idColumn()
                                + "=? AND voucher_kind='main'")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "DELETE FROM "
                                + kind.voucherTable()
                                + " WHERE "
                                + kind.idColumn()
                                + "=? AND voucher_kind='main'")) {
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
    public List<SSSupplierCreditInvoice> getSupplierCreditInvoices(
            int companyLegacyId, int creditingNumber) throws SQLException {
        return readAll(Kind.CREDIT, companyLegacyId, creditingNumber);
    }

    /**
     * Lists supplier invoices with stable SQL identities; no legacy defaults or business-number
     * allocation.
     *
     * @param company company migration identity
     * @return detached supplier invoices with stable SQL/legacy identities
     * @throws SQLException for storage or hydration errors
     */
    public List<Stored<SSSupplierInvoice>> listSupplierInvoices(int company) throws SQLException {
        return readStored(Kind.INVOICE, company, null, null);
    }

    /**
     * Lists supplier credits with stable SQL identities and exact detached snapshots.
     *
     * @param company company migration identity
     * @return detached supplier credits with stable SQL/legacy identities
     * @throws SQLException for storage or hydration errors
     */
    public List<Stored<SSSupplierCreditInvoice>> listSupplierCreditInvoices(int company)
            throws SQLException {
        return readStored(Kind.CREDIT, company, null, null);
    }

    /**
     * Finds one company-scoped supplier invoice by stable SQL identity.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @return matching snapshot, or empty for missing/foreign identities
     * @throws SQLException for storage or hydration errors
     */
    public Optional<Stored<SSSupplierInvoice>> findSupplierInvoice(int company, long id)
            throws SQLException {
        return this.<SSSupplierInvoice>readStored(Kind.INVOICE, company, null, id).stream()
                .findFirst();
    }

    /**
     * Finds one company-scoped supplier credit by stable SQL identity.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @return matching snapshot, or empty for missing/foreign identities
     * @throws SQLException for storage or hydration errors
     */
    public Optional<Stored<SSSupplierCreditInvoice>> findSupplierCreditInvoice(int company, long id)
            throws SQLException {
        return this.<SSSupplierCreditInvoice>readStored(Kind.CREDIT, company, null, id).stream()
                .findFirst();
    }

    private <T extends SSSupplierInvoice> List<T> readAll(
            Kind kind, int companyLegacyId, Integer creditingNumber) throws SQLException {
        return new ArrayList<>(
                this.<T>readStored(kind, companyLegacyId, creditingNumber, null).stream()
                        .map(Stored::document)
                        .toList());
    }

    private <T extends SSSupplierInvoice> List<Stored<T>> readStored(
            Kind kind, int companyLegacyId, Integer creditingNumber, Long onlyId)
            throws SQLException {
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
        Map<Long, Integer> legacyIds = new HashMap<>();
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        String creditFilter = creditingNumber == null ? "" : " AND crediting_invoice_number=?";
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT id,legacy_id,number,"
                                + creditColumn
                                + "invoice_date,due_date,payment_term_name,"
                                + "supplier_number,supplier_name,reference_number,currency_code,"
                                + "currency_rate,tax_sum,rounding_sum,entered,bgc_entered,"
                                + "stock_influencing FROM "
                                + kind.table
                                + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                                + creditFilter
                                + (onlyId == null ? "" : " AND id=?")
                                + " ORDER BY number,id")) {
            int parameter = 1;
            statement.setInt(parameter++, companyLegacyId);
            if (creditingNumber != null) {
                statement.setInt(parameter++, creditingNumber);
            }
            if (onlyId != null) statement.setLong(parameter, onlyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    @SuppressWarnings("unchecked")
                    T invoice =
                            (T)
                                    (kind == Kind.CREDIT
                                            ? SSSupplierCreditInvoice.forPersistence()
                                            : SSSupplierInvoice.forPersistence());
                    int column = 1;
                    long id = result.getLong(column++);
                    legacyIds.put(id, result.getInt(column++));
                    int number = result.getInt(column++);
                    invoice.setNumber(result.wasNull() ? null : number);
                    if (kind == Kind.CREDIT) {
                        int crediting = result.getInt(column++);
                        ((SSSupplierCreditInvoice) invoice)
                                .setCreditingNr(result.wasNull() ? null : crediting);
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
                    byId.put(id, invoice);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        String idFilter =
                "SELECT id FROM "
                        + kind.table
                        + " WHERE company_id="
                        + "(SELECT id FROM company WHERE legacy_id="
                        + companyLegacyId
                        + ")"
                        + (onlyId == null ? "" : " AND id=" + onlyId);
        readRows(kind, idFilter, byId, units);
        readDefaultAccounts(kind, idFilter, byId);
        readVouchers(kind, idFilter, byId);
        return byId.entrySet().stream()
                .map(e -> new Stored<>(e.getKey(), legacyIds.get(e.getKey()), e.getValue()))
                .toList();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Inserts an explicit snapshot atomically and returns its SQL identity; business counters are
     * unchanged.
     *
     * @param companyLegacyId company migration identity
     * @param invoice explicit complete snapshot
     * @return generated SQL identity; numbering counters remain caller-owned
     * @throws SQLException for invalid scope, references, unsupported graphs or storage errors
     */
    public long addSupplierInvoice(int companyLegacyId, SSSupplierInvoice invoice)
            throws SQLException {
        return atomic(() -> add(Kind.INVOICE, companyLegacyId, invoice));
    }

    /**
     * Inserts an explicit supplier credit atomically and returns its SQL identity.
     *
     * @param companyLegacyId company migration identity
     * @param creditInvoice explicit complete snapshot
     * @return generated SQL identity; numbering counters remain caller-owned
     * @throws SQLException for invalid scope, unsupported graphs or storage errors
     */
    public long addSupplierCreditInvoice(int companyLegacyId, SSSupplierCreditInvoice creditInvoice)
            throws SQLException {
        return atomic(() -> add(Kind.CREDIT, companyLegacyId, creditInvoice));
    }

    private long add(Kind kind, int companyLegacyId, SSSupplierInvoice invoice)
            throws SQLException {
        validateSnapshot(kind, invoice);
        requireAvailableNumber(kind, companyLegacyId, invoice.getNumber(), null);
        requireUnreferenced(kind, companyLegacyId, invoice.getNumber());
        long id;
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO "
                                + kind.table
                                + " (legacy_id,company_id,number,"
                                + creditColumn
                                + "invoice_date,due_date,payment_term_name,supplier_number,supplier_name,"
                                + "reference_number,currency_code,currency_rate,tax_sum,rounding_sum,"
                                + "entered,bgc_entered,stock_influencing)"
                                + " SELECT ?,id,"
                                + repeat("?,", 13 + (kind == Kind.CREDIT ? 1 : 0))
                                + "? FROM company WHERE legacy_id=?",
                        Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId(kind));
            i = bindHeader(statement, i, kind, invoice);
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
        insertChildren(kind, id, companyLegacyId, invoice);
        return id;
    }

    private int bindHeader(PreparedStatement statement, int i, Kind kind, SSSupplierInvoice invoice)
            throws SQLException {
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
        statement.setString(
                i++, invoice.getPaymentTerm() == null ? null : invoice.getPaymentTerm().getName());
        statement.setString(i++, invoice.getSupplierNr());
        statement.setString(i++, invoice.getSupplierName());
        statement.setString(i++, invoice.getReferencenumber());
        statement.setString(
                i++, invoice.getCurrency() == null ? null : invoice.getCurrency().getName());
        statement.setBigDecimal(i++, invoice.getCurrencyRate());
        statement.setBigDecimal(i++, invoice.getStoredTaxSum());
        statement.setBigDecimal(i++, invoice.getStoredRoundingSum());
        statement.setBoolean(i++, invoice.isEntered());
        statement.setBoolean(i++, invoice.isBGCEntered());
        statement.setBoolean(i++, invoice.isStockInfluencing());
        return i;
    }

    private void insertChildren(Kind kind, long id, int companyLegacyId, SSSupplierInvoice invoice)
            throws SQLException {
        int rowNumber = 0;
        for (SSSupplierInvoiceRow row : invoice.getRows()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + kind.rowTable()
                                    + " ("
                                    + kind.idColumn()
                                    + ",company_id,"
                                    + "row_number,product_number,description,unit_price,quantity,unit_name,"
                                    + "unit_freight,account_number,project_number,result_unit_number)"
                                    + " SELECT ?,id,?,?,?,?,?,?,?,?,?,? FROM company WHERE"
                                    + " legacy_id=?")) {
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
        for (Map.Entry<SSDefaultAccount, Integer> account :
                invoice.getStoredDefaultAccounts().entrySet()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + kind.defaultAccountTable()
                                    + " ("
                                    + kind.idColumn()
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

    /**
     * Replaces an unbooked, non-bank-exported invoice snapshot by SQL identity; no reference
     * detachment.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @param invoice explicit replacement; child positions follow the supplied order
     * @throws SQLException for protected/foreign/referenced targets or storage errors
     */
    public void updateSupplierInvoice(int company, long id, SSSupplierInvoice invoice)
            throws SQLException {
        atomic(
                () -> {
                    update(Kind.INVOICE, company, id, invoice);
                    return null;
                });
    }

    /**
     * Replaces an unbooked supplier credit, retaining its original source-invoice reference.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @param credit explicit replacement retaining its stored source link
     * @throws SQLException for protected/foreign targets, source retargeting or storage errors
     */
    public void updateSupplierCreditInvoice(int company, long id, SSSupplierCreditInvoice credit)
            throws SQLException {
        atomic(
                () -> {
                    update(Kind.CREDIT, company, id, credit);
                    return null;
                });
    }

    /**
     * Deletes an unbooked, unreferenced supplier invoice and its snapshots atomically.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @throws SQLException for protected/foreign/referenced targets or storage errors
     */
    public void deleteSupplierInvoice(int company, long id) throws SQLException {
        atomic(
                () -> {
                    delete(Kind.INVOICE, company, id);
                    return null;
                });
    }

    /**
     * Deletes an unbooked supplier credit and its own snapshots without rewriting its source
     * invoice.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @throws SQLException for protected/foreign targets or storage errors
     */
    public void deleteSupplierCreditInvoice(int company, long id) throws SQLException {
        atomic(
                () -> {
                    delete(Kind.CREDIT, company, id);
                    return null;
                });
    }

    private record Identity(Integer number, Integer crediting) {}

    private Identity editable(Kind kind, int company, long id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT number,entered,bgc_entered,"
                                + (kind == Kind.CREDIT
                                        ? "crediting_invoice_number"
                                        : "CAST(NULL AS INTEGER)")
                                + " FROM "
                                + kind.table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing or foreign supplier document");
                if (row.getBoolean(2) || row.getBoolean(3)) {
                    throw new SQLException(
                            "Booked or bank-exported supplier documents require a separate"
                                    + " workflow");
                }
                return new Identity(
                        row.getObject(1, Integer.class), row.getObject(4, Integer.class));
            }
        }
    }

    private void update(Kind kind, int company, long id, SSSupplierInvoice invoice)
            throws SQLException {
        validateSnapshot(kind, invoice);
        var old = editable(kind, company, id);
        if (invoice.isEntered() || invoice.isBGCEntered()) {
            throw new SQLException(
                    "Posting and bank-export state cannot be changed through graph editing");
        }
        if (kind == Kind.CREDIT
                && !Objects.equals(
                        old.crediting(), ((SSSupplierCreditInvoice) invoice).getCreditingNr())) {
            throw new SQLException("Supplier credit source changes require a separate workflow");
        }
        // Do not silently rewrite documents already consumed by payment, credit or purchase-order
        // workflows.
        requireUnreferenced(kind, company, old.number());
        if (!Objects.equals(old.number(), invoice.getNumber())) {
            requireUnreferenced(kind, company, invoice.getNumber());
            requireAvailableNumber(kind, company, invoice.getNumber(), id);
        }
        String columns =
                "number,"
                        + (kind == Kind.CREDIT ? "crediting_invoice_number," : "")
                        + "invoice_date,due_date,payment_term_name,supplier_number,supplier_name,reference_number,"
                        + "currency_code,currency_rate,tax_sum,rounding_sum,entered,bgc_entered,stock_influencing";
        try (var statement =
                connection.prepareStatement(
                        "UPDATE "
                                + kind.table
                                + " SET "
                                + String.join("=?,", columns.split(","))
                                + "=? WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            int i = bindHeader(statement, 1, kind, invoice);
            statement.setLong(i++, id);
            statement.setInt(i, company);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Missing or foreign supplier update target");
            }
        }
        deleteChildren(kind, id);
        insertChildren(kind, id, company, invoice);
    }

    private void delete(Kind kind, int company, long id) throws SQLException {
        var old = editable(kind, company, id);
        requireUnreferenced(kind, company, old.number());
        deleteChildren(kind, id);
        try (var statement =
                connection.prepareStatement(
                        "DELETE FROM "
                                + kind.table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Missing or foreign supplier deletion target");
            }
        }
    }

    private void deleteChildren(Kind kind, long id) throws SQLException {
        for (String table :
                List.of(
                        kind.voucherRowTable(),
                        kind.voucherTable(),
                        kind.defaultAccountTable(),
                        kind.rowTable())) {
            try (var statement =
                    connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE " + kind.idColumn() + "=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
    }

    private void requireAvailableNumber(Kind kind, int company, Integer number, Long exceptId)
            throws SQLException {
        if (number == null) return;
        try (var statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM "
                                + kind.table
                                + " WHERE number=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)"
                                + (exceptId == null ? "" : " AND id<>?"))) {
            statement.setInt(1, number);
            statement.setInt(2, company);
            if (exceptId != null) statement.setLong(3, exceptId);
            try (var row = statement.executeQuery()) {
                row.next();
                if (row.getLong(1) != 0) {
                    throw new SQLException("Duplicate supplier document number");
                }
            }
        }
    }

    private void requireUnreferenced(Kind kind, int company, Integer number) throws SQLException {
        if (kind != Kind.INVOICE || number == null) return;
        for (String reference :
                List.of(
                        "outpayment_row.invoice_number",
                        "supplier_credit_invoice.crediting_invoice_number",
                        "purchase_order.invoice_number")) {
            String[] parts = reference.split("\\.");
            String scope =
                    parts[0].equals("outpayment_row")
                            ? "outpayment_id IN (SELECT id FROM outpayment WHERE company_id=(SELECT"
                                    + " id FROM company WHERE legacy_id=?))"
                            : "company_id=(SELECT id FROM company WHERE legacy_id=?)";
            try (var statement =
                    connection.prepareStatement(
                            "SELECT COUNT(*) FROM "
                                    + parts[0]
                                    + " WHERE "
                                    + parts[1]
                                    + "=? AND "
                                    + scope)) {
                statement.setInt(1, number);
                statement.setInt(2, company);
                try (var row = statement.executeQuery()) {
                    row.next();
                    if (row.getLong(1) != 0) {
                        throw new SQLException("Supplier invoice is referenced by " + parts[0]);
                    }
                }
            }
        }
    }

    private static void validateSnapshot(Kind kind, SSSupplierInvoice invoice) throws SQLException {
        Objects.requireNonNull(invoice, "invoice");
        if (invoice.getClass()
                != (kind == Kind.CREDIT
                        ? SSSupplierCreditInvoice.class
                        : SSSupplierInvoice.class)) {
            throw new SQLException("Unsupported supplier document subtype");
        }
        if (invoice.getRows() == null || invoice.getStoredDefaultAccounts() == null) {
            throw new SQLException(
                    "Supplier snapshot requires explicit rows and default-account map");
        }
        exact(invoice.getCurrencyRate());
        exact(invoice.getStoredTaxSum());
        exact(invoice.getStoredRoundingSum());
        date(invoice.getLocalDate());
        date(invoice.getLocalDueDate());
        for (var row : invoice.getRows()) {
            if (row == null || row.getClass() != SSSupplierInvoiceRow.class) {
                throw new SQLException("Unsupported supplier row");
            }
            exact(row.getUnitprice());
            exact(row.getUnitFreight());
        }
        for (var key : invoice.getStoredDefaultAccounts().keySet()) {
            Objects.requireNonNull(key, "account type");
        }
        validateVoucher(invoice.getVoucher());
        validateVoucher(invoice.getCorrection());
    }

    private static void validateVoucher(SSVoucher voucher) throws SQLException {
        if (voucher == null) return;
        if (voucher.getClass() != SSVoucher.class
                || (voucher.getCorrects() != null || voucher.getCorrectedBy() != null)
                || voucher.getRows() == null) {
            throw new SQLException("Unsupported correction-linked supplier voucher snapshot");
        }
        date(voucher.getLocalDate());
        for (var row : voucher.getRows()) {
            if (row == null || row.getClass() != SSVoucherRow.class) {
                throw new SQLException("Unsupported supplier voucher row");
            }
            exact(row.getDebet());
            exact(row.getCredit());
        }
    }

    private static void exact(java.math.BigDecimal value) {
        if (value == null) return;
        try {
            if ((value.signum() != 0
                            && ((long) value.precision() - value.scale() > 70
                                    || value.stripTrailingZeros().scale() > 30))
                    || value.setScale(30, java.math.RoundingMode.UNNECESSARY).precision() > 100) {
                throw new ArithmeticException();
            }
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Amount must fit DECIMAL(100,30) exactly", invalid);
        }
    }

    private static void date(LocalDate value) {
        if (value != null && (value.getYear() < 1 || value.getYear() > 9999)) {
            throw new IllegalArgumentException("Date must fit SQL DATE years 0001..9999");
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    private <T> T atomic(SqlOperation<T> operation) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("Supplier writes require a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            T value = operation.run();
            connection.releaseSavepoint(savepoint);
            return value;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    // ------------------------------------------------------------------ internals

    private void readRows(
            Kind kind,
            String idFilter,
            Map<Long, ? extends SSSupplierInvoice> byId,
            Map<String, SSUnit> units)
            throws SQLException {
        Map<Long, List<SSSupplierInvoiceRow>> rows = new HashMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT "
                                + kind.idColumn()
                                + ",product_number,description,unit_price,quantity,unit_name,"
                                + "unit_freight,account_number,project_number,result_unit_number"
                                + " FROM "
                                + kind.rowTable()
                                + " WHERE "
                                + kind.idColumn()
                                + " IN ("
                                + idFilter
                                + ") ORDER BY "
                                + kind.idColumn()
                                + ",row_number")) {
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
        rows.forEach(
                (id, list) -> {
                    SSSupplierInvoice invoice = byId.get(id);
                    if (invoice != null) {
                        invoice.setRows(list);
                    }
                });
    }

    private void readDefaultAccounts(
            Kind kind, String idFilter, Map<Long, ? extends SSSupplierInvoice> byId)
            throws SQLException {
        Map<Long, Map<SSDefaultAccount, Integer>> accounts = new HashMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT "
                                + kind.idColumn()
                                + ",account_type,account_number FROM "
                                + kind.defaultAccountTable()
                                + " WHERE "
                                + kind.idColumn()
                                + " IN ("
                                + idFilter
                                + ") ORDER BY "
                                + kind.idColumn()
                                + ",account_type")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    accounts.computeIfAbsent(result.getLong(1), key -> new HashMap<>())
                            .put(
                                    SSDefaultAccount.valueOf(result.getString(2)),
                                    result.getObject(3, Integer.class));
                }
            }
        }
        accounts.forEach(
                (id, map) -> {
                    SSSupplierInvoice invoice = byId.get(id);
                    if (invoice != null) {
                        invoice.setDefaultAccounts(map);
                    }
                });
    }

    private void readVouchers(
            Kind kind, String idFilter, Map<Long, ? extends SSSupplierInvoice> byId)
            throws SQLException {
        record VoucherKey(long invoiceId, String voucherKind) {}
        Map<VoucherKey, SSVoucher> vouchers = new HashMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT "
                                + kind.idColumn()
                                + ",voucher_kind,number,voucher_date,description FROM "
                                + kind.voucherTable()
                                + " WHERE "
                                + kind.idColumn()
                                + " IN ("
                                + idFilter
                                + ") ORDER BY "
                                + kind.idColumn()
                                + ",voucher_kind")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher =
                            new SSVoucher(result.getInt(3), result.getObject(4, LocalDate.class));
                    voucher.setDescription(result.getString(5));
                    vouchers.put(new VoucherKey(result.getLong(1), result.getString(2)), voucher);
                }
            }
        }
        if (vouchers.isEmpty()) {
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT "
                                + kind.idColumn()
                                + ",voucher_kind,account_number,project_number,"
                                + "result_unit_number,debit,credit,edited_at,edited_signature,crossed,added"
                                + " FROM "
                                + kind.voucherRowTable()
                                + " WHERE "
                                + kind.idColumn()
                                + " IN ("
                                + idFilter
                                + ") ORDER BY "
                                + kind.idColumn()
                                + ",voucher_kind,row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher =
                            vouchers.get(new VoucherKey(result.getLong(1), result.getString(2)));
                    if (voucher != null) {
                        voucher.getRows().add(NormalizedInvoiceStore.voucherRowFrom(result, 3));
                    }
                }
            }
        }
        vouchers.forEach(
                (key, voucher) -> {
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
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO "
                                + kind.voucherTable()
                                + " ("
                                + kind.idColumn()
                                + ",voucher_kind,number,voucher_date,description) VALUES"
                                + " (?,?,?,?,?)")) {
            statement.setLong(1, invoiceId);
            statement.setString(2, voucherKind);
            statement.setInt(3, voucher.getNumber());
            setLocalDate(statement, 4, voucher.getLocalDate());
            statement.setString(5, voucher.getDescription());
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + kind.voucherRowTable()
                                    + " ("
                                    + kind.idColumn()
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
                ResultSet result =
                        statement.executeQuery(
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
