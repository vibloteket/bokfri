package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.gui.ownreport.util.SSOwnReportAccountRow;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Converts own reports with ordered headings, account-row snapshots, and monthly budgets. */
public final class OwnReportStagingConverter {
    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy));
        SemanticFingerprint sf = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized), source);
            Snapshot dest = readNormalized(normalized);
            SemanticFingerprint df = fingerprint(dest);
            List<String> d = sf.differences(df);
            if (!d.isEmpty()) { normalized.rollback(); throw new SQLException("Own-report staging fingerprint mismatch: " + String.join("; ", d)); }
            normalized.commit();
            return new ConversionResult(sf, df, source.reports.size(), source.headings.size(), source.accountRows.size(), source.budgets.size());
        } catch (SQLException | RuntimeException e) { normalized.rollback(); throw e; }
    }

    public SemanticFingerprint fingerprintNormalized(Connection c) throws SQLException { return fingerprint(readNormalized(c)); }

    private static Snapshot readLegacy(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT id,companyid,ownreport FROM tbl_ownreport ORDER BY companyid,id", r -> {
            int id = r.getInt(1), company = r.getInt(2);
            SSOwnReport x = (SSOwnReport) r.getObject(3);
            s.reports.add(new Report(id, company, x.getId(), x.getName(), x.getProjectNr(), x.getResultUnitNr()));
            int hr = 0;
            for (SSOwnReportRow row : x.getHeadings()) {
                s.headings.add(new Heading(id, hr, row.getType() == null ? null : row.getType().name(), row.getHeading()));
                int ar = 0;
                for (SSOwnReportAccountRow accountRow : row.getAccountRows()) {
                    SSAccount account = accountRow.getAccount();
                    s.accountRows.add(new AccountRow(id, hr, ar,
                            account == null ? null : account.getNumber(), account == null ? null : account.getDescription(),
                            account == null ? null : account.getSRUCode(), account == null ? null : account.getVATCode(),
                            account == null ? null : account.getReportCode(),
                            account == null ? null : account.isActive(), account == null ? null : account.isProjectRequired(),
                            account == null ? null : account.isResultUnitRequired()));
                    if (accountRow.getBudget() != null) {
                        final int headingRow = hr, accountRowIndex = ar;
                        accountRow.getBudget().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey(Comparator.comparing(SSMonth::getLocalFrom, Comparator.nullsFirst(java.time.LocalDate::compareTo))))
                                .forEach(e -> s.budgets.add(new Budget(id, headingRow, accountRowIndex, e.getKey().getLocalFrom(), e.getKey().getLocalTo(), e.getValue())));
                    }
                    ar++;
                }
                hr++;
            }
        });
        s.sort(); return s;
    }

    private static void writeNormalized(Connection c, Snapshot s) throws SQLException {
        Map<Integer, Long> ids = new HashMap<>();
        for (Report r : s.reports) { try (PreparedStatement p = c.prepareStatement("INSERT INTO own_report (legacy_id,company_id,number,name,project_number,result_unit_number) SELECT ?,id,?,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) { p.setInt(1, r.legacyId); setInteger(p, 2, r.number); p.setString(3, r.name); p.setString(4, r.project); p.setString(5, r.resultUnit); p.setInt(6, r.companyLegacyId); if (p.executeUpdate() != 1) throw new SQLException("Missing company " + r.companyLegacyId); ids.put(r.legacyId, key(p)); } }
        for (Heading r : s.headings) { try (PreparedStatement p = c.prepareStatement("INSERT INTO own_report_heading VALUES (?,?,?,?)")) { p.setLong(1, req(ids, r.reportLegacyId)); p.setInt(2, r.row); p.setString(3, r.type); p.setString(4, r.heading); p.executeUpdate(); } }
        for (AccountRow r : s.accountRows) { try (PreparedStatement p = c.prepareStatement("INSERT INTO own_report_account_row VALUES (?,?,?,?,?,?,?,?,?,?,?)")) { p.setLong(1, req(ids, r.reportLegacyId)); p.setInt(2, r.headingRow); p.setInt(3, r.row); setInteger(p, 4, r.accountNumber); p.setString(5, r.description); p.setString(6, r.sru); p.setString(7, r.vat); p.setString(8, r.reportCode); setBoolean(p, 9, r.active); setBoolean(p, 10, r.projectRequired); setBoolean(p, 11, r.resultUnitRequired); p.executeUpdate(); } }
        for (Budget r : s.budgets) { try (PreparedStatement p = c.prepareStatement("INSERT INTO own_report_account_budget VALUES (?,?,?,?,?,?)")) { p.setLong(1, req(ids, r.reportLegacyId)); p.setInt(2, r.headingRow); p.setInt(3, r.accountRow); p.setObject(4, r.periodStart); p.setObject(5, r.periodEnd); p.setBigDecimal(6, r.amount); p.executeUpdate(); } }
    }

    private static Snapshot readNormalized(Connection c) throws SQLException {
        Snapshot s = new Snapshot();
        query(c, "SELECT r.legacy_id,co.legacy_id,r.number,r.name,r.project_number,r.result_unit_number FROM own_report r JOIN company co ON co.id=r.company_id ORDER BY co.legacy_id,r.legacy_id", r -> s.reports.add(new Report(r.getInt(1), r.getInt(2), nullableInteger(r, 3), r.getString(4), r.getString(5), r.getString(6))));
        query(c, "SELECT r.legacy_id,h.row_number,h.heading_type,h.heading FROM own_report_heading h JOIN own_report r ON r.id=h.own_report_id ORDER BY r.legacy_id,h.row_number", r -> s.headings.add(new Heading(r.getInt(1), r.getInt(2), r.getString(3), r.getString(4))));
        query(c, "SELECT r.legacy_id,x.heading_row_number,x.row_number,x.account_number,x.description,x.sru_code,x.vat_code,x.report_code,x.active,x.project_required,x.result_unit_required FROM own_report_account_row x JOIN own_report r ON r.id=x.own_report_id ORDER BY r.legacy_id,x.heading_row_number,x.row_number", r -> s.accountRows.add(new AccountRow(r.getInt(1), r.getInt(2), r.getInt(3), nullableInteger(r, 4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), nullableBoolean(r, 9), nullableBoolean(r, 10), nullableBoolean(r, 11))));
        query(c, "SELECT r.legacy_id,b.heading_row_number,b.account_row_number,b.period_start,b.period_end,b.amount FROM own_report_account_budget b JOIN own_report r ON r.id=b.own_report_id ORDER BY r.legacy_id,b.heading_row_number,b.account_row_number,b.period_start", r -> s.budgets.add(new Budget(r.getInt(1), r.getInt(2), r.getInt(3), r.getObject(4, java.time.LocalDate.class), r.getObject(5, java.time.LocalDate.class), r.getBigDecimal(6))));
        s.sort(); return s;
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var d = b.domain("own-reports"); for (Report r : s.reports) d.startRecord(r.companyLegacyId + "/" + r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId).writeInteger(r.number).writeString(r.name).writeString(r.project).writeString(r.resultUnit); d.finish();
        var h = b.domain("own-report-headings"); for (Heading r : s.headings) h.startRecord(r.reportLegacyId + "/" + r.row).writeInteger(r.reportLegacyId).writeInteger(r.row).writeString(r.type).writeString(r.heading); h.finish();
        var ar = b.domain("own-report-account-rows"); for (AccountRow r : s.accountRows) ar.startRecord(r.reportLegacyId + "/" + r.headingRow + "/" + r.row).writeInteger(r.reportLegacyId).writeInteger(r.headingRow).writeInteger(r.row).writeInteger(r.accountNumber).writeString(r.description).writeString(r.sru).writeString(r.vat).writeString(r.reportCode).writeBoolean(r.active).writeBoolean(r.projectRequired).writeBoolean(r.resultUnitRequired); ar.finish();
        var bg = b.domain("own-report-account-budgets"); for (Budget r : s.budgets) bg.startRecord(r.reportLegacyId + "/" + r.headingRow + "/" + r.accountRow + "/" + r.periodStart).writeInteger(r.reportLegacyId).writeInteger(r.headingRow).writeInteger(r.accountRow).writeDate(r.periodStart).writeDate(r.periodEnd).writeDecimal(canon(r.amount)); bg.finish();
        return b.finish();
    }

    private static BigDecimal canon(BigDecimal v) { return v == null ? null : v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros(); }
    private static void setInteger(PreparedStatement p, int i, Integer v) throws SQLException { if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v); }
    private static void setBoolean(PreparedStatement p, int i, Boolean v) throws SQLException { if (v == null) p.setNull(i, Types.BOOLEAN); else p.setBoolean(i, v); }
    private static Integer nullableInteger(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    private static Boolean nullableBoolean(ResultSet r, int i) throws SQLException { boolean v = r.getBoolean(i); return r.wasNull() ? null : v; }
    private static long key(PreparedStatement p) throws SQLException { try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated own-report key"); return r.getLong(1); } }
    private static <K> long req(Map<K, Long> m, K k) throws SQLException { Long v = m.get(k); if (v == null) throw new SQLException("Missing own-report mapping " + k); return v; }
    private static void query(Connection c, String sql, SqlRow f) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) f.accept(r); } }

    public record ConversionResult(SemanticFingerprint sourceFingerprint, SemanticFingerprint destinationFingerprint, long reports, long headings, long accountRows, long budgets) {}
    @FunctionalInterface private interface SqlRow { void accept(ResultSet r) throws SQLException; }
    private record Report(int legacyId, int companyLegacyId, Integer number, String name, String project, String resultUnit) {}
    private record Heading(int reportLegacyId, int row, String type, String heading) {}
    private record AccountRow(int reportLegacyId, int headingRow, int row, Integer accountNumber, String description, String sru, String vat, String reportCode, Boolean active, Boolean projectRequired, Boolean resultUnitRequired) {}
    private record Budget(int reportLegacyId, int headingRow, int accountRow, java.time.LocalDate periodStart, java.time.LocalDate periodEnd, BigDecimal amount) {}
    private static final class Snapshot { final List<Report> reports = new ArrayList<>(); final List<Heading> headings = new ArrayList<>(); final List<AccountRow> accountRows = new ArrayList<>(); final List<Budget> budgets = new ArrayList<>(); void sort() { reports.sort(Comparator.comparingInt((Report r) -> r.companyLegacyId).thenComparingInt(r -> r.legacyId)); headings.sort(Comparator.comparingInt(Heading::reportLegacyId).thenComparingInt(Heading::row)); accountRows.sort(Comparator.comparingInt(AccountRow::reportLegacyId).thenComparingInt(AccountRow::headingRow).thenComparingInt(AccountRow::row)); budgets.sort(Comparator.comparingInt(Budget::reportLegacyId).thenComparingInt(Budget::headingRow).thenComparingInt(Budget::accountRow).thenComparing(Budget::periodStart, Comparator.nullsFirst(java.time.LocalDate::compareTo))); } }
}
