package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.common.SSCurrency;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Converts customer inpayments and supplier outpayments with rows and both voucher snapshots. */
public final class PaymentStagingConverter {
    public enum Kind {IN, OUT}

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Objects.requireNonNull(legacy, "legacy"); Objects.requireNonNull(normalized, "normalized");
        Snapshot source = readLegacy(legacy);
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(normalized, source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Payment staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.headers.size(), source.rows.size(), source.accounts.size(), source.voucherRows.size(), source.gaps, source.overlaps);
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        readKind(c, s, Kind.IN); readKind(c, s, Kind.OUT); s.sort(); return s;
    }

    private static void readKind(Connection c, Snapshot s, Kind kind) throws SQLException {
        String table = kind == Kind.IN ? "tbl_inpayment" : "tbl_outpayment";
        String column = kind == Kind.IN ? "inpayment" : "outpayment";
        query(c, "SELECT id,companyid,number," + column + " FROM " + table + " ORDER BY companyid,id", r -> {
            int id = r.getInt(1), company = r.getInt(2); Integer key = nullableInteger(r, 3);
            Integer number; java.time.LocalDate date; String text; boolean entered; SSVoucher voucher, difference;
            Map<se.swedsoft.bookkeeping.data.common.SSDefaultAccount, Integer> defaults;
            if (kind == Kind.IN) {
                SSInpayment x = (SSInpayment) r.getObject(4); number = x.getNumber(); date = x.getLocalDate(); text = x.getText(); entered = x.isEntered(); voucher = x.getVoucher(); difference = x.getStoredDifference(); defaults = x.getDefaultAccounts();
                if (!Objects.equals(key, number)) throw new SQLException("Inpayment key/object mismatch for legacy id " + id);
            } else {
                SSOutpayment x = (SSOutpayment) r.getObject(4); number = x.getNumber(); date = x.getLocalDate(); text = x.getText(); entered = x.isEntered(); voucher = x.getVoucher(); difference = x.getStoredDifference(); defaults = x.getDefaultAccounts();
                if (!Objects.equals(key, number)) throw new SQLException("Outpayment key/object mismatch for legacy id " + id);
            }
            s.headers.add(new Header(id, company, kind, number, date, text, entered));
            if (defaults != null) defaults.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).forEach(e -> s.accounts.add(new Account(id, company, kind, e.getKey().name(), e.getValue())));
            List<?> rows = kind == Kind.IN ? ((SSInpayment) r.getObject(4)).getRows() : ((SSOutpayment) r.getObject(4)).getRows();
            int n = 0;
            for (Object o : rows) {
                Integer invoiceNr; SSCurrency invoiceCurrency; BigDecimal invoiceRate, value, rate;
                if (o instanceof SSInpaymentRow row) { invoiceNr = row.getInvoiceNr(); invoiceCurrency = row.getInvoiceCurrency(); invoiceRate = row.getInvoiceCurrencyRate(); value = row.getValue(); rate = row.getCurrencyRate(); }
                else { SSOutpaymentRow row = (SSOutpaymentRow) o; invoiceNr = row.getInvoiceNr(); invoiceCurrency = row.getInvoiceCurrency(); invoiceRate = row.getInvoiceCurrencyRate(); value = row.getValue(); rate = row.getCurrencyRate(); }
                s.rows.add(new PayRow(id, company, kind, n++, invoiceNr, invoiceCurrency == null ? null : invoiceCurrency.getName(), invoiceRate, value, rate));
            }
            addVoucher(s, id, company, kind, "main", voucher);
            addVoucher(s, id, company, kind, "difference", difference);
        });
    }

    private static void addVoucher(Snapshot s, int headerId, int company, Kind kind, String voucherKind, SSVoucher v) {
        if (v == null) return;
        s.vouchers.add(new VoucherHeader(headerId, company, kind, voucherKind, v.getNumber(), v.getLocalDate(), v.getDescription()));
        int n = 0;
        for (SSVoucherRow row : v.getRows()) {
            Instant edited = null;
            if (row.getLocalEditedDate() != null) {
                var res = LegacySwedishTimeResolver.resolve(row.getLocalEditedDate()); edited = res.instant();
                if (res.kind() == LegacySwedishTimeResolver.ResolutionKind.FORWARD_BY_GAP) s.gaps++;
                else if (res.kind() == LegacySwedishTimeResolver.ResolutionKind.EARLIER_OFFSET_IN_OVERLAP) s.overlaps++;
            }
            s.voucherRows.add(new VoucherLine(headerId, company, kind, voucherKind, n++, row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr(), row.getDebet(), row.getCredit(), edited, row.getEditedSignature(), row.isCrossed(), row.isAdded()));
        }
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Long, Long> ids = new HashMap<>();
        for (Header r : s.headers) { String t = table(r.kind); try (PreparedStatement p = c.prepareStatement("INSERT INTO " + t + " (legacy_id,company_id,number,payment_date,text,entered) SELECT ?,id,?,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) { p.setInt(1, r.legacyId); setInteger(p, 2, r.number); p.setObject(3, r.date); p.setString(4, r.text); p.setBoolean(5, r.entered); p.setInt(6, r.companyLegacyId); if (p.executeUpdate() != 1) throw new SQLException("Missing company " + r.companyLegacyId + " for " + r.kind + " " + r.legacyId); long nid = key(p); ids.put(key(r.legacyId, r.kind), nid); } }
        for (PayRow r : s.rows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_row VALUES (?,?,?,?,?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setInt(2, r.row); setInteger(p, 3, r.invoiceNumber); p.setString(4, r.invoiceCurrency); p.setBigDecimal(5, r.invoiceRate); p.setBigDecimal(6, r.value); p.setBigDecimal(7, r.rate); p.executeUpdate(); } }
        for (Account r : s.accounts) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_default_account VALUES (?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setString(2, r.type); setInteger(p, 3, r.number); p.executeUpdate(); } }
        for (VoucherHeader r : s.vouchers) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_voucher VALUES (?,?,?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setString(2, r.voucherKind); p.setInt(3, r.number); p.setObject(4, r.date); p.setString(5, r.description); p.executeUpdate(); } }
        for (VoucherLine r : s.voucherRows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table(r.kind) + "_voucher_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) { p.setLong(1, req(ids, key(r.headerLegacyId, r.kind))); p.setString(2, r.voucherKind); p.setInt(3, r.row); setInteger(p, 4, r.account); p.setString(5, r.project); p.setString(6, r.resultUnit); p.setBigDecimal(7, r.debit); p.setBigDecimal(8, r.credit); p.setObject(9, r.edited == null ? null : OffsetDateTime.ofInstant(r.edited, ZoneOffset.UTC)); p.setString(10, r.signature); p.setBoolean(11, r.crossed); p.setBoolean(12, r.added); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        for (Kind kind : Kind.values()) { String t = table(kind);
            query(c, "SELECT p.legacy_id,co.legacy_id,p.number,p.payment_date,p.text,p.entered FROM " + t + " p JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id", r -> s.headers.add(new Header(r.getInt(1), r.getInt(2), kind, nullableInteger(r, 3), r.getObject(4, java.time.LocalDate.class), r.getString(5), r.getBoolean(6))));
            query(c, "SELECT p.legacy_id,co.legacy_id,x.row_number,x.invoice_number,x.invoice_currency_code,x.invoice_currency_rate,x.value,x.currency_rate FROM " + t + "_row x JOIN " + t + " p ON p.id=x." + t + "_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,x.row_number", r -> s.rows.add(new PayRow(r.getInt(1), r.getInt(2), kind, r.getInt(3), nullableInteger(r, 4), r.getString(5), r.getBigDecimal(6), r.getBigDecimal(7), r.getBigDecimal(8))));
            query(c, "SELECT p.legacy_id,co.legacy_id,a.account_type,a.account_number FROM " + t + "_default_account a JOIN " + t + " p ON p.id=a." + t + "_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,a.account_type", r -> s.accounts.add(new Account(r.getInt(1), r.getInt(2), kind, r.getString(3), nullableInteger(r, 4))));
            query(c, "SELECT p.legacy_id,co.legacy_id,v.voucher_kind,v.number,v.voucher_date,v.description FROM " + t + "_voucher v JOIN " + t + " p ON p.id=v." + t + "_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,v.voucher_kind", r -> s.vouchers.add(new VoucherHeader(r.getInt(1), r.getInt(2), kind, r.getString(3), r.getInt(4), r.getObject(5, java.time.LocalDate.class), r.getString(6))));
            query(c, "SELECT p.legacy_id,co.legacy_id,x.voucher_kind,x.row_number,x.account_number,x.project_number,x.result_unit_number,x.debit,x.credit,x.edited_at,x.edited_signature,x.crossed,x.added FROM " + t + "_voucher_row x JOIN " + t + " p ON p.id=x." + t + "_id JOIN company co ON co.id=p.company_id ORDER BY co.legacy_id,p.legacy_id,x.voucher_kind,x.row_number", r -> s.voucherRows.add(new VoucherLine(r.getInt(1), r.getInt(2), kind, r.getString(3), r.getInt(4), nullableInteger(r, 5), r.getString(6), r.getString(7), r.getBigDecimal(8), r.getBigDecimal(9), r.getObject(10) == null ? null : r.getObject(10, OffsetDateTime.class).toInstant(), r.getString(11), r.getBoolean(12), r.getBoolean(13))));
        }
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var h = b.domain("payments"); for (Header r : s.headers) h.startRecord(r.kind + "/" + r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.number).writeDate(r.date).writeString(r.text).writeBoolean(r.entered); h.finish();
        var pr = b.domain("payment-rows"); for (PayRow r : s.rows) pr.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.row).writeInteger(r.headerLegacyId).writeInteger(r.companyLegacyId).writeString(r.kind.name()).writeInteger(r.row).writeInteger(r.invoiceNumber).writeString(r.invoiceCurrency).writeDecimal(canon(r.invoiceRate)).writeDecimal(canon(r.value)).writeDecimal(canon(r.rate)).addDebit(canon(r.value)); pr.finish();
        var a = b.domain("payment-default-accounts"); for (Account r : s.accounts) a.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.type).writeInteger(r.headerLegacyId).writeString(r.kind.name()).writeString(r.type).writeInteger(r.number); a.finish();
        var vh = b.domain("payment-vouchers"); for (VoucherHeader r : s.vouchers) vh.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.voucherKind).writeInteger(r.headerLegacyId).writeString(r.kind.name()).writeString(r.voucherKind).writeInteger(r.number).writeDate(r.date).writeString(r.description); vh.finish();
        var vr = b.domain("payment-voucher-rows"); for (VoucherLine r : s.voucherRows) vr.startRecord(r.kind + "/" + r.headerLegacyId + "/" + r.voucherKind + "/" + r.row).writeInteger(r.headerLegacyId).writeString(r.kind.name()).writeString(r.voucherKind).writeInteger(r.row).writeInteger(r.account).writeString(r.project).writeString(r.resultUnit).writeDecimal(canon(r.debit)).writeDecimal(canon(r.credit)).writeInstant(r.edited).writeString(r.signature).writeBoolean(r.crossed).writeBoolean(r.added).addDebit(canon(r.debit)).addCredit(canon(r.credit)); vr.finish();
        return b.finish();
    }

    private static String table(Kind k) { return k == Kind.IN ? "inpayment" : "outpayment"; }
    private static long key(int legacyId, Kind kind) { return ((long) legacyId << 1) | (kind == Kind.IN ? 0 : 1); }
    private static BigDecimal canon(BigDecimal v) { return v == null ? null : v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros(); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated payment key"); return r.getLong(1); } }
    private static <K> long req(Map<K, Long> m, K k) throws SQLException { Long v = m.get(k); if (v == null) throw new SQLException("Missing payment mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long payments, long rows, long defaultAccounts, long voucherRows, long daylightSavingGapAdjustments, long daylightSavingOverlapResolutions) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private record Header(int legacyId, int companyLegacyId, Kind kind, Integer number, java.time.LocalDate date, String text, boolean entered) {}
    private record PayRow(int headerLegacyId, int companyLegacyId, Kind kind, int row, Integer invoiceNumber, String invoiceCurrency, BigDecimal invoiceRate, BigDecimal value, BigDecimal rate) {}
    private record Account(int headerLegacyId, int companyLegacyId, Kind kind, String type, Integer number) {}
    private record VoucherHeader(int headerLegacyId, int companyLegacyId, Kind kind, String voucherKind, int number, java.time.LocalDate date, String description) {}
    private record VoucherLine(int headerLegacyId, int companyLegacyId, Kind kind, String voucherKind, int row, Integer account, String project, String resultUnit, BigDecimal debit, BigDecimal credit, Instant edited, String signature, boolean crossed, boolean added) {}
    private static final class Snapshot { final List<Header> headers = new ArrayList<>(); final List<PayRow> rows = new ArrayList<>(); final List<Account> accounts = new ArrayList<>(); final List<VoucherHeader> vouchers = new ArrayList<>(); final List<VoucherLine> voucherRows = new ArrayList<>(); long gaps, overlaps;
        void sort() { headers.sort(Comparator.comparing((Header r) -> r.kind).thenComparingInt(r -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); rows.sort(Comparator.comparing((PayRow r) -> r.kind).thenComparingInt(PayRow::headerLegacyId).thenComparingInt(PayRow::row)); accounts.sort(Comparator.comparing((Account r) -> r.kind).thenComparingInt(Account::headerLegacyId).thenComparing(Account::type)); vouchers.sort(Comparator.comparing((VoucherHeader r) -> r.kind).thenComparingInt(VoucherHeader::headerLegacyId).thenComparing(VoucherHeader::voucherKind)); voucherRows.sort(Comparator.comparing((VoucherLine r) -> r.kind).thenComparingInt(VoucherLine::headerLegacyId).thenComparing(VoucherLine::voucherKind).thenComparingInt(VoucherLine::row)); } }
}
