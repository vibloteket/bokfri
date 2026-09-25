package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Converts supplier invoices with ordered rows, account overrides, and main/correction vouchers. */
public final class SupplierInvoiceStagingConverter {
    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy));
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized), source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Supplier-invoice staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.invoices.size(), source.rows.size(), source.accounts.size(), source.vouchers.size(), source.voucherRows.size(), source.gaps, source.overlaps);
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT id,companyid,number,supplierinvoice FROM tbl_supplierinvoice ORDER BY companyid,id", r -> {
            int id = r.getInt(1), company = r.getInt(2); Integer key = nullableInteger(r, 3);
            SSSupplierInvoice x = (SSSupplierInvoice) r.getObject(4);
            if (!Objects.equals(key, x.getNumber())) throw new SQLException("Supplier-invoice key/object mismatch for legacy id " + id + ": table=" + key + ", object=" + x.getNumber());
            s.invoices.add(new Invoice(id, company, key, x.getLocalDate(), x.getLocalDueDate(), x.getPaymentTerm() == null ? null : x.getPaymentTerm().getName(), x.getSupplierNr(), x.getSupplierName(), x.getReferencenumber(), x.getCurrency() == null ? null : x.getCurrency().getName(), x.getCurrencyRate(), x.getTaxSum(), x.getRoundingSum(), x.isEntered(), x.isBGCEntered(), x.isStockInfluencing()));
            if (x.getDefaultAccounts() != null) x.getDefaultAccounts().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).forEach(e -> s.accounts.add(new Account(id, e.getKey().name(), e.getValue())));
            int n = 0; for (SSSupplierInvoiceRow row : x.getRows()) { row.fixResultUnitAndProject(); s.rows.add(new Line(id, company, n++, row.getProductNr(), row.getDescription(), row.getUnitprice(), row.getQuantity(), row.getUnit() == null ? null : row.getUnit().getName(), row.getUnitFreight(), row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr())); }
            addVoucher(s, id, company, "main", x.getVoucher()); addVoucher(s, id, company, "correction", x.getCorrection());
        });
        s.sort(); return s;
    }

    private static void addVoucher(Snapshot s, int headerId, int company, String kind, SSVoucher v) {
        if (v == null) return;
        s.vouchers.add(new VoucherHeader(headerId, kind, v.getNumber(), v.getLocalDate(), v.getDescription()));
        int n = 0;
        for (SSVoucherRow row : v.getRows()) {
            Instant edited = null;
            if (row.getLocalEditedDate() != null) { var res = LegacySwedishTimeResolver.resolve(row.getLocalEditedDate()); edited = res.instant(); if (res.kind() == LegacySwedishTimeResolver.ResolutionKind.FORWARD_BY_GAP) s.gaps++; else if (res.kind() == LegacySwedishTimeResolver.ResolutionKind.EARLIER_OFFSET_IN_OVERLAP) s.overlaps++; }
            s.voucherRows.add(new VoucherLine(headerId, kind, n++, row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(), row.getDebet(), row.getCredit(), edited, row.getEditedSignature(), row.isCrossed(), row.isAdded()));
        }
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Integer, Invoice> map = new HashMap<>();
        for (Invoice r : s.invoices) { try (PreparedStatement p = c.prepareStatement("INSERT INTO supplier_invoice (legacy_id,company_id,number,invoice_date,due_date,payment_term_name,supplier_number,supplier_name,reference_number,currency_code,currency_rate,tax_sum,rounding_sum,entered,bgc_entered,stock_influencing) SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) { int i = 1; p.setInt(i++, r.legacyId); setInteger(p, i++, r.number); p.setObject(i++, r.date); p.setObject(i++, r.dueDate); p.setString(i++, r.paymentTerm); p.setString(i++, r.supplierNumber); p.setString(i++, r.supplierName); p.setString(i++, r.referenceNumber); p.setString(i++, r.currency); p.setBigDecimal(i++, r.currencyRate); p.setBigDecimal(i++, r.taxSum); p.setBigDecimal(i++, r.roundingSum); p.setBoolean(i++, r.entered); p.setBoolean(i++, r.bgcEntered); p.setBoolean(i++, r.stockInfluencing); p.setInt(i, r.companyLegacyId); if (p.executeUpdate() != 1) throw new SQLException("Missing company " + r.companyLegacyId); r.normalizedId = key(p); map.put(r.legacyId, r); } }
        for (Line r : s.rows) { Invoice x = req(map, r.invoiceLegacyId); try (PreparedStatement p = c.prepareStatement("INSERT INTO supplier_invoice_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) { p.setLong(1, x.normalizedId); p.setLong(2, companyId(c, x.companyLegacyId)); p.setInt(3, r.row); p.setString(4, r.product); p.setString(5, r.description); p.setBigDecimal(6, r.unitPrice); setInteger(p, 7, r.quantity); p.setString(8, r.unit); p.setBigDecimal(9, r.freight); setInteger(p, 10, r.account); p.setString(11, r.project); p.setString(12, r.resultUnit); p.executeUpdate(); } }
        for (Account r : s.accounts) { try (PreparedStatement p = c.prepareStatement("INSERT INTO supplier_invoice_default_account VALUES (?,?,?)")) { p.setLong(1, req(map, r.invoiceLegacyId).normalizedId); p.setString(2, r.type); setInteger(p, 3, r.number); p.executeUpdate(); } }
        for (VoucherHeader r : s.vouchers) { try (PreparedStatement p = c.prepareStatement("INSERT INTO supplier_invoice_voucher VALUES (?,?,?,?,?)")) { p.setLong(1, req(map, r.invoiceLegacyId).normalizedId); p.setString(2, r.kind); p.setInt(3, r.number); p.setObject(4, r.date); p.setString(5, r.description); p.executeUpdate(); } }
        for (VoucherLine r : s.voucherRows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO supplier_invoice_voucher_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) { p.setLong(1, req(map, r.invoiceLegacyId).normalizedId); p.setString(2, r.kind); p.setInt(3, r.row); setInteger(p, 4, r.account); p.setString(5, r.project); p.setString(6, r.resultUnit); p.setBigDecimal(7, r.debit); p.setBigDecimal(8, r.credit); p.setObject(9, r.edited == null ? null : OffsetDateTime.ofInstant(r.edited, ZoneOffset.UTC)); p.setString(10, r.signature); p.setBoolean(11, r.crossed); p.setBoolean(12, r.added); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT i.id,i.legacy_id,c.legacy_id,i.number,i.invoice_date,i.due_date,i.payment_term_name,i.supplier_number,i.supplier_name,i.reference_number,i.currency_code,i.currency_rate,i.tax_sum,i.rounding_sum,i.entered,i.bgc_entered,i.stock_influencing FROM supplier_invoice i JOIN company c ON c.id=i.company_id ORDER BY c.legacy_id,i.legacy_id", r -> { Invoice x = new Invoice(r.getInt(2), r.getInt(3), nullableInteger(r, 4), r.getObject(5, java.time.LocalDate.class), r.getObject(6, java.time.LocalDate.class), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getBigDecimal(12), r.getBigDecimal(13), r.getBigDecimal(14), r.getBoolean(15), r.getBoolean(16), r.getBoolean(17)); x.normalizedId = r.getLong(1); s.invoices.add(x); });
        query(c, "SELECT i.legacy_id,c.legacy_id,x.row_number,x.product_number,x.description,x.unit_price,x.quantity,x.unit_name,x.unit_freight,x.account_number,x.project_number,x.result_unit_number FROM supplier_invoice_row x JOIN supplier_invoice i ON i.id=x.supplier_invoice_id JOIN company c ON c.id=x.company_id ORDER BY c.legacy_id,i.legacy_id,x.row_number", r -> s.rows.add(new Line(r.getInt(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getBigDecimal(6), nullableInteger(r, 7), r.getString(8), r.getBigDecimal(9), nullableInteger(r, 10), r.getString(11), r.getString(12))));
        query(c, "SELECT i.legacy_id,a.account_type,a.account_number FROM supplier_invoice_default_account a JOIN supplier_invoice i ON i.id=a.supplier_invoice_id ORDER BY i.legacy_id,a.account_type", r -> s.accounts.add(new Account(r.getInt(1), r.getString(2), nullableInteger(r, 3))));
        query(c, "SELECT i.legacy_id,v.voucher_kind,v.number,v.voucher_date,v.description FROM supplier_invoice_voucher v JOIN supplier_invoice i ON i.id=v.supplier_invoice_id ORDER BY i.legacy_id,v.voucher_kind", r -> s.vouchers.add(new VoucherHeader(r.getInt(1), r.getString(2), r.getInt(3), r.getObject(4, java.time.LocalDate.class), r.getString(5))));
        query(c, "SELECT i.legacy_id,x.voucher_kind,x.row_number,x.account_number,x.project_number,x.result_unit_number,x.debit,x.credit,x.edited_at,x.edited_signature,x.crossed,x.added FROM supplier_invoice_voucher_row x JOIN supplier_invoice i ON i.id=x.supplier_invoice_id ORDER BY i.legacy_id,x.voucher_kind,x.row_number", r -> s.voucherRows.add(new VoucherLine(r.getInt(1), r.getString(2), r.getInt(3), nullableInteger(r, 4), r.getString(5), r.getString(6), r.getBigDecimal(7), r.getBigDecimal(8), r.getObject(9) == null ? null : r.getObject(9, OffsetDateTime.class).toInstant(), r.getString(10), r.getBoolean(11), r.getBoolean(12))));
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var d = b.domain("supplier-invoices"); for (Invoice r : s.invoices) d.startRecord(r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeInteger(r.number).writeDate(r.date).writeDate(r.dueDate).writeString(r.paymentTerm).writeString(r.supplierNumber).writeString(r.supplierName).writeString(r.referenceNumber).writeString(r.currency).writeDecimal(canon(r.currencyRate)).writeDecimal(canon(r.taxSum)).writeDecimal(canon(r.roundingSum)).writeBoolean(r.entered).writeBoolean(r.bgcEntered).writeBoolean(r.stockInfluencing); d.finish();
        var lr = b.domain("supplier-invoice-rows"); for (Line r : s.rows) lr.startRecord(r.invoiceLegacyId + "/" + r.row).writeInteger(r.invoiceLegacyId).writeInteger(r.companyLegacyId).writeInteger(r.row).writeString(r.product).writeString(r.description).writeDecimal(canon(r.unitPrice)).writeInteger(r.quantity).writeString(r.unit).writeDecimal(canon(r.freight)).writeInteger(r.account).writeString(r.project).writeString(r.resultUnit); lr.finish();
        var a = b.domain("supplier-invoice-default-accounts"); for (Account r : s.accounts) a.startRecord(r.invoiceLegacyId + "/" + r.type).writeInteger(r.invoiceLegacyId).writeString(r.type).writeInteger(r.number); a.finish();
        var vh = b.domain("supplier-invoice-vouchers"); for (VoucherHeader r : s.vouchers) vh.startRecord(r.invoiceLegacyId + "/" + r.kind).writeInteger(r.invoiceLegacyId).writeString(r.kind).writeInteger(r.number).writeDate(r.date).writeString(r.description); vh.finish();
        var vr = b.domain("supplier-invoice-voucher-rows"); for (VoucherLine r : s.voucherRows) vr.startRecord(r.invoiceLegacyId + "/" + r.kind + "/" + r.row).writeInteger(r.invoiceLegacyId).writeString(r.kind).writeInteger(r.row).writeInteger(r.account).writeString(r.project).writeString(r.resultUnit).writeDecimal(canon(r.debit)).writeDecimal(canon(r.credit)).writeInstant(r.edited).writeString(r.signature).writeBoolean(r.crossed).writeBoolean(r.added).addDebit(canon(r.debit)).addCredit(canon(r.credit)); vr.finish();
        return b.finish();
    }

    private static BigDecimal canon(BigDecimal v) { return v == null ? null : v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros(); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated supplier-invoice key"); return r.getLong(1); } }
    private static long companyId(Connection c, int legacy) throws SQLException { try (PreparedStatement p = c.prepareStatement("SELECT id FROM company WHERE legacy_id=?")) { p.setInt(1, legacy); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Missing company " + legacy); return r.getLong(1); } } }
    private static <K, V> V req(Map<K, V> m, K k) throws SQLException { V v = m.get(k); if (v == null) throw new SQLException("Missing supplier-invoice mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long invoices, long rows, long defaultAccounts, long vouchers, long voucherRows, long daylightSavingGapAdjustments, long daylightSavingOverlapResolutions) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private static final class Invoice { final int legacyId, companyLegacyId; final Integer number; final java.time.LocalDate date, dueDate; final String paymentTerm, supplierNumber, supplierName, referenceNumber, currency; final BigDecimal currencyRate, taxSum, roundingSum; final boolean entered, bgcEntered, stockInfluencing; long normalizedId; Invoice(int legacyId, int companyLegacyId, Integer number, java.time.LocalDate date, java.time.LocalDate dueDate, String paymentTerm, String supplierNumber, String supplierName, String referenceNumber, String currency, BigDecimal currencyRate, BigDecimal taxSum, BigDecimal roundingSum, boolean entered, boolean bgcEntered, boolean stockInfluencing) { this.legacyId = legacyId; this.companyLegacyId = companyLegacyId; this.number = number; this.date = date; this.dueDate = dueDate; this.paymentTerm = paymentTerm; this.supplierNumber = supplierNumber; this.supplierName = supplierName; this.referenceNumber = referenceNumber; this.currency = currency; this.currencyRate = currencyRate; this.taxSum = taxSum; this.roundingSum = roundingSum; this.entered = entered; this.bgcEntered = bgcEntered; this.stockInfluencing = stockInfluencing; } }
    private record Line(int invoiceLegacyId, int companyLegacyId, int row, String product, String description, BigDecimal unitPrice, Integer quantity, String unit, BigDecimal freight, Integer account, String project, String resultUnit) {}
    private record Account(int invoiceLegacyId, String type, Integer number) {}
    private record VoucherHeader(int invoiceLegacyId, String kind, int number, java.time.LocalDate date, String description) {}
    private record VoucherLine(int invoiceLegacyId, String kind, int row, Integer account, String project, String resultUnit, BigDecimal debit, BigDecimal credit, Instant edited, String signature, boolean crossed, boolean added) {}
    private static final class Snapshot { final List<Invoice> invoices = new ArrayList<>(); final List<Line> rows = new ArrayList<>(); final List<Account> accounts = new ArrayList<>(); final List<VoucherHeader> vouchers = new ArrayList<>(); final List<VoucherLine> voucherRows = new ArrayList<>(); long gaps, overlaps; void sort() { invoices.sort(Comparator.comparingInt((Invoice r) -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); rows.sort(Comparator.comparingInt(Line::invoiceLegacyId).thenComparingInt(Line::row)); accounts.sort(Comparator.comparingInt(Account::invoiceLegacyId).thenComparing(Account::type)); vouchers.sort(Comparator.comparingInt(VoucherHeader::invoiceLegacyId).thenComparing(VoucherHeader::kind)); voucherRows.sort(Comparator.comparingInt(VoucherLine::invoiceLegacyId).thenComparing(VoucherLine::kind).thenComparingInt(VoucherLine::row)); } }
}
