package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Converts customer orders and tenders: full sale snapshots with ordered rows, addresses and accounts. */
public final class SaleDocumentStagingConverter {
    public enum Kind {ORDER, TENDER}

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy));
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized), source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Sale-document staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.headers.size(), source.addresses.size(), source.accounts.size(), source.rows.size());
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot(); readKind(c, s, Kind.ORDER); readKind(c, s, Kind.TENDER); s.sort(); return s;
    }

    private static void readKind(Connection c, Snapshot s, Kind kind) throws SQLException {
        String table = kind == Kind.ORDER ? "tbl_order" : "tbl_tender";
        String column = kind == Kind.ORDER ? "iorder" : "tender";
        query(c, "SELECT id,companyid,number," + column + " FROM " + table + " ORDER BY companyid,id", r -> {
            int id = r.getInt(1), company = r.getInt(2); Integer key = nullableInteger(r, 3);
            Header h;
            if (kind == Kind.ORDER) {
                SSOrder x = (SSOrder) r.getObject(4);
                if (!Objects.equals(key, x.getNumber())) throw new SQLException("Order key/object mismatch for legacy id " + id);
                h = new Header(id, company, kind, key, x.getLocalDate(), null, x.getCustomerNr(), x.getCustomerName(), x.getOurContactPerson(), x.getYourContactPerson(), x.getStoredDelayInterest(), x.getCurrency() == null ? null : x.getCurrency().getName(), x.getCurrencyRate(), name(x.getPaymentTerm()), name(x.getDeliveryTerm()), name(x.getDeliveryWay()), x.getTaxFree(), x.getText(), tax(x, 0), tax(x, 1), tax(x, 2), x.getEuSaleCommodity(), x.getEuSaleThirdPartCommodity(), x.isPrinted(), x.getYourOrderNumber(), x.getEstimatedDelivery(), x.getInvoiceNr(), x.getPeriodicInvoiceNr(), x.getPurchaseOrderNr(), x.getHideUnitprice(), null);
                addRows(s, id, company, kind, x.getRows()); addAccounts(s, id, company, kind, x.getDefaultAccounts());
                addAddress(s, id, company, kind, "invoice", x.getInvoiceAddress()); addAddress(s, id, company, kind, "delivery", x.getDeliveryAddress());
            } else {
                SSTender x = (SSTender) r.getObject(4);
                if (!Objects.equals(key, x.getNumber())) throw new SQLException("Tender key/object mismatch for legacy id " + id);
                h = new Header(id, company, kind, key, x.getLocalDate(), x.getLocalExpires(), x.getCustomerNr(), x.getCustomerName(), x.getOurContactPerson(), x.getYourContactPerson(), x.getStoredDelayInterest(), x.getCurrency() == null ? null : x.getCurrency().getName(), x.getCurrencyRate(), name(x.getPaymentTerm()), name(x.getDeliveryTerm()), name(x.getDeliveryWay()), x.getTaxFree(), x.getText(), tax(x, 0), tax(x, 1), tax(x, 2), x.getEuSaleCommodity(), x.getEuSaleThirdPartCommodity(), x.isPrinted(), null, null, null, null, null, false, x.getOrderNr());
                addRows(s, id, company, kind, x.getRows()); addAccounts(s, id, company, kind, x.getDefaultAccounts());
                addAddress(s, id, company, kind, "invoice", x.getInvoiceAddress()); addAddress(s, id, company, kind, "delivery", x.getDeliveryAddress());
            }
            s.headers.add(h);
        });
    }

    private static BigDecimal tax(se.swedsoft.bookkeeping.data.base.SSSale x, int index) { List<BigDecimal> t = x.getStoredTaxRates(); return t.size() > index ? t.get(index) : null; }

    private static void addRows(Snapshot s, int id, int company, Kind kind, List<SSSaleRow> rows) {
        int n = 0;
        for (SSSaleRow row : rows) { row.fixResultUnitAndProject(); s.rows.add(new SaleLine(id, company, kind, n++, row.getProductNr(), row.getDescription(), row.getUnitprice(), row.getQuantity(), row.getUnit() == null ? null : row.getUnit().getName(), row.getDiscount(), row.getTaxCode() == null ? null : row.getTaxCode().name(), row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr())); }
    }

    private static void addAccounts(Snapshot s, int id, int company, Kind kind, Map<SSDefaultAccount, Integer> accounts) {
        if (accounts != null) accounts.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).forEach(e -> s.accounts.add(new Account(id, kind, e.getKey().name(), e.getValue())));
    }

    private static void addAddress(Snapshot s, int id, int company, Kind kind, String type, SSAddress a) {
        if (a != null) s.addresses.add(new Address(id, company, kind, type, a.getName(), a.getAddress1(), a.getAddress2(), a.getZipCode(), a.getCity(), a.getCountry()));
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Long, Long> ids = new HashMap<>();
        for (Header r : s.headers) {
            String t = table(r.kind);
            boolean order = r.kind == Kind.ORDER;
            String sql = order
                ? "INSERT INTO customer_order (legacy_id,company_id,number,sale_date,customer_number,customer_name,our_contact_person,customer_contact_person,delay_interest,currency_code,currency_rate,payment_term_name,delivery_term_name,delivery_way_name,tax_free,sale_text,tax_rate_1,tax_rate_2,tax_rate_3,eu_sale_commodity,eu_sale_third_party_commodity,printed,customer_order_number,estimated_delivery,invoice_number,periodic_invoice_number,purchase_order_number,hide_unit_price) SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?"
                : "INSERT INTO tender (legacy_id,company_id,number,sale_date,expires_on,customer_number,customer_name,our_contact_person,customer_contact_person,delay_interest,currency_code,currency_rate,payment_term_name,delivery_term_name,delivery_way_name,tax_free,sale_text,tax_rate_1,tax_rate_2,tax_rate_3,eu_sale_commodity,eu_sale_third_party_commodity,printed,order_number) SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?";
            try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i = 1; p.setInt(i++, r.legacyId); setInteger(p, i++, r.number); p.setObject(i++, r.saleDate);
                if (!order) p.setObject(i++, r.expiresOn);
                p.setString(i++, r.customerNumber); p.setString(i++, r.customerName); p.setString(i++, r.ourContact); p.setString(i++, r.customerContact); p.setBigDecimal(i++, r.delayInterest); p.setString(i++, r.currency); p.setBigDecimal(i++, r.currencyRate); p.setString(i++, r.paymentTerm); p.setString(i++, r.deliveryTerm); p.setString(i++, r.deliveryWay); p.setBoolean(i++, r.taxFree); p.setString(i++, r.text); p.setBigDecimal(i++, r.tax1); p.setBigDecimal(i++, r.tax2); p.setBigDecimal(i++, r.tax3); p.setBoolean(i++, r.euCommodity); p.setBoolean(i++, r.euThird); p.setBoolean(i++, r.printed);
                if (order) { p.setString(i++, r.orderNumber); p.setString(i++, r.estimatedDelivery); setInteger(p, i++, r.invoiceNumber); setInteger(p, i++, r.periodicInvoiceNumber); setInteger(p, i++, r.purchaseOrderNumber); p.setBoolean(i, r.hideUnitPrice); }
                else { if (r.tenderOrderNumber == null) p.setNull(i, Types.INTEGER); else p.setInt(i, r.tenderOrderNumber); }
                p.setInt(++i, r.companyLegacyId);
                if (p.executeUpdate() != 1) throw new SQLException("Missing company " + r.companyLegacyId + " for " + r.kind + " " + r.legacyId);
                ids.put(key(r.legacyId, r.kind), key(p));
            }
        }
        for (SaleLine r : s.rows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setLong(2, companyId(c, r.companyLegacyId)); p.setInt(3, r.row); p.setString(4, r.product); p.setString(5, r.description); p.setBigDecimal(6, r.unitPrice); p.setBigDecimal(7, r.quantity); p.setString(8, r.unit); p.setBigDecimal(9, r.discount); p.setString(10, r.taxCode); setInteger(p, 11, r.account); p.setString(12, r.project); p.setString(13, r.resultUnit); p.executeUpdate(); } }
        for (Address r : s.addresses) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_address VALUES (?,?,?,?,?,?,?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setLong(2, companyId(c, r.companyLegacyId)); p.setString(3, r.type); int i = 4; for (String v : r.values()) p.setString(i++, v); p.executeUpdate(); } }
        for (Account r : s.accounts) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_default_account VALUES (?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setString(2, r.type); setInteger(p, 3, r.number); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        for (Kind kind : Kind.values()) {
            String t = table(kind); boolean order = kind == Kind.ORDER;
            String extra = order ? "x.customer_order_number,x.estimated_delivery,x.invoice_number,x.periodic_invoice_number,x.purchase_order_number,x.hide_unit_price" : "x.order_number";
            query(c, "SELECT x.legacy_id,co.legacy_id,x.number,x.sale_date," + (order ? "" : "x.expires_on,") + "x.customer_number,x.customer_name,x.our_contact_person,x.customer_contact_person,x.delay_interest,x.currency_code,x.currency_rate,x.payment_term_name,x.delivery_term_name,x.delivery_way_name,x.tax_free,x.sale_text,x.tax_rate_1,x.tax_rate_2,x.tax_rate_3,x.eu_sale_commodity,x.eu_sale_third_party_commodity,x.printed," + extra + " FROM " + t + " x JOIN company co ON co.id=x.company_id ORDER BY co.legacy_id,x.legacy_id", r -> {
                int i = 2; int company = r.getInt(i++); Integer number = nullableInteger(r, i++); java.time.LocalDate date = r.getObject(i++, java.time.LocalDate.class); java.time.LocalDate expires = order ? null : r.getObject(i++, java.time.LocalDate.class);
                Header h = new Header(r.getInt(1), company, kind, number, date, expires, r.getString(i++), r.getString(i++), r.getString(i++), r.getString(i++), r.getBigDecimal(i++), r.getString(i++), r.getBigDecimal(i++), r.getString(i++), r.getString(i++), r.getString(i++), r.getBoolean(i++), r.getString(i++), r.getBigDecimal(i++), r.getBigDecimal(i++), r.getBigDecimal(i++), r.getBoolean(i++), r.getBoolean(i++), r.getBoolean(i++), order ? r.getString(i++) : null, order ? r.getString(i++) : null, order ? nullableInteger(r, i++) : null, order ? nullableInteger(r, i++) : null, order ? nullableInteger(r, i++) : null, order ? r.getBoolean(i++) : false, order ? null : nullableInteger(r, i));
                s.headers.add(h);
            });
            query(c, "SELECT p.legacy_id,co.legacy_id,x.row_number,x.product_number,x.description,x.unit_price,x.quantity,x.unit_name,x.discount,x.tax_code,x.account_number,x.project_number,x.result_unit_number FROM " + t + "_row x JOIN " + t + " p ON p.id=x." + parentColumn(kind) + " JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,x.row_number", r -> s.rows.add(new SaleLine(r.getInt(1), r.getInt(2), kind, r.getInt(3), r.getString(4), r.getString(5), r.getBigDecimal(6), r.getBigDecimal(7), r.getString(8), r.getBigDecimal(9), r.getString(10), nullableInteger(r, 11), r.getString(12), r.getString(13))));
            query(c, "SELECT p.legacy_id,co.legacy_id,a.address_type,a.name,a.address_line_1,a.address_line_2,a.postal_code,a.city,a.country FROM " + t + "_address a JOIN " + t + " p ON p.id=a." + parentColumn(kind) + " JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,a.address_type", r -> s.addresses.add(new Address(r.getInt(1), r.getInt(2), kind, r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9))));
            query(c, "SELECT p.legacy_id,co.legacy_id,a.account_type,a.account_number FROM " + t + "_default_account a JOIN " + t + " p ON p.id=a." + parentColumn(kind) + " JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,a.account_type", r -> s.accounts.add(new Account(r.getInt(1), kind, r.getString(3), nullableInteger(r, 4))));
        }
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var h = b.domain("sale-documents"); for (Header r : s.headers) h.startRecord(r.kind + "/" + r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.number).writeDate(r.saleDate).writeDate(r.expiresOn).writeString(r.customerNumber).writeString(r.customerName).writeString(r.ourContact).writeString(r.customerContact).writeDecimal(canon(r.delayInterest)).writeString(r.currency).writeDecimal(canon(r.currencyRate)).writeString(r.paymentTerm).writeString(r.deliveryTerm).writeString(r.deliveryWay).writeBoolean(r.taxFree).writeString(r.text).writeDecimal(canon(r.tax1)).writeDecimal(canon(r.tax2)).writeDecimal(canon(r.tax3)).writeBoolean(r.euCommodity).writeBoolean(r.euThird).writeBoolean(r.printed).writeString(r.orderNumber).writeString(r.estimatedDelivery).writeInteger(r.invoiceNumber).writeInteger(r.periodicInvoiceNumber).writeInteger(r.purchaseOrderNumber).writeBoolean(r.hideUnitPrice).writeInteger(r.tenderOrderNumber); h.finish();
        var a = b.domain("sale-document-addresses"); for (Address r : s.addresses) { var w = a.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.type).writeInteger(r.headerLegacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeString(r.type); for (String v : r.values()) w.writeString(v); } a.finish();
        var ac = b.domain("sale-document-default-accounts"); for (Account r : s.accounts) ac.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.type).writeInteger(r.headerLegacyId).writeString(r.kind.name()).writeString(r.type).writeInteger(r.number); ac.finish();
        var rw = b.domain("sale-document-rows"); for (SaleLine r : s.rows) rw.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.row).writeInteger(r.headerLegacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.row).writeString(r.product).writeString(r.description).writeDecimal(canon(r.unitPrice)).writeDecimal(canon(r.quantity)).writeString(r.unit).writeDecimal(canon(r.discount)).writeString(r.taxCode).writeInteger(r.account).writeString(r.project).writeString(r.resultUnit); rw.finish();
        return b.finish();
    }

    private static String table(Kind k) { return k == Kind.ORDER ? "customer_order" : "tender"; }
    private static String parentColumn(Kind k) { return k == Kind.ORDER ? "order_id" : "tender_id"; }
    private static long key(int legacyId, Kind kind) { return ((long) legacyId << 1) | (kind == Kind.ORDER ? 0 : 1); }
    private static String name(Object v) { if (v instanceof se.swedsoft.bookkeeping.data.common.SSPaymentTerm x) return x.getName(); if (v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryTerm x) return x.getName(); if (v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryWay x) return x.getName(); return null; }
    private static BigDecimal canon(BigDecimal v) { return v == null ? null : v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros(); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated sale-document key"); return r.getLong(1); } }
    private static long companyId(Connection c, int legacy) throws SQLException { try (PreparedStatement p = c.prepareStatement("SELECT id FROM company WHERE legacy_id=?")) { p.setInt(1, legacy); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Missing company " + legacy); return r.getLong(1); } } }
    private static <K> long req(Map<K, Long> m, K k) throws SQLException { Long v = m.get(k); if (v == null) throw new SQLException("Missing sale-document mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long documents, long addresses, long defaultAccounts, long rows) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private record Header(int legacyId, int companyLegacyId, Kind kind, Integer number, java.time.LocalDate saleDate, java.time.LocalDate expiresOn, String customerNumber, String customerName, String ourContact, String customerContact, BigDecimal delayInterest, String currency, BigDecimal currencyRate, String paymentTerm, String deliveryTerm, String deliveryWay, boolean taxFree, String text, BigDecimal tax1, BigDecimal tax2, BigDecimal tax3, boolean euCommodity, boolean euThird, boolean printed, String orderNumber, String estimatedDelivery, Integer invoiceNumber, Integer periodicInvoiceNumber, Integer purchaseOrderNumber, boolean hideUnitPrice, Integer tenderOrderNumber) {}
    private record Address(int headerLegacyId, int companyLegacyId, Kind kind, String type, String name, String line1, String line2, String postal, String city, String country) { List<String> values() { return Arrays.asList(name, line1, line2, postal, city, country); } }
    private record Account(int headerLegacyId, Kind kind, String type, Integer number) {}
    private record SaleLine(int headerLegacyId, int companyLegacyId, Kind kind, int row, String product, String description, BigDecimal unitPrice, BigDecimal quantity, String unit, BigDecimal discount, String taxCode, Integer account, String project, String resultUnit) {}
    private static final class Snapshot { final List<Header> headers = new ArrayList<>(); final List<Address> addresses = new ArrayList<>(); final List<Account> accounts = new ArrayList<>(); final List<SaleLine> rows = new ArrayList<>(); void sort() { headers.sort(Comparator.comparing((Header r) -> r.kind).thenComparingInt(r -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); addresses.sort(Comparator.comparing((Address r) -> r.kind).thenComparingInt(Address::headerLegacyId).thenComparing(Address::type)); accounts.sort(Comparator.comparing((Account r) -> r.kind).thenComparingInt(Account::headerLegacyId).thenComparing(Account::type)); rows.sort(Comparator.comparing((SaleLine r) -> r.kind).thenComparingInt(SaleLine::headerLegacyId).thenComparingInt(SaleLine::row)); } }
}
