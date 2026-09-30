package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
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
 * SSDB-shaped reads/writes over normalized customer invoice tables, including addresses,
 * default accounts, ordered rows, and the booked voucher snapshot.
 */
public final class NormalizedInvoiceStore {
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
        int highest = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(number),0) FROM customer_invoice "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                highest = result.getInt(1);
            }
        }
        int counter = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counter_value FROM company_auto_increment WHERE counter_name='invoice' "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
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
        for (se.swedsoft.bookkeeping.data.SSCreditInvoice creditInvoice
                : readCreditInvoices(companyLegacyId, invoiceNumber)) {
            sum = sum.add(se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                    creditInvoice, roundingOff));
        }
        return sum;
    }

    /** Reads the credit invoices crediting a given invoice, with the fields total math needs. */
    private List<se.swedsoft.bookkeeping.data.SSCreditInvoice> readCreditInvoices(
            int companyLegacyId, int creditingInvoiceNumber) throws SQLException {
        Map<Long, se.swedsoft.bookkeeping.data.SSCreditInvoice> byId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,tax_free,tax_rate_1,tax_rate_2,tax_rate_3 FROM customer_credit_invoice "
                        + "WHERE crediting_invoice_number=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, creditingInvoiceNumber);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    se.swedsoft.bookkeeping.data.SSCreditInvoice creditInvoice =
                            new se.swedsoft.bookkeeping.data.SSCreditInvoice();
                    creditInvoice.setTaxFree(result.getBoolean(2));
                    creditInvoice.setTaxRate1(result.getBigDecimal(3));
                    creditInvoice.setTaxRate2(result.getBigDecimal(4));
                    creditInvoice.setTaxRate3(result.getBigDecimal(5));
                    byId.put(result.getLong(1), creditInvoice);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SSSaleRow>> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.credit_invoice_id,r.unit_price,r.quantity,r.discount,r.tax_code "
                        + "FROM customer_credit_invoice_row r JOIN customer_credit_invoice c "
                        + "ON c.id=r.credit_invoice_id WHERE c.crediting_invoice_number=? "
                        + "AND c.company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY r.credit_invoice_id,r.row_number")) {
            statement.setInt(1, creditingInvoiceNumber);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSaleRow row = new SSSaleRow();
                    row.setUnitprice(result.getBigDecimal(2));
                    row.setQuantity(result.getBigDecimal(3));
                    row.setDiscount(result.getBigDecimal(4));
                    String taxCode = result.getString(5);
                    if (taxCode != null) {
                        row.setTaxCode(SSTaxCode.valueOf(taxCode));
                    }
                    rows.computeIfAbsent(result.getLong(1), key -> new ArrayList<>()).add(row);
                }
            }
        }
        rows.forEach((id, list) -> byId.get(id).setRows(list));
        return new ArrayList<>(byId.values());
    }

    public List<SSInvoice> getInvoices(int companyLegacyId) throws SQLException {
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

        Map<Long, SSInvoice> byId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,number,invoice_date,due_date,customer_number,customer_name,"
                        + "our_contact_person,customer_contact_person,delay_interest,currency_code,"
                        + "payment_term_name,delivery_term_name,delivery_way_name,tax_free,"
                        + "invoice_text,tax_rate_1,tax_rate_2,tax_rate_3,eu_sale_commodity,"
                        + "eu_sale_third_party_commodity,printed,invoice_type,currency_rate,"
                        + "customer_order_number,ocr_number,entered,reminder_count,"
                        + "interest_invoiced,stock_influencing,order_numbers "
                        + "FROM customer_invoice "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSInvoice invoice = new SSInvoice();
                    long id = result.getLong(1);
                    int number = result.getInt(2);
                    invoice.setNumber(result.wasNull() ? null : number);
                    invoice.setLocalDate(result.getObject(3, LocalDate.class));
                    invoice.setLocalDueDate(result.getObject(4, LocalDate.class));
                    invoice.setCustomerNr(result.getString(5));
                    invoice.setCustomerName(result.getString(6));
                    invoice.setOurContactPerson(result.getString(7));
                    invoice.setYourContactPerson(result.getString(8));
                    invoice.setDelayInterest(result.getBigDecimal(9));
                    invoice.setCurrency(currencies.get(result.getString(10)));
                    invoice.setPaymentTerm(paymentTerms.get(result.getString(11)));
                    invoice.setDeliveryTerm(deliveryTerms.get(result.getString(12)));
                    invoice.setDeliveryWay(deliveryWays.get(result.getString(13)));
                    invoice.setTaxFree(result.getBoolean(14));
                    invoice.setText(result.getString(15));
                    invoice.setTaxRate1(result.getBigDecimal(16));
                    invoice.setTaxRate2(result.getBigDecimal(17));
                    invoice.setTaxRate3(result.getBigDecimal(18));
                    invoice.setEuSaleCommodity(result.getBoolean(19));
                    invoice.setEuSaleYhirdPartCommodity(result.getBoolean(20));
                    invoice.setPrinted(result.getBoolean(21));
                    String type = result.getString(22);
                    if (type != null) {
                        invoice.setType(SSInvoiceType.valueOf(type));
                    }
                    invoice.setCurrencyRate(result.getBigDecimal(23));
                    invoice.setYourOrderNumber(result.getString(24));
                    invoice.setOCRNumber(result.getString(25));
                    invoice.setEntered(result.getBoolean(26));
                    invoice.setNumRemainders(result.getInt(27));
                    invoice.setInterestInvoiced(result.getBoolean(28));
                    invoice.setStockInfluencing(result.getBoolean(29));
                    invoice.setOrderNumbers(result.getString(30));
                    byId.put(id, invoice);
                }
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        String invoiceIds = invoiceIdFilter(companyLegacyId);
        readAddresses(invoiceIds, companyLegacyId, byId);
        readDefaultAccounts(invoiceIds, companyLegacyId, byId);
        readRows(invoiceIds, companyLegacyId, byId, units);
        readVoucherSnapshots(invoiceIds, companyLegacyId, byId);
        return new ArrayList<>(byId.values());
    }

    public void addInvoice(int companyLegacyId, SSInvoice invoice) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer_invoice (legacy_id,company_id,number,invoice_date,due_date,"
                        + "customer_number,customer_name,our_contact_person,customer_contact_person,"
                        + "delay_interest,currency_code,payment_term_name,delivery_term_name,"
                        + "delivery_way_name,tax_free,invoice_text,tax_rate_1,tax_rate_2,tax_rate_3,"
                        + "eu_sale_commodity,eu_sale_third_party_commodity,printed,invoice_type,"
                        + "currency_rate,customer_order_number,ocr_number,entered,reminder_count,"
                        + "interest_invoiced,stock_influencing,order_numbers) "
                        + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                        + " FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId());
            if (invoice.getNumber() == null) {
                statement.setNull(i++, java.sql.Types.INTEGER);
            } else {
                statement.setInt(i++, invoice.getNumber());
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
                    throw new SQLException("No generated key returned for invoice");
                }
                id = keys.getLong(1);
            }
        }
        insertAddress(id, companyLegacyId, "invoice", invoice.getInvoiceAddress());
        insertAddress(id, companyLegacyId, "delivery", invoice.getDeliveryAddress());
        for (Map.Entry<SSDefaultAccount, Integer> account
                : invoice.getDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO customer_invoice_default_account (invoice_id,account_type,"
                            + "account_number) VALUES (?,?,?)")) {
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
                    "INSERT INTO customer_invoice_row (invoice_id,company_id,row_number,"
                            + "product_number,description,unit_price,quantity,unit_name,discount,"
                            + "tax_code,account_number,project_number,result_unit_number) "
                            + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,? "
                            + "FROM company WHERE legacy_id=?")) {
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
            insertVoucherSnapshot(id, voucher);
        }
    }

    // ------------------------------------------------------------------ internals

    private static String invoiceIdFilter(int companyLegacyId) {
        return "SELECT id FROM customer_invoice WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=" + companyLegacyId + ")";
    }

    private void readAddresses(String invoiceIds, int companyLegacyId, Map<Long, SSInvoice> byId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.invoice_id,a.address_type,a.name,a.address_line_1,a.address_line_2,"
                        + "a.postal_code,a.city,a.country FROM customer_invoice_address a "
                        + "WHERE a.invoice_id IN (" + invoiceIds + ") "
                        + "ORDER BY a.invoice_id,a.address_type")) {
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

    private void readDefaultAccounts(String invoiceIds, int companyLegacyId,
            Map<Long, SSInvoice> byId) throws SQLException {
        Map<Long, Map<SSDefaultAccount, Integer>> accounts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invoice_id,account_type,account_number "
                        + "FROM customer_invoice_default_account WHERE invoice_id IN ("
                        + invoiceIds + ") ORDER BY invoice_id,account_type")) {
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

    private void readRows(String invoiceIds, int companyLegacyId, Map<Long, SSInvoice> byId,
            Map<String, SSUnit> units) throws SQLException {
        Map<Long, List<SSSaleRow>> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invoice_id,product_number,description,unit_price,quantity,unit_name,"
                        + "discount,tax_code,account_number,project_number,result_unit_number "
                        + "FROM customer_invoice_row WHERE invoice_id IN (" + invoiceIds + ") "
                        + "ORDER BY invoice_id,row_number")) {
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

    private void readVoucherSnapshots(String invoiceIds, int companyLegacyId,
            Map<Long, SSInvoice> byId) throws SQLException {
        Map<Long, SSVoucher> vouchers = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invoice_id,number,voucher_date,description FROM customer_invoice_voucher "
                        + "WHERE invoice_id IN (" + invoiceIds + ") ORDER BY invoice_id")) {
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
                "SELECT invoice_id,account_number,project_number,result_unit_number,debit,credit,"
                        + "edited_at,edited_signature,crossed,added "
                        + "FROM customer_invoice_voucher_row WHERE invoice_id IN (" + invoiceIds
                        + ") ORDER BY invoice_id,row_number")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = vouchers.get(result.getLong(1));
                    if (voucher == null) {
                        continue;
                    }
                    SSVoucherRow row = new SSVoucherRow();
                    row.setAccountNr(result.getObject(2, Integer.class));
                    row.setProjectNr(result.getString(3));
                    row.setResultUnitNr(result.getString(4));
                    row.setDebet(result.getBigDecimal(5));
                    row.setCredit(result.getBigDecimal(6));
                    OffsetDateTime edited = result.getObject(7, OffsetDateTime.class);
                    if (edited != null) {
                        row.setLocalEditedDate(edited.atZoneSameInstant(
                                LegacySwedishTimeResolver.LEGACY_ZONE).toLocalDateTime());
                    }
                    row.setEditedSignature(result.getString(8));
                    row.setCrossed(result.getBoolean(9));
                    row.setAdded(result.getBoolean(10));
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

    private void insertAddress(long invoiceId, int companyLegacyId, String type, SSAddress address)
            throws SQLException {
        if (address == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer_invoice_address (invoice_id,company_id,address_type,name,"
                        + "address_line_1,address_line_2,postal_code,city,country) "
                        + "SELECT ?,id,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            statement.setLong(1, invoiceId);
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

    private void insertVoucherSnapshot(long invoiceId, SSVoucher voucher) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer_invoice_voucher (invoice_id,number,voucher_date,description) "
                        + "VALUES (?,?,?,?)")) {
            statement.setLong(1, invoiceId);
            statement.setInt(2, voucher.getNumber());
            setLocalDate(statement, 3, voucher.getLocalDate());
            statement.setString(4, voucher.getDescription());
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO customer_invoice_voucher_row (invoice_id,row_number,"
                            + "account_number,project_number,result_unit_number,debit,credit,"
                            + "edited_at,edited_signature,crossed,added) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, invoiceId);
                statement.setInt(2, rowNumber++);
                if (row.getAccountNr() == null) {
                    statement.setNull(3, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(3, row.getAccountNr());
                }
                statement.setString(4, row.getProjectNr());
                statement.setString(5, row.getResultUnitNr());
                statement.setBigDecimal(6, row.getDebet());
                statement.setBigDecimal(7, row.getCredit());
                java.time.LocalDateTime edited = row.getLocalEditedDate();
                statement.setObject(8, edited == null ? null
                        : OffsetDateTime.ofInstant(
                                LegacySwedishTimeResolver.resolve(edited).instant(),
                                java.time.ZoneOffset.UTC));
                statement.setString(9, row.getEditedSignature());
                statement.setBoolean(10, row.isCrossed());
                statement.setBoolean(11, row.isAdded());
                statement.executeUpdate();
            }
        }
    }

    private int nextLegacyId() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0)+1 FROM customer_invoice")) {
            result.next();
            return result.getInt(1);
        }
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
