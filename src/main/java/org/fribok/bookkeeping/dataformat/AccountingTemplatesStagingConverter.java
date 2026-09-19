package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAutoDist;
import se.swedsoft.bookkeeping.data.SSAutoDistRow;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Converts company-scoped voucher templates and automatic distributions with ordered rows. */
public final class AccountingTemplatesStagingConverter {

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy, "legacy"));
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized, "normalized"), source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                normalized.rollback();
                throw new SQLException("Accounting-templates staging fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.templates.size(), source.templateRows.size(),
                    source.distributions.size(), source.distributionRows.size(),
                    source.gapAdjustments, source.overlapResolutions);
        } catch (SQLException | RuntimeException exception) {
            normalized.rollback();
            throw exception;
        }
    }

    public SemanticFingerprint fingerprintNormalized(Connection connection) throws SQLException {
        return fingerprint(readNormalized(connection));
    }

    private static Snapshot readLegacy(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        query(connection, "SELECT companyid,name,vouchertemplate FROM tbl_vouchertemplate "
                + "ORDER BY companyid,name", result -> {
            int company = result.getInt(1);
            String name = result.getString(2);
            SSVoucherTemplate template = (SSVoucherTemplate) result.getObject(3);
            if (!Objects.equals(name, template.getDescription())) {
                throw new SQLException("Voucher-template key/object mismatch for company " + company
                        + ": table=" + name + ", object=" + template.getDescription());
            }
            Instant modifiedAt = resolve(template.getLocalDateTime(), snapshot);
            snapshot.templates.add(new Template(company, name, template.getDescription(), modifiedAt));
            int rowNumber = 0;
            for (SSVoucherTemplateRow row : template.getRows()) {
                snapshot.templateRows.add(new TemplateRow(company, name, rowNumber++,
                        row.getAccountNr(), row.getDebet() != null));
            }
        });
        query(connection, "SELECT companyid,number,autodist FROM tbl_autodist "
                + "ORDER BY companyid,number,id", result -> {
            int company = result.getInt(1);
            int number = result.getInt(2);
            SSAutoDist distribution = (SSAutoDist) result.getObject(3);
            if (!Objects.equals(number, distribution.getNumber())) {
                throw new SQLException("Auto-distribution key/object mismatch for company " + company
                        + ": table=" + number + ", object=" + distribution.getNumber());
            }
            snapshot.distributions.add(new Distribution(company, number,
                    distribution.getDescription(), distribution.getAmount()));
            int rowNumber = 0;
            for (SSAutoDistRow row : distribution.getRows()) {
                snapshot.distributionRows.add(new DistributionRow(company, number, rowNumber++,
                        row.getAccountNr(), row.getDescription(), row.getPercentage(), row.getDebet(),
                        row.getCredit(), row.getProjectNr(), row.getResultUnitNr()));
            }
        });
        snapshot.sort();
        return snapshot;
    }

    private static Instant resolve(java.time.LocalDateTime value, Snapshot snapshot) {
        if (value == null) return null;
        var resolution = LegacySwedishTimeResolver.resolve(value);
        if (resolution.kind() == LegacySwedishTimeResolver.ResolutionKind.FORWARD_BY_GAP) {
            snapshot.gapAdjustments++;
        } else if (resolution.kind()
                == LegacySwedishTimeResolver.ResolutionKind.EARLIER_OFFSET_IN_OVERLAP) {
            snapshot.overlapResolutions++;
        }
        return resolution.instant();
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        for (Template row : snapshot.templates) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO voucher_template (company_id,name,description,modified_at)
                    SELECT id,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setString(1, row.name); statement.setString(2, row.description);
                statement.setObject(3, offset(row.modifiedAt)); statement.setInt(4, row.companyLegacyId);
                requireOne(statement, "voucher template", row.companyLegacyId, row.name);
            }
        }
        for (TemplateRow row : snapshot.templateRows) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO voucher_template_row
                      (company_id,template_name,row_number,account_number,debit)
                    SELECT id,?,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setString(1, row.templateName); statement.setInt(2, row.rowNumber);
                setInteger(statement, 3, row.accountNumber); statement.setBoolean(4, row.debit);
                statement.setInt(5, row.companyLegacyId);
                requireOne(statement, "voucher-template row", row.companyLegacyId, row.templateName);
            }
        }
        for (Distribution row : snapshot.distributions) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auto_distribution (company_id,number,description,amount)
                    SELECT id,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setInt(1, row.number); statement.setString(2, row.description);
                statement.setBigDecimal(3, row.amount); statement.setInt(4, row.companyLegacyId);
                requireOne(statement, "auto distribution", row.companyLegacyId,
                        Integer.toString(row.number));
            }
        }
        for (DistributionRow row : snapshot.distributionRows) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auto_distribution_row
                      (company_id,distribution_number,row_number,account_number,description,
                       percentage,debit,credit,project_number,result_unit_number)
                    SELECT id,?,?,?,?,?,?,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setInt(1, row.distributionNumber); statement.setInt(2, row.rowNumber);
                setInteger(statement, 3, row.accountNumber); statement.setString(4, row.description);
                statement.setBigDecimal(5, row.percentage); statement.setBigDecimal(6, row.debit);
                statement.setBigDecimal(7, row.credit); statement.setString(8, row.projectNumber);
                statement.setString(9, row.resultUnitNumber); statement.setInt(10, row.companyLegacyId);
                requireOne(statement, "auto-distribution row", row.companyLegacyId,
                        Integer.toString(row.distributionNumber));
            }
        }
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        query(connection, "SELECT c.legacy_id,t.name,t.description,t.modified_at FROM voucher_template t "
                + "JOIN company c ON c.id=t.company_id ORDER BY c.legacy_id,t.name", r ->
                snapshot.templates.add(new Template(r.getInt(1), r.getString(2), r.getString(3),
                        r.getObject(4) == null ? null : r.getObject(4, OffsetDateTime.class).toInstant())));
        query(connection, "SELECT c.legacy_id,r.template_name,r.row_number,r.account_number,r.debit "
                + "FROM voucher_template_row r JOIN company c ON c.id=r.company_id "
                + "ORDER BY c.legacy_id,r.template_name,r.row_number", r -> snapshot.templateRows.add(
                        new TemplateRow(r.getInt(1), r.getString(2), r.getInt(3),
                                nullableInteger(r, 4), r.getBoolean(5))));
        query(connection, "SELECT c.legacy_id,d.number,d.description,d.amount FROM auto_distribution d "
                + "JOIN company c ON c.id=d.company_id ORDER BY c.legacy_id,d.number", r ->
                snapshot.distributions.add(new Distribution(r.getInt(1), r.getInt(2),
                        r.getString(3), r.getBigDecimal(4))));
        query(connection, "SELECT c.legacy_id,r.distribution_number,r.row_number,r.account_number,"
                + "r.description,r.percentage,r.debit,r.credit,r.project_number,r.result_unit_number "
                + "FROM auto_distribution_row r JOIN company c ON c.id=r.company_id "
                + "ORDER BY c.legacy_id,r.distribution_number,r.row_number", r ->
                snapshot.distributionRows.add(new DistributionRow(r.getInt(1), r.getInt(2),
                        r.getInt(3), nullableInteger(r, 4), r.getString(5), r.getBigDecimal(6),
                        r.getBigDecimal(7), r.getBigDecimal(8), r.getString(9), r.getString(10))));
        snapshot.sort();
        return snapshot;
    }

    private static SemanticFingerprint fingerprint(Snapshot snapshot) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        var templates = builder.domain("voucher-templates");
        for (Template r : snapshot.templates) templates.startRecord(r.companyLegacyId + "/" + r.name)
                .writeInteger(r.companyLegacyId).writeString(r.name).writeString(r.description)
                .writeInstant(r.modifiedAt);
        templates.finish();
        var templateRows = builder.domain("voucher-template-rows");
        for (TemplateRow r : snapshot.templateRows) templateRows.startRecord(
                        r.companyLegacyId + "/" + r.templateName + "/" + r.rowNumber)
                .writeInteger(r.companyLegacyId).writeString(r.templateName).writeInteger(r.rowNumber)
                .writeInteger(r.accountNumber).writeBoolean(r.debit);
        templateRows.finish();
        var distributions = builder.domain("auto-distributions");
        for (Distribution r : snapshot.distributions) distributions.startRecord(
                        r.companyLegacyId + "/" + r.number).writeInteger(r.companyLegacyId)
                .writeInteger(r.number).writeString(r.description).writeDecimal(canonical(r.amount));
        distributions.finish();
        var rows = builder.domain("auto-distribution-rows");
        for (DistributionRow r : snapshot.distributionRows) rows.startRecord(
                        r.companyLegacyId + "/" + r.distributionNumber + "/" + r.rowNumber)
                .writeInteger(r.companyLegacyId).writeInteger(r.distributionNumber)
                .writeInteger(r.rowNumber).writeInteger(r.accountNumber).writeString(r.description)
                .writeDecimal(canonical(r.percentage)).writeDecimal(canonical(r.debit))
                .writeDecimal(canonical(r.credit)).writeString(r.projectNumber)
                .writeString(r.resultUnitNumber);
        rows.finish();
        return builder.finish();
    }

    private static BigDecimal canonical(BigDecimal value) {
        return value == null ? null : value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
    private static OffsetDateTime offset(Instant value) { return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static void setInteger(PreparedStatement s,int i,Integer v)throws SQLException{if(v==null)s.setNull(i,java.sql.Types.INTEGER);else s.setInt(i,v);}
    private static Integer nullableInteger(ResultSet r,int i)throws SQLException{int v=r.getInt(i);return r.wasNull()?null:v;}
    private static void requireOne(PreparedStatement s,String type,int company,String key)throws SQLException{if(s.executeUpdate()!=1)throw new SQLException("Missing normalized company "+company+" for "+type+" "+key);}
    private static void query(Connection c,String sql,SqlRow f)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next())f.accept(r);}}

    public record ConversionResult(SemanticFingerprint sourceFingerprint,SemanticFingerprint destinationFingerprint,long templates,long templateRows,long distributions,long distributionRows,long daylightSavingGapAdjustments,long daylightSavingOverlapResolutions){}
    @FunctionalInterface private interface SqlRow{void accept(ResultSet r)throws SQLException;}
    private record Template(int companyLegacyId,String name,String description,Instant modifiedAt){}
    private record TemplateRow(int companyLegacyId,String templateName,int rowNumber,Integer accountNumber,boolean debit){}
    private record Distribution(int companyLegacyId,int number,String description,BigDecimal amount){}
    private record DistributionRow(int companyLegacyId,int distributionNumber,int rowNumber,Integer accountNumber,String description,BigDecimal percentage,BigDecimal debit,BigDecimal credit,String projectNumber,String resultUnitNumber){}
    private static final class Snapshot{final List<Template>templates=new ArrayList<>();final List<TemplateRow>templateRows=new ArrayList<>();final List<Distribution>distributions=new ArrayList<>();final List<DistributionRow>distributionRows=new ArrayList<>();long gapAdjustments,overlapResolutions;void sort(){templates.sort(Comparator.comparingInt(Template::companyLegacyId).thenComparing(Template::name));templateRows.sort(Comparator.comparingInt(TemplateRow::companyLegacyId).thenComparing(TemplateRow::templateName).thenComparingInt(TemplateRow::rowNumber));distributions.sort(Comparator.comparingInt(Distribution::companyLegacyId).thenComparingInt(Distribution::number));distributionRows.sort(Comparator.comparingInt(DistributionRow::companyLegacyId).thenComparingInt(DistributionRow::distributionNumber).thenComparingInt(DistributionRow::rowNumber));}}
}
