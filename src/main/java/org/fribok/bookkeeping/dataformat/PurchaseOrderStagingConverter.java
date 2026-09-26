package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Converts purchase orders with supplier snapshot, two addresses, ordered rows, and accounts. */
public final class PurchaseOrderStagingConverter {
    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy));
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized), source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Purchase-order staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.orders.size(), source.addresses.size(), source.accounts.size(), source.rows.size());
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT id,companyid,number,purchaseorder FROM tbl_purchaseorder ORDER BY companyid,id", r -> {
            int id = r.getInt(1), company = r.getInt(2); Integer key = nullableInteger(r, 3);
            SSPurchaseOrder x = (SSPurchaseOrder) r.getObject(4);
            if (!Objects.equals(key, x.getNumber())) throw new SQLException("Purchase-order key/object mismatch for legacy id " + id + ": table=" + key + ", object=" + x.getNumber());
            s.orders.add(new Order(id, company, key, x.getLocalDate(), x.getLocalEstimatedDelivery(), x.getSupplierNr(), x.getSupplierName(), name(x.getPaymentTerm()), name(x.getDeliveryTerm()), name(x.getDeliveryWay()), x.getOurContact(), x.getYourContact(), x.getCurrency() == null ? null : x.getCurrency().getName(), x.getCurrencyRate(), x.getText(), x.isPrinted(), x.isStockInfluencing(), x.getInvoiceNr()));
            addAddress(s, id, company, "delivery", x.getDeliveryAddress());
            addAddress(s, id, company, "supplier", x.getSupplierAddress());
            if (x.getDefaultAccounts() != null) x.getDefaultAccounts().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).forEach(e -> s.accounts.add(new Account(id, e.getKey().name(), e.getValue())));
            int n = 0; for (SSPurchaseOrderRow row : x.getRows()) s.rows.add(new Line(id, n++, row.getProductNr(), row.getDescription(), row.getSupplierArticleNr(), row.getUnitPrice(), row.getQuantity(), row.getUnit() == null ? null : row.getUnit().getName(), row.getAccountNr()));
        });
        s.sort(); return s;
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Integer, Long> ids = new HashMap<>();
        for (Order r : s.orders) { try (PreparedStatement p = c.prepareStatement("INSERT INTO purchase_order (legacy_id,company_id,number,order_date,estimated_delivery_on,supplier_number,supplier_name,payment_term_name,delivery_term_name,delivery_way_name,our_contact_person,supplier_contact_person,currency_code,currency_rate,order_text,printed,stock_influencing,invoice_number) SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) { int i=1; p.setInt(i++,r.legacyId); setInteger(p,i++,r.number); p.setObject(i++,r.date); p.setObject(i++,r.estimatedDelivery); p.setString(i++,r.supplierNumber); p.setString(i++,r.supplierName); p.setString(i++,r.paymentTerm); p.setString(i++,r.deliveryTerm); p.setString(i++,r.deliveryWay); p.setString(i++,r.ourContact); p.setString(i++,r.supplierContact); p.setString(i++,r.currency); p.setBigDecimal(i++,r.currencyRate); p.setString(i++,r.text); p.setBoolean(i++,r.printed); p.setBoolean(i++,r.stockInfluencing); setInteger(p,i++,r.invoiceNumber); p.setInt(i,r.companyLegacyId); if(p.executeUpdate()!=1) throw new SQLException("Missing company "+r.companyLegacyId+" for purchase order "+r.legacyId); ids.put(r.legacyId,key(p)); } }
        for (Address r : s.addresses) { try (PreparedStatement p = c.prepareStatement("INSERT INTO purchase_order_address VALUES (?,?,?,?,?,?,?,?,?)")) { p.setLong(1,req(ids,r.orderLegacyId)); p.setLong(2,companyId(c,r.companyLegacyId)); p.setString(3,r.type); int i=4; for(String v:r.values()) p.setString(i++,v); p.executeUpdate(); } }
        for (Account r : s.accounts) { try (PreparedStatement p = c.prepareStatement("INSERT INTO purchase_order_default_account VALUES (?,?,?)")) { p.setLong(1,req(ids,r.orderLegacyId)); p.setString(2,r.type); setInteger(p,3,r.number); p.executeUpdate(); } }
        for (Line r : s.rows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO purchase_order_row VALUES (?,?,?,?,?,?,?,?,?)")) { p.setLong(1,req(ids,r.orderLegacyId)); p.setInt(2,r.row); p.setString(3,r.product); p.setString(4,r.description); p.setString(5,r.supplierArticle); p.setBigDecimal(6,r.unitPrice); setInteger(p,7,r.quantity); p.setString(8,r.unit); setInteger(p,9,r.account); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT o.legacy_id,c.legacy_id,o.number,o.order_date,o.estimated_delivery_on,o.supplier_number,o.supplier_name,o.payment_term_name,o.delivery_term_name,o.delivery_way_name,o.our_contact_person,o.supplier_contact_person,o.currency_code,o.currency_rate,o.order_text,o.printed,o.stock_influencing,o.invoice_number FROM purchase_order o JOIN company c ON c.id=o.company_id ORDER BY c.legacy_id,o.legacy_id", r -> s.orders.add(new Order(r.getInt(1), r.getInt(2), nullableInteger(r,3), r.getObject(4,java.time.LocalDate.class), r.getObject(5,java.time.LocalDate.class), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getBigDecimal(14), r.getString(15), r.getBoolean(16), r.getBoolean(17), nullableInteger(r,18))));
        query(c, "SELECT o.legacy_id,c.legacy_id,a.address_type,a.name,a.address_line_1,a.address_line_2,a.postal_code,a.city,a.country FROM purchase_order_address a JOIN purchase_order o ON o.id=a.purchase_order_id JOIN company c ON c.id=a.company_id ORDER BY c.legacy_id,o.legacy_id,a.address_type", r -> s.addresses.add(new Address(r.getInt(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9))));
        query(c, "SELECT o.legacy_id,a.account_type,a.account_number FROM purchase_order_default_account a JOIN purchase_order o ON o.id=a.purchase_order_id ORDER BY o.legacy_id,a.account_type", r -> s.accounts.add(new Account(r.getInt(1), r.getString(2), nullableInteger(r,3))));
        query(c, "SELECT o.legacy_id,x.row_number,x.product_number,x.description,x.supplier_article_number,x.unit_price,x.quantity,x.unit_name,x.account_number FROM purchase_order_row x JOIN purchase_order o ON o.id=x.purchase_order_id ORDER BY o.legacy_id,x.row_number", r -> s.rows.add(new Line(r.getInt(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5), r.getBigDecimal(6), nullableInteger(r,7), r.getString(8), nullableInteger(r,9))));
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var d = b.domain("purchase-orders"); for (Order r : s.orders) d.startRecord(r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeInteger(r.number).writeDate(r.date).writeDate(r.estimatedDelivery).writeString(r.supplierNumber).writeString(r.supplierName).writeString(r.paymentTerm).writeString(r.deliveryTerm).writeString(r.deliveryWay).writeString(r.ourContact).writeString(r.supplierContact).writeString(r.currency).writeDecimal(canon(r.currencyRate)).writeString(r.text).writeBoolean(r.printed).writeBoolean(r.stockInfluencing).writeInteger(r.invoiceNumber); d.finish();
        var a = b.domain("purchase-order-addresses"); for (Address r : s.addresses) { var w = a.startRecord(r.orderLegacyId + "/" + r.type).writeInteger(r.orderLegacyId).writeInteger(r.companyLegacyId).writeString(r.type); for (String v : r.values()) w.writeString(v); } a.finish();
        var ac = b.domain("purchase-order-default-accounts"); for (Account r : s.accounts) ac.startRecord(r.orderLegacyId + "/" + r.type).writeInteger(r.orderLegacyId).writeString(r.type).writeInteger(r.number); ac.finish();
        var rw = b.domain("purchase-order-rows"); for (Line r : s.rows) rw.startRecord(r.orderLegacyId + "/" + r.row).writeInteger(r.orderLegacyId).writeInteger(r.row).writeString(r.product).writeString(r.description).writeString(r.supplierArticle).writeDecimal(canon(r.unitPrice)).writeInteger(r.quantity).writeString(r.unit).writeInteger(r.account); rw.finish();
        return b.finish();
    }

    private static void addAddress(Snapshot s, int order, int company, String type, SSAddress a) { if (a != null) s.addresses.add(new Address(order, company, type, a.getName(), a.getAddress1(), a.getAddress2(), a.getZipCode(), a.getCity(), a.getCountry())); }
    private static String name(Object v) { if (v instanceof se.swedsoft.bookkeeping.data.common.SSPaymentTerm x) return x.getName(); if (v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryTerm x) return x.getName(); if (v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryWay x) return x.getName(); return null; }
    private static BigDecimal canon(BigDecimal value) { return CanonicalValues.amount(value); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated purchase-order key"); return r.getLong(1); } }
    private static long companyId(Connection c, int legacy) throws SQLException { try (PreparedStatement p = c.prepareStatement("SELECT id FROM company WHERE legacy_id=?")) { p.setInt(1, legacy); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Missing company " + legacy); return r.getLong(1); } } }
    private static <K> long req(Map<K, Long> m, K k) throws SQLException { Long v = m.get(k); if (v == null) throw new SQLException("Missing purchase-order mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long orders, long addresses, long defaultAccounts, long rows) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private record Order(int legacyId, int companyLegacyId, Integer number, java.time.LocalDate date, java.time.LocalDate estimatedDelivery, String supplierNumber, String supplierName, String paymentTerm, String deliveryTerm, String deliveryWay, String ourContact, String supplierContact, String currency, BigDecimal currencyRate, String text, boolean printed, boolean stockInfluencing, Integer invoiceNumber) {}
    private record Address(int orderLegacyId, int companyLegacyId, String type, String name, String line1, String line2, String postal, String city, String country) { List<String> values() { return Arrays.asList(name, line1, line2, postal, city, country); } }
    private record Account(int orderLegacyId, String type, Integer number) {}
    private record Line(int orderLegacyId, int row, String product, String description, String supplierArticle, BigDecimal unitPrice, Integer quantity, String unit, Integer account) {}
    private static final class Snapshot { final List<Order> orders = new ArrayList<>(); final List<Address> addresses = new ArrayList<>(); final List<Account> accounts = new ArrayList<>(); final List<Line> rows = new ArrayList<>(); void sort() { orders.sort(Comparator.comparingInt((Order r) -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); addresses.sort(Comparator.comparingInt(Address::orderLegacyId).thenComparing(Address::type)); accounts.sort(Comparator.comparingInt(Account::orderLegacyId).thenComparing(Account::type)); rows.sort(Comparator.comparingInt(Line::orderLegacyId).thenComparingInt(Line::row)); } }
}
