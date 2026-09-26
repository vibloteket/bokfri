package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;

import java.sql.*;
import java.util.*;

/** Converts in/out deliveries and inventory counts: headers with ordered product-quantity rows. */
public final class StockDocumentStagingConverter {
    public enum Kind {IN, OUT, INVENTORY}

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy));
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized), source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Stock staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.headers.size(), source.rows.size());
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        for (Kind kind : Kind.values()) {
            String legacyTable = "tbl_" + column(kind), column = column(kind);
            query(c, "SELECT id,companyid,number," + column + " FROM " + legacyTable + " ORDER BY companyid,id", r -> {
                int id = r.getInt(1), company = r.getInt(2); Integer key = nullableInteger(r, 3);
                Object o = r.getObject(4);
                Integer number; java.time.LocalDate date; String text;
                List<?> rows;
                if (o instanceof SSIndelivery x) { number = x.getNumber(); date = x.getLocalDate(); text = x.getText(); rows = x.getRows(); }
                else if (o instanceof SSOutdelivery x) { number = x.getNumber(); date = x.getLocalDate(); text = x.getText(); rows = x.getRows(); }
                else { SSInventory x = (SSInventory) o; number = x.getNumber(); date = x.getLocalDate(); text = x.getText(); rows = x.getRows(); }
                if (!Objects.equals(key, number)) throw new SQLException(column + " key/object mismatch for legacy id " + id);
                s.headers.add(new Header(id, company, kind, key, date, text));
                int n = 0;
                for (Object ro : rows) {
                    String product; Integer stockQty, change;
                    if (ro instanceof SSIndeliveryRow row) { product = row.getProductNr(); stockQty = null; change = row.getChange(); }
                    else if (ro instanceof SSOutdeliveryRow row) { product = row.getProductNr(); stockQty = null; change = row.getChange(); }
                    else { SSInventoryRow row = (SSInventoryRow) ro; product = row.getProductNr(); stockQty = row.getStockQuantity(); change = row.getChange(); }
                    s.rows.add(new Line(id, company, kind, n++, product, stockQty, change));
                }
            });
        }
        s.sort(); return s;
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Long, Long> ids = new HashMap<>();
        for (Header r : s.headers) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + " (legacy_id,company_id,number," + dateColumn(r.kind) + "," + textColumn(r.kind) + ") SELECT ?,id,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) { p.setInt(1, r.legacyId); setInteger(p, 2, r.number); p.setObject(3, r.date); p.setString(4, r.text); p.setInt(5, r.companyLegacyId); if (p.executeUpdate() != 1) throw new SQLException("Missing company " + r.companyLegacyId); ids.put(key(r.legacyId, r.kind), key(p)); } }
        for (Line r : s.rows) { String sql = r.kind == Kind.INVENTORY ? "INSERT INTO inventory_row VALUES (?,?,?,?,?)" : "INSERT INTO " + table(r.kind) + "_row VALUES (?,?,?,?)"; try (PreparedStatement p = c.prepareStatement(sql)) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setInt(2, r.row); p.setString(3, r.product); if (r.kind == Kind.INVENTORY) { setInteger(p, 4, r.stockQuantity); setInteger(p, 5, r.change); } else setInteger(p, 4, r.change); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        for (Kind kind : Kind.values()) { String t = table(kind);
            query(c, "SELECT p.legacy_id,co.legacy_id,p.number,p." + dateColumn(kind) + ",p." + textColumn(kind) + " FROM " + t + " p JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id", r -> s.headers.add(new Header(r.getInt(1), r.getInt(2), kind, nullableInteger(r, 3), r.getObject(4, java.time.LocalDate.class), r.getString(5))));
            if (kind == Kind.INVENTORY) {
                query(c, "SELECT p.legacy_id,co.legacy_id,x.row_number,x.product_number,x.stock_quantity,x.change_quantity FROM inventory_row x JOIN inventory p ON p.id=x.inventory_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,x.row_number", r -> s.rows.add(new Line(r.getInt(1), r.getInt(2), kind, r.getInt(3), r.getString(4), nullableInteger(r, 5), nullableInteger(r, 6))));
            } else {
                query(c, "SELECT p.legacy_id,co.legacy_id,x.row_number,x.product_number,x.change_quantity FROM " + t + "_row x JOIN " + t + " p ON p.id=x." + t + "_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,x.row_number", r -> s.rows.add(new Line(r.getInt(1), r.getInt(2), kind, r.getInt(3), r.getString(4), null, nullableInteger(r, 5))));
            }
        }
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var h = b.domain("stock-documents"); for (Header r : s.headers) h.startRecord(r.kind + "/" + r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.number).writeDate(r.date).writeString(r.text); h.finish();
        var rw = b.domain("stock-document-rows"); for (Line r : s.rows) rw.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.row).writeInteger(r.headerLegacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.row).writeString(r.product).writeInteger(r.stockQuantity).writeInteger(r.change); rw.finish();
        return b.finish();
    }

    private static String table(Kind k) { return switch (k) { case IN -> "indelivery"; case OUT -> "outdelivery"; case INVENTORY -> "inventory"; }; }
    private static String column(Kind k) { return switch (k) { case IN -> "indelivery"; case OUT -> "outdelivery"; case INVENTORY -> "inventory"; }; }
    private static String dateColumn(Kind k) { return k == Kind.INVENTORY ? "inventory_date" : "delivery_date"; }
    private static String textColumn(Kind k) { return k == Kind.INVENTORY ? "inventory_text" : "delivery_text"; }
    private static long key(int legacyId, Kind kind) { return ((long) legacyId << 2) | kind.ordinal(); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated stock key"); return r.getLong(1); } }
    private static <K> long req(Map<K, Long> m, K k) throws SQLException { Long v = m.get(k); if (v == null) throw new SQLException("Missing stock mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long documents, long rows) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private record Header(int legacyId, int companyLegacyId, Kind kind, Integer number, java.time.LocalDate date, String text) {}
    private record Line(int headerLegacyId, int companyLegacyId, Kind kind, int row, String product, Integer stockQuantity, Integer change) {}
    private static final class Snapshot { final List<Header> headers = new ArrayList<>(); final List<Line> rows = new ArrayList<>(); void sort() { headers.sort(Comparator.comparing((Header r) -> r.kind).thenComparingInt(r -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); rows.sort(Comparator.comparing((Line r) -> r.kind).thenComparingInt(Line::headerLegacyId).thenComparingInt(Line::row)); } }
}
