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
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SSDB-shaped reads/writes over the normalized customer invoice and credit invoice tables,
 * including addresses, default accounts, ordered rows, and the booked voucher snapshot.
 */
public final class NormalizedInvoiceStore {

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
        return readSales(Kind.INVOICE, companyLegacyId, null);
    }

    /** Reads all credit invoices of a company. */
    public List<SSCreditInvoice> getCreditInvoices(int companyLegacyId) throws SQLException {
        return readSales(Kind.CREDIT, companyLegacyId, null);
    }

    /** Reads the credit invoices crediting a given invoice number. */
    public List<SSCreditInvoice> getCreditInvoices(int companyLegacyId, int creditingNumber)
            throws SQLException {
        return readSales(Kind.CREDIT, companyLegacyId, creditingNumber);
    }

    private <T extends SSInvoice> List<T> readSales(Kind kind, int companyLegacyId,
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
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        String creditFilter = creditingNumber == null ? "" : " AND crediting_invoice_number=?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,number," + creditColumn + "invoice_date,due_date,customer_number,"
                        + "customer_name,our_contact_person,customer_contact_person,delay_interest,"
                        + "currency_code,payment_term_name,delivery_term_name,delivery_way_name,"
                        + "tax_free,invoice_text,tax_rate_1,tax_rate_2,tax_rate_3,"
                        + "eu_sale_commodity,eu_sale_third_party_commodity,printed,invoice_type,"
                        + "currency_rate,customer_order_number,ocr_number,entered,reminder_count,"
                        + "interest_invoiced,stock_influencing,order_numbers FROM " + kind.table
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
                    T sale = (T) (kind == Kind.CREDIT ? new SSCreditInvoice() : new SSInvoice());
                    int column = 1;
                    long id = result.getLong(column++);
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
                    sale.setTaxRate1(result.getBigDecimal(column++));
                    sale.setTaxRate2(result.getBigDecimal(column++));
                    sale.setTaxRate3(result.getBigDecimal(column++));
                    sale.setEuSaleCommodity(result.getBoolean(column++));
                    sale.setEuSaleYhirdPartCommodity(result.getBoolean(column++));
                    sale.setPrinted(result.getBoolean(column++));
                    String type = result.getString(column++);
                    if (type != null) {
                        sale.setType(SSInvoiceType.valueOf(type));
                    }
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
        return new ArrayList<>(byId.values());
    }

    // ------------------------------------------------------------------ writes

    // ------------------------------------------------------------------ writes

    /**
     * Marks an invoice as booked and replaces its voucher snapshot, mirroring the legacy
     * {@code updateInvoice} after {@code setEntered()}.
     */
    public void markInvoiceEntered(int companyLegacyId, int number, SSVoucher voucher)
            throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE customer_invoice SET entered=true WHERE number=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No invoice " + number + " for company "
                        + companyLegacyId);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM customer_invoice WHERE number=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, number);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                id = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM customer_invoice_voucher_row WHERE invoice_id=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM customer_invoice_voucher WHERE invoice_id=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        if (voucher != null) {
            insertVoucherSnapshot(Kind.INVOICE, id, voucher);
        }
    }

    public void addInvoice(int companyLegacyId, SSInvoice invoice) throws SQLException {
        addSale(Kind.INVOICE, companyLegacyId, invoice);
    }

    public void addCreditInvoice(int companyLegacyId, SSCreditInvoice creditInvoice)
            throws SQLException {
        addSale(Kind.CREDIT, companyLegacyId, creditInvoice);
    }

    private void addSale(Kind kind, int companyLegacyId, SSInvoice invoice) throws SQLException {
        long id;
        String creditColumn = kind == Kind.CREDIT ? "crediting_invoice_number," : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + kind.table + " (legacy_id,company_id,number," + creditColumn
                        + "invoice_date,due_date,customer_number,customer_name,our_contact_person,"
                        + "customer_contact_person,delay_interest,currency_code,payment_term_name,"
                        + "delivery_term_name,delivery_way_name,tax_free,invoice_text,tax_rate_1,"
                        + "tax_rate_2,tax_rate_3,eu_sale_commodity,eu_sale_third_party_commodity,"
                        + "printed,invoice_type,currency_rate,customer_order_number,ocr_number,"
                        + "entered,reminder_count,interest_invoiced,stock_influencing,order_numbers)"
                        + " SELECT ?,id," + repeat("?,", 28 + (kind == Kind.CREDIT ? 1 : 0))
                        + "? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId(kind));
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
                    int number = result.getInt(3);
                    accounts.computeIfAbsent(result.getLong(1), key -> new HashMap<>())
                            .put(SSDefaultAccount.valueOf(result.getString(2)),
                                    result.wasNull() ? null : number);
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
                    SSVoucher voucher = new SSVoucher(result.getInt(2));
                    voucher.setLocalDate(result.getObject(3, LocalDate.class));
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
