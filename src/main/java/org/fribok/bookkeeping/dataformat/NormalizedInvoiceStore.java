package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SSDB-shaped reads/writes over the normalized customer invoice and credit invoice tables,
 * including addresses, default accounts, ordered rows, and the booked voucher snapshot.
 * Writes use local savepoints and require a caller-owned transaction; they never commit,
 * close the connection, allocate business numbers or change counters. General editing and
 * deletion are limited to unbooked documents. Posting/correction and referenced-order
 * detachment are workflow responsibilities, not implicit side effects of repository writes.
 */
public final class NormalizedInvoiceStore {

    /** Stable SQL identity, migration identity and detached document snapshot. */
    public record Stored<T>(long id, int legacyId, T document) { }

    /** Which sale table family an operation targets. */
    private enum Kind {
        INVOICE("customer_invoice", "invoice"),
        CREDIT("customer_credit_invoice", "creditinvoice");

        final String table;
        final String counter;

        Kind(String table, String counter) {
            this.table = table;
            this.counter = counter;
        }

        String addressTable() {
            return table + "_address";
        }

        String defaultAccountTable() {
            return table + "_default_account";
        }

        String rowTable() {
            return table + "_row";
        }

        String voucherTable() {
            return table + "_voucher";
        }

        String voucherRowTable() {
            return table + "_voucher_row";
        }

        String idColumn() {
            return this == CREDIT ? "credit_invoice_id" : "invoice_id";
        }
    }

    private final Connection connection;
    private final NormalizedRegisterStore registers;

    /** Uses the supplied connection; the caller owns its transaction and lifecycle. */
    public NormalizedInvoiceStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.registers = new NormalizedRegisterStore(connection);
    }

    /**
     * Returns the number the next created invoice receives: one above the greater of the
     * highest existing invoice number and the company's "invoice" auto-increment counter,
     * mirroring the legacy numbering contract.
     */
    public int nextInvoiceNumber(int companyLegacyId) throws SQLException {
        return nextNumber(Kind.INVOICE, companyLegacyId);
    }

    /** Returns the number the next created credit invoice receives, same legacy contract. */
    public int nextCreditInvoiceNumber(int companyLegacyId) throws SQLException {
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
     * Returns the sum of inpayment rows paying an invoice, mirroring
     * {@code SSInpaymentMath.getSumForInvoice}.
     */
    public java.math.BigDecimal inpaymentSum(int companyLegacyId, int invoiceNumber)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(r.value),0) FROM inpayment_row r "
                        + "JOIN inpayment p ON p.id=r.inpayment_id "
                        + "WHERE p.company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "AND r.invoice_number=?")) {
            statement.setInt(1, companyLegacyId);
            statement.setInt(2, invoiceNumber);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBigDecimal(1);
            }
        }
    }

    /**
     * Returns the summed total of all credit invoices crediting an invoice, mirroring
     * {@code SSCreditInvoiceMath.getSumForInvoice}.
     */
    public java.math.BigDecimal creditInvoiceSum(int companyLegacyId, int invoiceNumber,
            boolean roundingOff) throws SQLException {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (SSCreditInvoice creditInvoice : getCreditInvoices(companyLegacyId, invoiceNumber)) {
            sum = sum.add(se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                    creditInvoice, roundingOff));
        }
        return sum;
    }

    // ------------------------------------------------------------------ reads

    public List<SSInvoice> getInvoices(int companyLegacyId) throws SQLException {
        return this.<SSInvoice>readSales(Kind.INVOICE, companyLegacyId, null).stream()
                .map(Stored::document).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Reads all credit invoices of a company. */
    public List<SSCreditInvoice> getCreditInvoices(int companyLegacyId) throws SQLException {
        return this.<SSCreditInvoice>readSales(Kind.CREDIT, companyLegacyId, null).stream()
                .map(Stored::document).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Reads the credit invoices crediting a given invoice number. */
    public List<SSCreditInvoice> getCreditInvoices(int companyLegacyId, int creditingNumber)
            throws SQLException {
        return this.<SSCreditInvoice>readSales(Kind.CREDIT, companyLegacyId, creditingNumber).stream()
                .map(Stored::document).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private <T extends SSInvoice> List<Stored<T>> readSales(Kind kind, int companyLegacyId,
            Integer creditingNumber) throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency currency : registers.getCurrencies()) {
            currencies.put(currency.getName(), currency);
        }
        Map<String, SSPaymentTerm> paymentTerms = new HashMap<>();
        for (SSPaymentTerm term : registers.getPaymentTerms()) {
            paymentTerms.put(term.getName(), term);
        }
        Map<String, SSDeliveryTerm> deliveryTerms = new HashMap<>();
        for (SSDeliveryTerm term : registers.getDeliveryTerms()) {
            deliveryTerms.put(term.getName(), term);
        }
        Map<String, SSDeliveryWay> deliveryWays = new HashMap<>();
        for (SSDeliveryWay way : registers.getDeliveryWays()) {
            deliveryWays.put(way.getName(), way);
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
                                + "invoice_date,due_date,customer_number,"
                                + "customer_name,our_contact_person,customer_contact_person,delay_interest,"
                                + "currency_code,payment_term_name,delivery_term_name,delivery_way_name,"
                                + "tax_free,invoice_text,tax_rate_1,tax_rate_2,tax_rate_3,"
                                + "eu_sale_commodity,eu_sale_third_party_commodity,printed,invoice_type,"
                                + "currency_rate,customer_order_number,ocr_number,entered,reminder_count,interest_invoiced,stock_influencing,order_numbers"
                                + " FROM "
                                + kind.table
                                + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                                + creditFilter
                                + " ORDER BY number,id")) {
            int parameter = 1;
            statement.setInt(parameter++, companyLegacyId);
            if (creditingNumber != null) {
                statement.setInt(parameter, creditingNumber);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    @SuppressWarnings("unchecked")
                    T sale = (T) (kind == Kind.CREDIT ? SSCreditInvoice.forPersistence() : SSInvoice.forPersistence());
                    int column = 1;
                    long id = result.getLong(column++);
                    legacyIds.put(id, result.getInt(column++));
                    int number = result.getInt(column++);
                    sale.setNumber(result.wasNull() ? null : number);
                    if (kind == Kind.CREDIT) {
                        int crediting = result.getInt(column++);
                        ((SSCreditInvoice) sale).setCreditingNr(
                                result.wasNull() ? null : crediting);
                    }
                    sale.setLocalDate(result.getObject(column++, LocalDate.class));
                    sale.setLocalDueDate(result.getObject(column++, LocalDate.class));
                    sale.setCustomerNr(result.getString(column++));
                    sale.setCustomerName(result.getString(column++));
                    sale.setOurContactPerson(result.getString(column++));
                    sale.setYourContactPerson(result.getString(column++));
                    sale.setDelayInterest(result.getBigDecimal(column++));
                    sale.setCurrency(currencies.get(result.getString(column++)));
                    sale.setPaymentTerm(paymentTerms.get(result.getString(column++)));
                    sale.setDeliveryTerm(deliveryTerms.get(result.getString(column++)));
                    sale.setDeliveryWay(deliveryWays.get(result.getString(column++)));
                    sale.setTaxFree(result.getBoolean(column++));
                    sale.setText(result.getString(column++));
                    sale.setStoredTaxRates(result.getBigDecimal(column++), result.getBigDecimal(column++),
                            result.getBigDecimal(column++));
                    sale.setEuSaleCommodity(result.getBoolean(column++));
                    sale.setEuSaleYhirdPartCommodity(result.getBoolean(column++));
                    sale.setPrinted(result.getBoolean(column++));
                    String type = result.getString(column++);
                    sale.setType(type == null ? null : SSInvoiceType.valueOf(type));
                    sale.setCurrencyRate(result.getBigDecimal(column++));
                    sale.setYourOrderNumber(result.getString(column++));
                    sale.setOCRNumber(result.getString(column++));
                    sale.setEntered(result.getBoolean(column++));
                    sale.setNumRemainders(result.getInt(column++));
                    sale.setInterestInvoiced(result.getBoolean(column++));
                    sale.setStockInfluencing(result.getBoolean(column++));
                    sale.setOrderNumbers(result.getString(column));
                    sale.getDefaultAccounts();
                    byId.put(id, sale);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        String idFilter = "SELECT id FROM " + kind.table + " WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=" + companyLegacyId + ")";
        readAddresses(kind, idFilter, byId);
        readDefaultAccounts(kind, idFilter, byId);
        readRows(kind, idFilter, byId, units);
        readVoucherSnapshots(kind, idFilter, byId);
        return byId.entrySet().stream()
                .map(entry -> new Stored<>(entry.getKey(), legacyIds.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    /**
     * Existing-row amounts; no displayed/list index is used.
     *
     * @param rowNumber persisted row key
     * @param quantity nullable quantity, exactly representable as DECIMAL(100,30)
     * @param unitPrice nullable unit price, exactly representable as DECIMAL(100,30)
     */
    public record LineAmounts(
            int rowNumber, java.math.BigDecimal quantity, java.math.BigDecimal unitPrice) {
        /** Validates both nullable amounts without rounding. */
        public LineAmounts {
            requireExactAmount(quantity);
            requireExactAmount(unitPrice);
        }
    }

    /**
     * Optional existing-row description/discount patch; raw nulls remain distinct.
     *
     * @param rowNumber persisted row key
     * @param description nullable stored description
     * @param discount nullable exact percentage; changed values must be within 0..100
     */
    public record LineDetails(int rowNumber, String description, java.math.BigDecimal discount) {
        /** Validates exact decimal storage without rejecting historical out-of-range values. */
        public LineDetails {
            requireExactAmount(discount);
        }
    }

    /**
     * Existing-row accounting patch; tax code is its persisted enum name, never a display label.
     *
     * @param rowNumber persisted row key
     * @param account nullable account number; business validation belongs to the explicit-year service
     * @param taxCode nullable persisted SSTaxCode name
     */
    public record LineAccounting(int rowNumber, Integer account, String taxCode) {
        /** Rejects unknown codes without singleton-backed display decoding. */
        public LineAccounting {
            if (taxCode != null) SSTaxCode.valueOf(taxCode);
        }
    }

    /**
     * Raw stored header tax values. Nullable rates are never replaced by defaults.
     *
     * @param taxFree stored non-null exemption flag
     * @param rate1 nullable exact stored rate for TAXRATE_1
     * @param rate2 nullable exact stored rate for TAXRATE_2
     * @param rate3 nullable exact stored rate for TAXRATE_3
     */
    public record TaxValues(boolean taxFree, java.math.BigDecimal rate1,
            java.math.BigDecimal rate2, java.math.BigDecimal rate3) {
        /** Validates exact storage; changed-value business rules belong to the service. */
        public TaxValues {
            requireExactAmount(rate1);
            requireExactAmount(rate2);
            requireExactAmount(rate3);
        }
    }

    /** Optional stored exchange-rate patch; no currency code or catalogue rate is changed. */
    public record ExchangeRateValues(java.math.BigDecimal rate) {
        /** Requires exact nullable storage; the service validates changed values and currency context. */
        public ExchangeRateValues {
            requireExactAmount(rate);
        }
    }

    /**
     * Immutable dates and existing-row/header-tax/exchange patches; other fields stay untouched.
     *
     * @param date nullable invoice date, within SQL DATE years 0001-9999
     * @param dueDate nullable due date, within SQL DATE years 0001-9999
     * @param rows row amount updates; empty means no amount changes at the store boundary
     * @param details optional row detail patches; omitted keys remain unchanged
     * @param accounting optional row account/tax patches; omitted keys remain unchanged
     * @param tax optional complete raw header-tax patch; null preserves all header tax columns
     * @param exchange optional stored-rate patch; null preserves the rate and all currency codes
     */
    public record FinancialValues(
            LocalDate date, LocalDate dueDate, List<LineAmounts> rows, List<LineDetails> details,
            List<LineAccounting> accounting, TaxValues tax, ExchangeRateValues exchange) {
        /** Preserves the exchange rate for existing header-tax callers. */
        public FinancialValues(LocalDate date, LocalDate dueDate, List<LineAmounts> rows,
                List<LineDetails> details, List<LineAccounting> accounting, TaxValues tax) {
            this(date, dueDate, rows, details, accounting, tax, null);
        }
        /** Preserves header taxes for existing row-accounting callers. */
        public FinancialValues(LocalDate date, LocalDate dueDate, List<LineAmounts> rows,
                List<LineDetails> details, List<LineAccounting> accounting) {
            this(date, dueDate, rows, details, accounting, null);
        }
        /** Preserves accounting fields for existing detail callers. */
        public FinancialValues(LocalDate date, LocalDate dueDate, List<LineAmounts> rows, List<LineDetails> details) {
            this(date, dueDate, rows, details, List.of());
        }
        /** Preserves descriptions and discounts for existing amount-only callers. */
        public FinancialValues(LocalDate date, LocalDate dueDate, List<LineAmounts> rows) {
            this(date, dueDate, rows, List.of());
        }

        /** Copies row updates and validates nullable SQL dates. */
        public FinancialValues {
            rows = List.copyOf(rows);
            details = List.copyOf(details);
            accounting = List.copyOf(accounting);
            requireSqlDate(date);
            requireSqlDate(dueDate);
        }
    }

    /** Reads raw invoice tax values, including nullable rates, without defaults. */
    public Optional<TaxValues> findInvoiceTaxValues(int company, long id) throws SQLException {
        return readTaxValues(Kind.INVOICE, company, id);
    }

    /** Reads raw credit tax values without defaults. */
    public Optional<TaxValues> findCreditTaxValues(int company, long id) throws SQLException {
        return readTaxValues(Kind.CREDIT, company, id);
    }

    private Optional<TaxValues> readTaxValues(Kind kind, int company, long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tax_free,tax_rate_1,tax_rate_2,tax_rate_3 FROM "
                + kind.table + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new TaxValues(row.getBoolean(1), row.getBigDecimal(2),
                        row.getBigDecimal(3), row.getBigDecimal(4)));
            }
        }
    }

    private static void requireExactAmount(java.math.BigDecimal value) {
        if (value == null) return;
        try {
            if (value.setScale(30, java.math.RoundingMode.UNNECESSARY).precision() > 100)
                throw new ArithmeticException();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Amount must fit DECIMAL(100,30) exactly", error);
        }
    }

    private static void requireSqlDate(LocalDate value) {
        if (value != null && (value.getYear() < 1 || value.getYear() > 9999))
            throw new IllegalArgumentException("Date must fit SQL DATE");
    }

    /**
     * Updates only unbooked invoice dates and supplied row/tax/exchange patches under a savepoint.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @param values explicit date and row amount updates
     * @throws SQLException for missing/foreign/booked targets, row keys or transaction errors
     */
    public void updateInvoiceFinancialValues(int company, long id, FinancialValues values)
            throws SQLException {
        atomic(
                () -> {
                    updateFinancialValues(Kind.INVOICE, company, id, values);
                    return null;
                });
    }

    /**
     * Updates only unbooked credit dates and supplied row/tax/exchange patches under a savepoint.
     *
     * @param company company migration identity
     * @param id stable SQL identity
     * @param values explicit date and row amount updates
     * @throws SQLException for missing/foreign/booked targets, row keys or transaction errors
     */
    public void updateCreditFinancialValues(int company, long id, FinancialValues values)
            throws SQLException {
        atomic(
                () -> {
                    updateFinancialValues(Kind.CREDIT, company, id, values);
                    return null;
                });
    }

    private void updateFinancialValues(Kind kind, int company, long id, FinancialValues values)
            throws SQLException {
        Objects.requireNonNull(values, "values");
        editableIdentity(kind, company, id);
        try (var statement =
                connection.prepareStatement(
                        "UPDATE "
                                + kind.table
                                + " SET invoice_date=?,due_date=? WHERE id=? AND entered=FALSE AND"
                                + " company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            setLocalDate(statement, 1, values.date());
            setLocalDate(statement, 2, values.dueDate());
            statement.setLong(3, id);
            statement.setInt(4, company);
            if (statement.executeUpdate() != 1)
                throw new SQLException("Missing, foreign or booked document");
        }
        if (values.tax() != null) {
            var tax = values.tax();
            try (var statement = connection.prepareStatement("UPDATE " + kind.table
                    + " SET tax_free=?,tax_rate_1=?,tax_rate_2=?,tax_rate_3=? WHERE id=? AND entered=FALSE"
                    + " AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setBoolean(1, tax.taxFree());
                statement.setBigDecimal(2, tax.rate1());
                statement.setBigDecimal(3, tax.rate2());
                statement.setBigDecimal(4, tax.rate3());
                statement.setLong(5, id);
                statement.setInt(6, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing, foreign or booked tax target");
            }
        }
        if (values.exchange() != null) {
            try (var statement = connection.prepareStatement("UPDATE " + kind.table
                    + " SET currency_rate=? WHERE id=? AND entered=FALSE"
                    + " AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setBigDecimal(1, values.exchange().rate());
                statement.setLong(2, id);
                statement.setInt(3, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing, foreign or booked exchange target");
            }
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (var row : values.rows()) {
            if (!seen.add(row.rowNumber()))
                throw new IllegalArgumentException("Duplicate stored row key");
            try (var statement =
                    connection.prepareStatement(
                            "UPDATE "
                                    + kind.rowTable()
                                    + " SET quantity=?,unit_price=? WHERE "
                                    + kind.idColumn()
                                    + "=? AND row_number=? AND company_id=(SELECT id FROM company"
                                    + " WHERE legacy_id=?)")) {
                statement.setBigDecimal(1, row.quantity());
                statement.setBigDecimal(2, row.unitPrice());
                statement.setLong(3, id);
                statement.setInt(4, row.rowNumber());
                statement.setInt(5, company);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("Missing or foreign stored row key");
            }
        }
        seen.clear();
        for (var row : values.details()) {
            if (!seen.add(row.rowNumber()))
                throw new IllegalArgumentException("Duplicate stored detail row key");
            // Read current discount so unchanged historical out-of-range values can survive.
            try (var statement = connection.prepareStatement(
                    "SELECT discount FROM " + kind.rowTable() + " WHERE " + kind.idColumn()
                    + "=? AND row_number=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setLong(1, id);
                statement.setInt(2, row.rowNumber());
                statement.setInt(3, company);
                try (var current = statement.executeQuery()) {
                    if (!current.next()) throw new SQLException("Missing or foreign stored detail row key");
                    var before = current.getBigDecimal(1);
                    var after = row.discount();
                    boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
                    if (!same && after != null && (after.signum() < 0
                            || after.compareTo(java.math.BigDecimal.valueOf(100)) > 0))
                        throw new IllegalArgumentException("Discount must be within 0..100");
                }
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE " + kind.rowTable() + " SET description=?,discount=? WHERE "
                    + kind.idColumn() + "=? AND row_number=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                statement.setString(1, row.description());
                statement.setBigDecimal(2, row.discount());
                statement.setLong(3, id);
                statement.setInt(4, row.rowNumber());
                statement.setInt(5, company);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing or foreign stored detail row key");
            }
        }
        seen.clear();
        for (var row : values.accounting()) {
            if (!seen.add(row.rowNumber()))
                throw new IllegalArgumentException("Duplicate stored accounting row key");
            try (var statement = connection.prepareStatement(
                    "UPDATE " + kind.rowTable() + " SET account_number=?,tax_code=? WHERE "
                    + kind.idColumn() + "=? AND row_number=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                if (row.account() == null) statement.setNull(1, java.sql.Types.INTEGER);
                else statement.setInt(1, row.account());
                statement.setString(2, row.taxCode());
                statement.setLong(3, id);
                statement.setInt(4, row.rowNumber());
                statement.setInt(5, company);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("Missing or foreign stored accounting row key");
            }
        }
    }

    /** Non-financial persisted fields; deliberately excludes dates, amounts, booking and voucher data. */
    public record Metadata(boolean printed, String ocrNumber, int reminderCount, boolean interestInvoiced) { }

    /** Reads invoice metadata by stable identity without hydrating or generating financial snapshots. */
    public Optional<Metadata> findInvoiceMetadata(int companyLegacyId, long id) throws SQLException {
        return readMetadata(Kind.INVOICE, companyLegacyId, id);
    }

    /** Reads credit metadata by stable identity, including booked documents. */
    public Optional<Metadata> findCreditInvoiceMetadata(int companyLegacyId, long id) throws SQLException {
        return readMetadata(Kind.CREDIT, companyLegacyId, id);
    }

    /**
     * Changes only non-financial invoice fields, including on booked documents. Expected fields
     * must still match; changes never replace voucher snapshots or mark a document unbooked.
     */
    public void updateInvoiceMetadata(int companyLegacyId, long id, Metadata expected, Metadata replacement)
            throws SQLException {
        atomic(() -> { updateMetadata(Kind.INVOICE, companyLegacyId, id, expected, replacement); return null; });
    }

    /** Changes only credit metadata with the same optimistic comparison and transaction contract. */
    public void updateCreditInvoiceMetadata(int companyLegacyId, long id, Metadata expected, Metadata replacement)
            throws SQLException {
        atomic(() -> { updateMetadata(Kind.CREDIT, companyLegacyId, id, expected, replacement); return null; });
    }

    private Optional<Metadata> readMetadata(Kind kind, int companyLegacyId, long id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT printed,ocr_number,reminder_count,interest_invoiced FROM "
                                + kind.table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new Metadata(result.getBoolean(1), result.getString(2),
                        result.getInt(3), result.getBoolean(4))) : Optional.empty();
            }
        }
    }

    private void updateMetadata(Kind kind, int companyLegacyId, long id, Metadata expected, Metadata replacement)
            throws SQLException {
        Objects.requireNonNull(expected, "expected metadata");
        Objects.requireNonNull(replacement, "replacement metadata");
        if (replacement.reminderCount() < 0)
            throw new IllegalArgumentException("Negative reminder count");
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE "
                                + kind.table
                                + " SET printed=?,ocr_number=?,reminder_count=?,interest_invoiced=?"
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?) AND printed=? AND reminder_count=? AND"
                                + " interest_invoiced=? AND (ocr_number=? OR (ocr_number IS NULL"
                                + " AND ? IS NULL))")) {
            statement.setBoolean(1, replacement.printed());
            statement.setString(2, replacement.ocrNumber());
            statement.setInt(3, replacement.reminderCount());
            statement.setBoolean(4, replacement.interestInvoiced());
            statement.setLong(5, id);
            statement.setInt(6, companyLegacyId);
            statement.setBoolean(7, expected.printed());
            statement.setInt(8, expected.reminderCount());
            statement.setBoolean(9, expected.interestInvoiced());
            statement.setString(10, expected.ocrNumber());
            statement.setString(11, expected.ocrNumber());
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Missing, company-mismatched or stale "
                                + kind.table
                                + " metadata for "
                                + id);
            }
        }
    }

    /** Lists invoice snapshots with stable SQL identities (numbers may be null/duplicated). */
    public List<Stored<SSInvoice>> listInvoices(int companyLegacyId) throws SQLException {
        return readSales(Kind.INVOICE, companyLegacyId, null);
    }

    /** Lists credit snapshots with stable SQL identities. */
    public List<Stored<SSCreditInvoice>> listCreditInvoices(int companyLegacyId) throws SQLException {
        return readSales(Kind.CREDIT, companyLegacyId, null);
    }

    /** Finds a detached invoice by company and SQL identity, never by displayed number. */
    public Optional<Stored<SSInvoice>> findInvoice(int companyLegacyId, long id) throws SQLException {
        return listInvoices(companyLegacyId).stream().filter(stored -> stored.id() == id).findFirst();
    }

    /** Finds a detached credit by company and SQL identity. */
    public Optional<Stored<SSCreditInvoice>> findCreditInvoice(int companyLegacyId, long id)
            throws SQLException {
        return listCreditInvoices(companyLegacyId).stream().filter(stored -> stored.id() == id).findFirst();
    }

    /**
     * Replaces an unbooked invoice graph in place. A referenced number cannot be changed.
     * Booked-document correction/metadata workflows require a separate posting service.
     */
    public void updateInvoice(int companyLegacyId, long id, SSInvoice invoice) throws SQLException {
        atomic(() -> { updateSale(Kind.INVOICE, companyLegacyId, id, invoice); return null; });
    }

    /** Replaces an unbooked credit graph, retaining its SQL and migration identities. */
    public void updateCreditInvoice(int companyLegacyId, long id, SSCreditInvoice credit)
            throws SQLException {
        atomic(() -> { updateSale(Kind.CREDIT, companyLegacyId, id, credit); return null; });
    }

    /** Deletes an unbooked, unreferenced invoice and its snapshots; never deletes ledger vouchers. */
    public void deleteInvoice(int companyLegacyId, long id) throws SQLException {
        atomic(() -> { deleteSale(Kind.INVOICE, companyLegacyId, id); return null; });
    }

    /** Deletes an unbooked credit and its snapshots; never deletes ledger vouchers. */
    public void deleteCreditInvoice(int companyLegacyId, long id) throws SQLException {
        atomic(() -> { deleteSale(Kind.CREDIT, companyLegacyId, id); return null; });
    }

    private record Identity(Integer number, boolean entered) { }

    private Identity editableIdentity(Kind kind, int companyLegacyId, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT number,entered FROM " + kind.table + " WHERE id=? AND company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing " + kind.table + " identity " + id
                            + " for company " + companyLegacyId);
                }
                Identity identity = new Identity(result.getObject(1, Integer.class), result.getBoolean(2));
                if (identity.entered()) {
                    throw new SQLException(
                            "Booked " + kind.table + " requires a posting/correction workflow");
                }
                return identity;
            }
        }
    }

    private void updateSale(Kind kind, int companyLegacyId, long id, SSInvoice invoice)
            throws SQLException {
        validateSnapshot(kind, invoice);
        Identity old = editableIdentity(kind, companyLegacyId, id);
        if (invoice.isEntered()) {
            throw new SQLException(
                    "Use markEntered in a posting transaction, not document editing");
        }
        if (!Objects.equals(old.number(), invoice.getNumber())) {
            requireUnreferenced(kind, companyLegacyId, old.number());
            requireUnreferenced(kind, companyLegacyId, invoice.getNumber());
            requireAvailableNumber(kind, companyLegacyId, invoice.getNumber(), id);
        }
        String columns = "number," + (kind == Kind.CREDIT ? "crediting_invoice_number," : "")
                + "invoice_date,due_date,customer_number,customer_name,our_contact_person,"
                + "customer_contact_person,delay_interest,currency_code,payment_term_name,"
                + "delivery_term_name,delivery_way_name,tax_free,invoice_text,tax_rate_1,"
                + "tax_rate_2,tax_rate_3,eu_sale_commodity,eu_sale_third_party_commodity,printed,"
                + "invoice_type,currency_rate,customer_order_number,ocr_number,entered,"
                + "reminder_count,interest_invoiced,stock_influencing,order_numbers";
        String assignments = String.join("=?,", columns.split(",")) + "=?";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + kind.table + " SET " + assignments + " WHERE id=? AND company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?)")) {
            int index = bindHeader(statement, 1, kind, invoice);
            statement.setLong(index++, id);
            statement.setInt(index, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Missing or company-mismatched " + kind.table + " identity " + id);
            }
        }
        deleteChildren(kind, id);
        insertChildren(kind, id, companyLegacyId, invoice);
    }

    private void deleteSale(Kind kind, int companyLegacyId, long id) throws SQLException {
        Identity identity = editableIdentity(kind, companyLegacyId, id);
        requireUnreferenced(kind, companyLegacyId, identity.number());
        deleteChildren(kind, id);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + kind.table + " WHERE id=? AND company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Missing or company-mismatched " + kind.table + " identity " + id);
            }
        }
    }

    private void deleteChildren(Kind kind, long id) throws SQLException {
        for (String table : List.of(kind.voucherRowTable(), kind.voucherTable(),
                kind.rowTable(), kind.defaultAccountTable(), kind.addressTable())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE " + kind.idColumn() + "=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
    }

    private void requireUnreferenced(Kind kind, int companyLegacyId, Integer number) throws SQLException {
        if (kind != Kind.INVOICE || number == null) {
            return;
        }
        // These legacy relationships are number-based, not foreign keys. Null numbers do not
        // designate a target. Any match protects every ambiguous historical duplicate.
        for (String reference :
                List.of(
                        "inpayment_row.invoice_number",
                        "customer_credit_invoice.crediting_invoice_number",
                        "customer_order.invoice_number")) {
            String[] parts = reference.split("\\.");
            String scope = parts[0].equals("inpayment_row")
                    ? "inpayment_id IN (SELECT id FROM inpayment WHERE company_id="
                            + "(SELECT id FROM company WHERE legacy_id=?))"
                    : "company_id=(SELECT id FROM company WHERE legacy_id=?)";
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT COUNT(*) FROM "
                                    + parts[0]
                                    + " WHERE "
                                    + parts[1]
                                    + "=? AND "
                                    + scope)) {
                statement.setInt(1, number);
                statement.setInt(2, companyLegacyId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getLong(1) != 0) {
                        throw new SQLException(
                                "Invoice number " + number + " is referenced by " + parts[0]);
                    }
                }
            }
        }
    }

    private void requireAvailableNumber(Kind kind, int companyLegacyId, Integer number, long exceptId)
            throws SQLException {
        if (number == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + kind.table + " WHERE number=? AND id<>? AND company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setLong(2, exceptId);
            statement.setInt(3, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new SQLException("Duplicate " + kind.table + " number " + number);
                }
            }
        }
    }

    private static void validateSnapshot(Kind kind, SSInvoice invoice) throws SQLException {
        Objects.requireNonNull(invoice, "invoice");
        if (invoice.getClass() != (kind == Kind.INVOICE ? SSInvoice.class : SSCreditInvoice.class)) {
            throw new SQLException("Unsupported invoice subtype: " + invoice.getClass().getName());
        }
        validateVoucher(invoice.getStoredVoucher());
    }

    private static void validateVoucher(SSVoucher voucher) throws SQLException {
        if (voucher != null && (voucher.getCorrects() != null || voucher.getCorrectedBy() != null)) {
            throw new SQLException(
                    "Correction-linked voucher snapshots require explicit graph support");
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    private <T> T atomic(SqlOperation<T> operation) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("Normalized invoice writes require a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            T result = operation.run();
            connection.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Marks an invoice as booked and replaces its voucher snapshot, mirroring the legacy
     * {@code updateInvoice} after {@code setEntered()}.
     */
    public void markInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        atomic(() -> { markEntered(Kind.INVOICE, companyLegacyId, number, voucher); return null; });
    }

    /** Marks a credit invoice as booked and replaces its voucher snapshot. */
    public void markCreditInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        atomic(() -> { markEntered(Kind.CREDIT, companyLegacyId, number, voucher); return null; });
    }

    /** Books a previously unbooked invoice by stable identity inside the caller transaction. */
    public void markInvoiceEnteredById(int companyLegacyId, long id, SSVoucher voucher) throws SQLException {
        atomic(() -> { markEnteredById(Kind.INVOICE, companyLegacyId, id, voucher); return null; });
    }

    /** Books a previously unbooked credit by stable identity inside the caller transaction. */
    public void markCreditInvoiceEnteredById(int companyLegacyId, long id, SSVoucher voucher) throws SQLException {
        atomic(() -> { markEnteredById(Kind.CREDIT, companyLegacyId, id, voucher); return null; });
    }

    private void markEnteredById(Kind kind, int companyLegacyId, long id, SSVoucher voucher) throws SQLException {
        validateVoucher(voucher);
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE "
                                + kind.table
                                + " SET entered=true WHERE id=? AND entered=false AND company_id="
                                + "(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Missing, already booked or company-mismatched "
                                + kind.table
                                + " identity "
                                + id);
            }
        }
        for (String table : List.of(kind.voucherRowTable(), kind.voucherTable())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE " + kind.idColumn() + "=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
        if (voucher != null) insertVoucherSnapshot(kind, id, voucher);
    }

    private void markEntered(Kind kind, int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + kind.table + " WHERE number=? AND company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1) {
                    throw new SQLException(
                            "Missing or ambiguous " + kind.table + " number " + number);
                }
            }
        }
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
            insertVoucherSnapshot(kind, id, voucher);
        }
    }

    /** Inserts a complete invoice snapshot and returns its SQL identity without committing. */
    public long addInvoice(int companyLegacyId, SSInvoice invoice) throws SQLException {
        return atomic(() -> addSale(Kind.INVOICE, companyLegacyId, invoice));
    }

    /** Inserts a complete credit snapshot and returns its SQL identity without committing. */
    public long addCreditInvoice(int companyLegacyId, SSCreditInvoice creditInvoice)
            throws SQLException {
        return atomic(() -> addSale(Kind.CREDIT, companyLegacyId, creditInvoice));
    }

    private long addSale(Kind kind, int companyLegacyId, SSInvoice invoice) throws SQLException {
        validateSnapshot(kind, invoice);
        requireUnreferenced(kind, companyLegacyId, invoice.getNumber());
        long id;
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO "
                                + kind.table
                                + " (legacy_id,company_id,number,"
                                + creditColumn
                                + "invoice_date,due_date,customer_number,customer_name,our_contact_person,"
                                + "customer_contact_person,delay_interest,currency_code,payment_term_name,"
                                + "delivery_term_name,delivery_way_name,tax_free,invoice_text,tax_rate_1,"
                                + "tax_rate_2,tax_rate_3,eu_sale_commodity,eu_sale_third_party_commodity,"
                                + "printed,invoice_type,currency_rate,customer_order_number,ocr_number,"
                                + "entered,reminder_count,interest_invoiced,stock_influencing,order_numbers)"
                                + " SELECT ?,id,"
                                + repeat("?,", 28 + (kind == Kind.CREDIT ? 1 : 0))
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

    private int bindHeader(PreparedStatement statement, int i, Kind kind, SSInvoice invoice)
            throws SQLException {
        if (invoice.getNumber() == null) {
            statement.setNull(i++, java.sql.Types.INTEGER);
        } else {
            statement.setInt(i++, invoice.getNumber());
        }
        if (kind == Kind.CREDIT) {
            Integer crediting = ((SSCreditInvoice) invoice).getCreditingNr();
            if (crediting == null) {
                statement.setNull(i++, java.sql.Types.INTEGER);
            } else {
                statement.setInt(i++, crediting);
            }
        }
        setLocalDate(statement, i++, invoice.getLocalDate());
        setLocalDate(statement, i++, invoice.getLocalDueDate());
        statement.setString(i++, invoice.getCustomerNr());
        statement.setString(i++, invoice.getCustomerName());
        statement.setString(i++, invoice.getOurContactPerson());
        statement.setString(i++, invoice.getYourContactPerson());
        statement.setBigDecimal(i++, invoice.getStoredDelayInterest());
        statement.setString(i++, invoice.getCurrency() == null
                ? null : invoice.getCurrency().getName());
        statement.setString(i++, invoice.getPaymentTerm() == null
                ? null : invoice.getPaymentTerm().getName());
        statement.setString(i++, invoice.getDeliveryTerm() == null
                ? null : invoice.getDeliveryTerm().getName());
        statement.setString(i++, invoice.getDeliveryWay() == null
                ? null : invoice.getDeliveryWay().getName());
        statement.setBoolean(i++, invoice.getTaxFree());
        statement.setString(i++, invoice.getText());
        statement.setBigDecimal(i++, invoice.getStoredTaxRates().get(0));
        statement.setBigDecimal(i++, invoice.getStoredTaxRates().get(1));
        statement.setBigDecimal(i++, invoice.getStoredTaxRates().get(2));
        statement.setBoolean(i++, invoice.getEuSaleCommodity());
        statement.setBoolean(i++, invoice.getEuSaleThirdPartCommodity());
        statement.setBoolean(i++, invoice.isPrinted());
        statement.setString(i++, invoice.getStoredType() == null
                ? null : invoice.getStoredType().name());
        statement.setBigDecimal(i++, invoice.getCurrencyRate());
        statement.setString(i++, invoice.getYourOrderNumber());
        statement.setString(i++, invoice.getOCRNumber());
        statement.setBoolean(i++, invoice.isEntered());
        statement.setInt(i++, invoice.getNumReminders());
        statement.setBoolean(i++, invoice.isInterestInvoiced());
        statement.setBoolean(i++, invoice.isStockInfluencing());
        statement.setString(i++, invoice.getOrderNumbers());
        return i;
    }

    private void insertChildren(Kind kind, long id, int companyLegacyId, SSInvoice invoice)
            throws SQLException {
        insertAddress(kind, id, companyLegacyId, "invoice", invoice.getInvoiceAddress());
        insertAddress(kind, id, companyLegacyId, "delivery", invoice.getDeliveryAddress());
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
        int rowNumber = 0;
        for (SSSaleRow row : invoice.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.rowTable() + " (" + kind.idColumn() + ",company_id,"
                            + "row_number,product_number,description,unit_price,quantity,unit_name,"
                            + "discount,tax_code,account_number,project_number,result_unit_number) "
                            + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
                statement.setLong(1, id);
                statement.setInt(2, rowNumber++);
                statement.setString(3, row.getProductNr());
                statement.setString(4, row.getDescription());
                statement.setBigDecimal(5, row.getUnitprice());
                statement.setBigDecimal(6, row.getQuantity());
                statement.setString(7, row.getUnit() == null ? null : row.getUnit().getName());
                statement.setBigDecimal(8, row.getDiscount());
                statement.setString(9, row.getTaxCode() == null ? null : row.getTaxCode().name());
                if (row.getAccountNr() == null) {
                    statement.setNull(10, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(10, row.getAccountNr());
                }
                statement.setString(11, row.getProjectNr());
                statement.setString(12, row.getResultUnitNr());
                statement.setInt(13, companyLegacyId);
                statement.executeUpdate();
            }
        }
        SSVoucher voucher = invoice.getStoredVoucher();
        if (voucher != null) {
            insertVoucherSnapshot(kind, id, voucher);
        }
    }

    // ------------------------------------------------------------------ internals

    private void readAddresses(Kind kind, String idFilter, Map<Long, ? extends SSInvoice> byId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a." + kind.idColumn() + ",a.address_type,a.name,a.address_line_1,"
                        + "a.address_line_2,a.postal_code,a.city,a.country FROM "
                        + kind.addressTable() + " a WHERE a." + kind.idColumn() + " IN ("
                        + idFilter + ") ORDER BY a." + kind.idColumn() + ",a.address_type")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSInvoice invoice = byId.get(result.getLong(1));
                    if (invoice == null) {
                        continue;
                    }
                    SSAddress address = new SSAddress();
                    address.setName(result.getString(3));
                    address.setAddress1(result.getString(4));
                    address.setAddress2(result.getString(5));
                    address.setZipCode(result.getString(6));
                    address.setCity(result.getString(7));
                    address.setCountry(result.getString(8));
                    if ("invoice".equals(result.getString(2))) {
                        invoice.setInvoiceAddress(address);
                    } else {
                        invoice.setDeliveryAddress(address);
                    }
                }
            }
        }
    }

    private void readDefaultAccounts(Kind kind, String idFilter,
            Map<Long, ? extends SSInvoice> byId) throws SQLException {
        Map<Long, Map<SSDefaultAccount, Integer>> accounts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",account_type,account_number FROM "
                        + kind.defaultAccountTable() + " WHERE " + kind.idColumn() + " IN ("
                        + idFilter + ") ORDER BY " + kind.idColumn() + ",account_type")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Integer number = result.getObject(3, Integer.class);
                    accounts.computeIfAbsent(result.getLong(1), key -> new HashMap<>())
                            .put(SSDefaultAccount.valueOf(result.getString(2)), number);
                }
            }
        }
        accounts.forEach((id, map) -> {
            SSInvoice invoice = byId.get(id);
            if (invoice != null) {
                invoice.setDefaultAccounts(map);
            }
        });
    }

    private void readRows(Kind kind, String idFilter, Map<Long, ? extends SSInvoice> byId,
            Map<String, SSUnit> units) throws SQLException {
        Map<Long, List<SSSaleRow>> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",product_number,description,unit_price,quantity,"
                        + "unit_name,discount,tax_code,account_number,project_number,"
                        + "result_unit_number FROM " + kind.rowTable() + " WHERE "
                        + kind.idColumn() + " IN (" + idFilter + ") ORDER BY " + kind.idColumn()
                        + ",row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSaleRow row = new SSSaleRow();
                    row.setProductNr(result.getString(2));
                    row.setDescription(result.getString(3));
                    row.setUnitprice(result.getBigDecimal(4));
                    row.setQuantity(result.getBigDecimal(5));
                    row.setUnit(units.get(result.getString(6)));
                    row.setDiscount(result.getBigDecimal(7));
                    String taxCode = result.getString(8);
                    if (taxCode != null) {
                        row.setTaxCode(SSTaxCode.valueOf(taxCode));
                    }
                    row.setAccountNr(result.getObject(9, Integer.class));
                    row.setProjectNr(result.getString(10));
                    row.setResultUnitNr(result.getString(11));
                    rows.computeIfAbsent(result.getLong(1), key -> new ArrayList<>()).add(row);
                }
            }
        }
        rows.forEach((id, list) -> {
            SSInvoice invoice = byId.get(id);
            if (invoice != null) {
                invoice.setRows(list);
            }
        });
    }

    private void readVoucherSnapshots(Kind kind, String idFilter,
            Map<Long, ? extends SSInvoice> byId) throws SQLException {
        Map<Long, SSVoucher> vouchers = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",number,voucher_date,description FROM "
                        + kind.voucherTable() + " WHERE " + kind.idColumn() + " IN (" + idFilter
                        + ") ORDER BY " + kind.idColumn())) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = new SSVoucher(result.getInt(2),
                            result.getObject(3, LocalDate.class));
                    voucher.setDescription(result.getString(4));
                    vouchers.put(result.getLong(1), voucher);
                }
            }
        }
        if (vouchers.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + kind.idColumn() + ",account_number,project_number,result_unit_number,"
                        + "debit,credit,edited_at,edited_signature,crossed,added FROM "
                        + kind.voucherRowTable() + " WHERE " + kind.idColumn() + " IN (" + idFilter
                        + ") ORDER BY " + kind.idColumn() + ",row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = vouchers.get(result.getLong(1));
                    if (voucher == null) {
                        continue;
                    }
                    SSVoucherRow row = voucherRowFrom(result, 2);
                    voucher.getRows().add(row);
                }
            }
        }
        vouchers.forEach((id, voucher) -> {
            SSInvoice invoice = byId.get(id);
            if (invoice != null) {
                invoice.setVoucher(voucher);
            }
        });
    }

    /** Builds a voucher row from a result set positioned at account_number. */
    static SSVoucherRow voucherRowFrom(ResultSet result, int offset) throws SQLException {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(result.getObject(offset, Integer.class));
        row.setProjectNr(result.getString(offset + 1));
        row.setResultUnitNr(result.getString(offset + 2));
        row.setDebet(result.getBigDecimal(offset + 3));
        row.setCredit(result.getBigDecimal(offset + 4));
        OffsetDateTime edited = result.getObject(offset + 5, OffsetDateTime.class);
        if (edited != null) {
            row.setLocalEditedDate(edited.atZoneSameInstant(
                    LegacySwedishTimeResolver.LEGACY_ZONE).toLocalDateTime());
        }
        row.setEditedSignature(result.getString(offset + 6));
        row.setCrossed(result.getBoolean(offset + 7));
        row.setAdded(result.getBoolean(offset + 8));
        return row;
    }

    /** Binds a voucher row's fields starting at the given parameter index, returning the next. */
    static int bindVoucherRow(PreparedStatement statement, int index, SSVoucherRow row)
            throws SQLException {
        if (row.getAccountNr() == null) {
            statement.setNull(index++, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index++, row.getAccountNr());
        }
        statement.setString(index++, row.getProjectNr());
        statement.setString(index++, row.getResultUnitNr());
        statement.setBigDecimal(index++, row.getDebet());
        statement.setBigDecimal(index++, row.getCredit());
        java.time.LocalDateTime edited = row.getLocalEditedDate();
        statement.setObject(index++, edited == null ? null
                : OffsetDateTime.ofInstant(LegacySwedishTimeResolver.resolve(edited).instant(),
                        java.time.ZoneOffset.UTC));
        statement.setString(index++, row.getEditedSignature());
        statement.setBoolean(index++, row.isCrossed());
        statement.setBoolean(index++, row.isAdded());
        return index;
    }

    private void insertAddress(Kind kind, long saleId, int companyLegacyId, String type,
            SSAddress address) throws SQLException {
        if (address == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + kind.addressTable() + " (" + kind.idColumn() + ",company_id,"
                        + "address_type,name,address_line_1,address_line_2,postal_code,city,"
                        + "country) SELECT ?,id,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            statement.setLong(1, saleId);
            statement.setString(2, type);
            int i = 3;
            statement.setString(i++, address.getName());
            statement.setString(i++, address.getAddress1());
            statement.setString(i++, address.getAddress2());
            statement.setString(i++, address.getZipCode());
            statement.setString(i++, address.getCity());
            statement.setString(i++, address.getCountry());
            statement.setInt(i, companyLegacyId);
            statement.executeUpdate();
        }
    }

    private void insertVoucherSnapshot(Kind kind, long saleId, SSVoucher voucher)
            throws SQLException {
        validateVoucher(voucher);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + kind.voucherTable() + " (" + kind.idColumn()
                        + ",number,voucher_date,description) VALUES (?,?,?,?)")) {
            statement.setLong(1, saleId);
            statement.setInt(2, voucher.getNumber());
            setLocalDate(statement, 3, voucher.getLocalDate());
            statement.setString(4, voucher.getDescription());
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + kind.voucherRowTable() + " (" + kind.idColumn()
                            + ",row_number,account_number,project_number,result_unit_number,"
                            + "debit,credit,edited_at,edited_signature,crossed,added) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, saleId);
                statement.setInt(2, rowNumber++);
                bindVoucherRow(statement, 3, row);
                statement.executeUpdate();
            }
        }
    }

    private int nextLegacyId(Kind kind) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0) FROM " + kind.table)) {
            result.next();
            long maximum = result.getLong(1);
            if (maximum >= Integer.MAX_VALUE) {
                throw new SQLException("Legacy identity exhausted for " + kind.table);
            }
            return (int) maximum + 1;
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
