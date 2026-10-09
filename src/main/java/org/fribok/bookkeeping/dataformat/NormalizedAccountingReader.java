package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads the accounting core from a normalized catalog into the existing domain objects. */
public final class NormalizedAccountingReader {

    public List<SSNewCompany> companies(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        List<SSNewCompany> companies = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT legacy_id,name,corporate_id,vat_number,"
                             + "tax_rate_1,tax_rate_2,tax_rate_3,rounding_off FROM company ORDER BY legacy_id")) {
            while (result.next()) {
                SSNewCompany company = new SSNewCompany();
                company.setId(result.getInt(1));
                company.setName(result.getString(2));
                company.setCorporateID(result.getString(3));
                company.setVATNumber(result.getString(4));
                java.math.BigDecimal taxRate1 = result.getBigDecimal(5);
                if (taxRate1 != null) {
                    company.setTaxrate1(taxRate1);
                }
                java.math.BigDecimal taxRate2 = result.getBigDecimal(6);
                if (taxRate2 != null) {
                    company.setTaxrate2(taxRate2);
                }
                java.math.BigDecimal taxRate3 = result.getBigDecimal(7);
                if (taxRate3 != null) {
                    company.setTaxrate3(taxRate3);
                }
                company.setRoundingOff(result.getBoolean(8));
                companies.add(company);
            }
        }
        return companies;
    }

    public List<SSNewAccountingYear> years(Connection connection, int companyLegacyId)
            throws SQLException {
        List<SSNewAccountingYear> years = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT y.legacy_id,y.starts_on,y.ends_on,p.legacy_id,p.name,p.base_name,"
                        + "p.assessment_year,p.plan_type FROM accounting_year y "
                        + "JOIN company c ON c.id=y.company_id "
                        + "LEFT JOIN account_plan p ON p.accounting_year_id=y.id "
                        + "WHERE c.legacy_id=? ORDER BY y.legacy_id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSNewAccountingYear year = new SSNewAccountingYear();
                    year.setId(result.getInt(1));
                    year.setLocalFrom(result.getObject(2, java.time.LocalDate.class));
                    year.setLocalTo(result.getObject(3, java.time.LocalDate.class));
                    SSAccountPlan plan = new SSAccountPlan();
                    int planId = result.getInt(4);
                    if (!result.wasNull()) plan.setId(planId);
                    plan.setName(result.getString(5));
                    plan.setBaseName(result.getString(6));
                    plan.setAssessementYear(result.getString(7));
                    plan.setType(result.getString(8));
                    year.setAccountPlan(plan);
                    years.add(year);
                }
            }
        }
        return years;
    }

    public List<SSAccount> accounts(Connection connection, int yearLegacyId) throws SQLException {
        List<SSAccount> accounts = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT a.number,a.description,a.sru_code,a.vat_code,a.report_code,a.active,"
                        + "a.project_required,a.result_unit_required FROM account a "
                        + "JOIN accounting_year y ON y.id=a.accounting_year_id "
                        + "WHERE y.legacy_id=? ORDER BY a.number")) {
            statement.setInt(1, yearLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSAccount account = new SSAccount(result.getInt(1));
                    account.setDescription(result.getString(2));
                    account.setSRUCode(result.getString(3));
                    account.setVATCode(result.getString(4));
                    account.setReportCode(result.getString(5));
                    account.setActive(result.getBoolean(6));
                    account.setProjectRequired(result.getBoolean(7));
                    account.setResultUnitRequired(result.getBoolean(8));
                    accounts.add(account);
                }
            }
        }
        return accounts;
    }

    /** Reads the opening balances of a year keyed by account number. */
    public java.util.Map<Integer, java.math.BigDecimal> openingBalances(Connection connection,
                                                                        int yearLegacyId)
            throws SQLException {
        java.util.Map<Integer, java.math.BigDecimal> balances = new java.util.LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT a.number,o.amount FROM opening_balance o "
                        + "JOIN account a ON a.id=o.account_id AND a.accounting_year_id=o.accounting_year_id "
                        + "JOIN accounting_year y ON y.id=o.accounting_year_id "
                        + "WHERE y.legacy_id=? ORDER BY a.number")) {
            statement.setInt(1, yearLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    balances.put(result.getInt(1), result.getBigDecimal(2));
                }
            }
        }
        return balances;
    /** Reads the voucher templates of a company with their ordered rows. */
    public List<se.swedsoft.bookkeeping.data.SSVoucherTemplate> voucherTemplates(
            Connection connection, int companyLegacyId) throws SQLException {
        List<se.swedsoft.bookkeeping.data.SSVoucherTemplate> templates = new ArrayList<>();
        java.util.Map<String, se.swedsoft.bookkeeping.data.SSVoucherTemplate> byName =
                new java.util.LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT t.name,t.description,t.modified_at FROM voucher_template t "
                        + "JOIN company c ON c.id=t.company_id "
                        + "WHERE c.legacy_id=? ORDER BY t.name")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    se.swedsoft.bookkeeping.data.SSVoucherTemplate template =
                            new se.swedsoft.bookkeeping.data.SSVoucherTemplate();
                    template.setDescription(result.getString(2));
                    java.time.OffsetDateTime modified =
                            result.getObject(3, java.time.OffsetDateTime.class);
                    if (modified != null) {
                        template.setLocalDateTime(modified.atZoneSameInstant(
                                LegacySwedishTimeResolver.LEGACY_ZONE).toLocalDateTime());
                    }
                    byName.put(result.getString(1), template);
                    templates.add(template);
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT r.template_name,r.account_number,r.debit FROM voucher_template_row r "
                        + "JOIN company c ON c.id=r.company_id "
                        + "WHERE c.legacy_id=? "
                        + "ORDER BY r.template_name,r.row_number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    se.swedsoft.bookkeeping.data.SSVoucherTemplate template =
                            byName.get(result.getString(1));
                    se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow row =
                            new se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow();
                    row.setAccountNr(result.getObject(2, Integer.class));
                    // The debit flag selects which side carries the (zero) placeholder.
                    if (result.getBoolean(3)) {
                        row.setDebet(java.math.BigDecimal.ZERO);
                    } else {
                        row.setCredit(java.math.BigDecimal.ZERO);
                    }
                    template.getRows().add(row);
                }
            }
        }
        return templates;
    }

    public List<SSVoucher> vouchers(Connection connection, int yearLegacyId) throws SQLException {
        List<SSVoucher> vouchers = new ArrayList<>();
        java.util.Map<Long, SSVoucher> byId = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Long> corrects = new java.util.HashMap<>();
        java.util.Map<Long, Long> correctedBy = new java.util.HashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT v.id,v.number,v.voucher_date,v.description,v.corrects_voucher_id,"
                        + "v.corrected_by_voucher_id FROM voucher v "
                        + "JOIN accounting_year y ON y.id=v.accounting_year_id "
                        + "WHERE y.legacy_id=? ORDER BY v.number")) {
            statement.setInt(1, yearLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    SSVoucher voucher = new SSVoucher(result.getInt(2));
                    voucher.setLocalDate(result.getObject(3, java.time.LocalDate.class));
                    voucher.setDescription(result.getString(4));
                    long c = result.getLong(5); if (!result.wasNull()) corrects.put(id, c);
                    long b = result.getLong(6); if (!result.wasNull()) correctedBy.put(id, b);
                    byId.put(id, voucher);
                    vouchers.add(voucher);
                }
            }
        }
        for (var entry : byId.entrySet()) {
            SSVoucher voucher = entry.getValue();
            Long c = corrects.get(entry.getKey());
            if (c != null) voucher.setCorrects(byId.get(c));
            Long b = correctedBy.get(entry.getKey());
            if (b != null) voucher.setCorrectedBy(byId.get(b));
        }
        try (var statement = connection.prepareStatement(
                "SELECT r.voucher_id,r.row_number,a.number,r.project_number,"
                        + "r.result_unit_number,r.debit,r.credit,r.edited_at,r.edited_signature,"
                        + "r.crossed,r.added FROM voucher_row r "
                        + "JOIN voucher v ON v.id=r.voucher_id "
                        + "JOIN accounting_year y ON y.id=v.accounting_year_id "
                        + "LEFT JOIN account a ON a.id=r.account_id "
                        + "WHERE y.legacy_id=? ORDER BY v.number,r.row_number")) {
            statement.setInt(1, yearLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSVoucher voucher = byId.get(result.getLong(1));
                    SSVoucherRow row = new SSVoucherRow();
                    row.setAccountNr(result.getObject(3, Integer.class));
                    row.setProjectNr(result.getString(4));
                    row.setResultUnitNr(result.getString(5));
                    row.setDebet(result.getBigDecimal(6));
                    row.setCredit(result.getBigDecimal(7));
                    java.time.OffsetDateTime edited = result.getObject(8, java.time.OffsetDateTime.class);
                    if (edited != null) {
                        row.setLocalEditedDate(edited.atZoneSameInstant(
                                LegacySwedishTimeResolver.LEGACY_ZONE).toLocalDateTime());
                    }
                    row.setEditedSignature(result.getString(9));
                    row.setCrossed(result.getBoolean(10));
                    row.setAdded(result.getBoolean(11));
                    voucher.getRows().add(row);
                }
            }
        }
        return vouchers;
    }
}
