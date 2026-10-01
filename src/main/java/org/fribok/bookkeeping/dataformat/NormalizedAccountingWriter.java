package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Writes the accounting core into normalized tables; the caller controls the transaction. */
public final class NormalizedAccountingWriter {
    private final java.time.Clock clock;

    public NormalizedAccountingWriter(java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Inserts a company and returns the generated key. */
    public long addCompany(Connection connection, SSNewCompany company) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO company (legacy_id,name,corporate_id,vat_number,"
                        + "tax_rate_1,tax_rate_2,tax_rate_3,rounding_off) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, company.getId());
            statement.setString(2, company.getName());
            statement.setString(3, company.getCorporateID());
            statement.setString(4, company.getVATNumber());
            statement.setBigDecimal(5, company.getTaxRate1());
            statement.setBigDecimal(6, company.getTaxRate2());
            statement.setBigDecimal(7, company.getTaxRate3());
            statement.setBoolean(8, company.isRoundingOff());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    /** Updates the company identified by its legacy id. */
    public void updateCompany(Connection connection, SSNewCompany company) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE company SET name=?,corporate_id=?,vat_number=?,"
                        + "tax_rate_1=?,tax_rate_2=?,tax_rate_3=?,rounding_off=? WHERE legacy_id=?")) {
            statement.setString(1, company.getName());
            statement.setString(2, company.getCorporateID());
            statement.setString(3, company.getVATNumber());
            statement.setBigDecimal(4, company.getTaxRate1());
            statement.setBigDecimal(5, company.getTaxRate2());
            statement.setBigDecimal(6, company.getTaxRate3());
            statement.setBoolean(7, company.isRoundingOff());
            statement.setInt(8, company.getId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + company.getId());
            }
        }
    }

    /** Deletes the company and everything beneath it in dependency order. */
    public void deleteCompany(Connection connection, int companyLegacyId) throws SQLException {
        long companyId = companyId(connection, companyLegacyId);
        deleteWhere(connection,
                "DELETE FROM voucher_row WHERE voucher_id IN "
                        + "(SELECT v.id FROM voucher v JOIN accounting_year y ON y.id=v.accounting_year_id "
                        + "WHERE y.company_id=?)", companyId);
        deleteWhere(connection,
                "DELETE FROM voucher WHERE accounting_year_id IN "
                        + "(SELECT id FROM accounting_year WHERE company_id=?)", companyId);
        deleteWhere(connection,
                "DELETE FROM budget_entry WHERE accounting_year_id IN "
                        + "(SELECT id FROM accounting_year WHERE company_id=?)", companyId);
        deleteWhere(connection,
                "DELETE FROM opening_balance WHERE accounting_year_id IN "
                        + "(SELECT id FROM accounting_year WHERE company_id=?)", companyId);
        deleteWhere(connection,
                "DELETE FROM account WHERE accounting_year_id IN "
                        + "(SELECT id FROM accounting_year WHERE company_id=?)", companyId);
        deleteWhere(connection,
                "DELETE FROM account_plan WHERE accounting_year_id IN "
                        + "(SELECT id FROM accounting_year WHERE company_id=?)", companyId);
        deleteWhere(connection, "DELETE FROM accounting_year WHERE company_id=?", companyId);
        deleteWhere(connection, "DELETE FROM company_address WHERE company_id=?", companyId);
        deleteWhere(connection, "DELETE FROM company_standard_text WHERE company_id=?", companyId);
        deleteWhere(connection, "DELETE FROM company_default_account WHERE company_id=?", companyId);
        deleteWhere(connection, "DELETE FROM company_auto_increment WHERE company_id=?", companyId);
        deleteWhere(connection, "DELETE FROM company WHERE id=?", companyId, true);
    }

    private static void deleteWhere(Connection connection, String sql, long id)
            throws SQLException {
        deleteWhere(connection, sql, id, false);
    }

    private static void deleteWhere(Connection connection, String sql, long id, boolean required)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            int deleted = statement.executeUpdate();
            if (required && deleted != 1) {
                throw new SQLException("Expected to delete one row, got " + deleted);
            }
        }
    }

    /** Inserts an accounting year with its plan and accounts; returns the generated key. */
    public long addAccountingYear(Connection connection, int companyLegacyId,
                                  SSNewAccountingYear year) throws SQLException {
        long companyId = companyId(connection, companyLegacyId);
        long yearId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounting_year (legacy_id,company_id,starts_on,ends_on) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, year.getId());
            statement.setLong(2, companyId);
            statement.setObject(3, year.getLocalFrom());
            statement.setObject(4, year.getLocalTo());
            statement.executeUpdate();
            yearId = generatedKey(statement);
        }
        SSAccountPlan plan = year.getAccountPlan();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_plan (accounting_year_id,legacy_id,name,base_name,"
                        + "assessment_year,plan_type) VALUES (?,?,?,?,?,?)")) {
            statement.setLong(1, yearId);
            if (plan.getId() == null) statement.setNull(2, java.sql.Types.INTEGER);
            else statement.setInt(2, plan.getId());
            statement.setString(3, plan.getName());
            statement.setString(4, plan.getBaseName());
            statement.setString(5, plan.getAssessementYear());
            statement.setString(6, plan.getType() == null ? null : plan.getType().getName());
            statement.executeUpdate();
        }
        for (SSAccount account : plan.getAccounts()) {
            addAccount(connection, yearId, account);
        }
        return yearId;
    }

    /** Inserts an account into the year identified by its generated key. */
    public void addAccount(Connection connection, long yearGeneratedId, SSAccount account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account (accounting_year_id,number,description,sru_code,vat_code,"
                        + "report_code,active,project_required,result_unit_required) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, yearGeneratedId);
            statement.setInt(2, account.getNumber());
            statement.setString(3, account.getDescription());
            statement.setString(4, account.getSRUCode());
            statement.setString(5, account.getVATCode());
            statement.setString(6, account.getReportCode());
            statement.setBoolean(7, account.isActive());
            statement.setBoolean(8, account.isProjectRequired());
            statement.setBoolean(9, account.isResultUnitRequired());
            statement.executeUpdate();
        }
    }

    /** Inserts a voucher with its ordered rows into the year; returns the generated key. */
    public long addVoucher(Connection connection, int yearLegacyId, SSVoucher voucher)
            throws SQLException {
        long yearId = yearId(connection, yearLegacyId);
        long voucherId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO voucher (accounting_year_id,number,voucher_date,description) "
                        + "VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, yearId);
            statement.setInt(2, voucher.getNumber());
            statement.setObject(3, voucher.getLocalDate());
            statement.setString(4, voucher.getDescription());
            statement.executeUpdate();
            voucherId = generatedKey(statement);
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            addVoucherRow(connection, voucherId, yearId, rowNumber++, row);
        }
        return voucherId;
    }

    private void addVoucherRow(Connection connection, long voucherId, long yearId, int rowNumber,
                               SSVoucherRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO voucher_row (voucher_id,accounting_year_id,row_number,account_id,"
                        + "project_number,result_unit_number,debit,credit,edited_at,edited_signature,"
                        + "crossed,added) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, voucherId);
            statement.setLong(2, yearId);
            statement.setInt(3, rowNumber);
            if (row.getAccountNr() == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, accountId(connection, yearId, row.getAccountNr()));
            }
            statement.setString(5, row.getProjectNr());
            statement.setString(6, row.getResultUnitNr());
            statement.setBigDecimal(7, row.getDebet());
            statement.setBigDecimal(8, row.getCredit());
            java.time.LocalDateTime edited = row.getLocalEditedDate();
            statement.setObject(9, edited == null ? null
                    : OffsetDateTime.ofInstant(LegacySwedishTimeResolver.resolve(edited).instant(),
                            ZoneOffset.UTC));
            statement.setString(10, row.getEditedSignature());
            statement.setBoolean(11, row.isCrossed());
            statement.setBoolean(12, row.isAdded());
            statement.executeUpdate();
        }
    }

    /** Updates a voucher's header and replaces its rows. */
    public void updateVoucher(Connection connection, int yearLegacyId, SSVoucher voucher)
            throws SQLException {
        long yearId = yearId(connection, yearLegacyId);
        long voucherId = voucherId(connection, yearId, voucher.getNumber());
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE voucher SET voucher_date=?,description=? WHERE id=?")) {
            statement.setObject(1, voucher.getLocalDate());
            statement.setString(2, voucher.getDescription());
            statement.setLong(3, voucherId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM voucher_row WHERE voucher_id=?")) {
            statement.setLong(1, voucherId);
            statement.executeUpdate();
        }
        int rowNumber = 0;
        for (SSVoucherRow row : voucher.getRows()) {
            addVoucherRow(connection, voucherId, yearId, rowNumber++, row);
        }
    }

    /** Deletes the voucher identified by year and number, including its rows. */
    public void deleteVoucher(Connection connection, int yearLegacyId, int number)
            throws SQLException {
        long yearId = yearId(connection, yearLegacyId);
        long voucherId = voucherId(connection, yearId, number);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM voucher_row WHERE voucher_id=?")) {
            statement.setLong(1, voucherId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM voucher WHERE id=?")) {
            statement.setLong(1, voucherId);
            statement.executeUpdate();
        }
    }

    private long accountId(Connection connection, long yearId, int accountNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM account WHERE accounting_year_id=? AND number=?")) {
            statement.setLong(1, yearId);
            statement.setInt(2, accountNumber);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No account " + accountNumber + " in year id " + yearId);
                }
                return result.getLong(1);
            }
        }
    }

    private long companyId(Connection connection, int legacyId) throws SQLException {
        return requireId(connection, "company", legacyId);
    }

    private long yearId(Connection connection, int legacyId) throws SQLException {
        return requireId(connection, "accounting_year", legacyId);
    }

    private long voucherId(Connection connection, long yearId, int number) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM voucher WHERE accounting_year_id=? AND number=?")) {
            statement.setLong(1, yearId);
            statement.setInt(2, number);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No voucher " + number + " in year id " + yearId);
                }
                return result.getLong(1);
            }
        }
    }

    private static long requireId(Connection connection, String table, int legacyId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE legacy_id=?")) {
            statement.setInt(1, legacyId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No " + table + " with legacy id " + legacyId);
                }
                return result.getLong(1);
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.getGeneratedKeys()) {
            if (!result.next()) throw new SQLException("Missing generated key");
            return result.getLong(1);
        }
    }
}
