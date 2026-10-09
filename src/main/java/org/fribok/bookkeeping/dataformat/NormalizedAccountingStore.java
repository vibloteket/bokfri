package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Application-facing facade over the normalized accounting core with an SSDB-shaped API.
 *
 * <p>Every method works on the caller's connection and never manages the global SSDB singleton.
 * The caller owns transaction boundaries; each method leaves commit/rollback to the caller.
 */
public final class NormalizedAccountingStore implements AutoCloseable {
    private final Connection connection;
    private final NormalizedAccountingReader reader = new NormalizedAccountingReader();
    private final NormalizedAccountingWriter writer;

    private SSNewCompany currentCompany;
    private SSNewAccountingYear currentYear;

    public NormalizedAccountingStore(Connection connection, java.time.Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.writer = new NormalizedAccountingWriter(clock);
    }

    public List<SSNewCompany> getCompanies() throws SQLException {
        return reader.companies(connection);
    }

    public void setCurrentCompany(SSNewCompany company) {
        currentCompany = company;
        currentYear = null;
    }

    public SSNewCompany getCurrentCompany() {
        return currentCompany;
    }

    public List<SSNewAccountingYear> getYears() throws SQLException {
        requireCompany();
        return reader.years(connection, currentCompany.getId());
    }

    public void setCurrentYear(SSNewAccountingYear year) {
        currentYear = year;
    }

    public SSNewAccountingYear getCurrentYear() {
        return currentYear;
    }

    public List<SSAccount> getAccounts() throws SQLException {
        requireYear();
        return reader.accounts(connection, currentYear.getId());
    }

    /** Lists the accounts of an explicit year, ignoring the current-year selection. */
    public List<SSAccount> getAccounts(SSNewAccountingYear year) throws SQLException {
        return reader.accounts(connection, year.getId());
    }

    public List<SSVoucher> getVouchers() throws SQLException {
        requireYear();
        return reader.vouchers(connection, currentYear.getId());
    }

    /** Lists the vouchers of an explicit year, ignoring the current-year selection. */
    public List<SSVoucher> getVouchers(SSNewAccountingYear year) throws SQLException {
        return reader.vouchers(connection, year.getId());
    }

    /** Returns the opening balances of a year keyed by account number. */
    public java.util.Map<Integer, java.math.BigDecimal> getOpeningBalances(
            SSNewAccountingYear year) throws SQLException {
        return reader.openingBalances(connection, year.getId());
    }

    /** Replaces the opening balances of a year with its in-balance values. */
    public void replaceOpeningBalances(SSNewAccountingYear year) throws SQLException {
        java.util.Map<Integer, java.math.BigDecimal> byNumber = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<SSAccount, java.math.BigDecimal> entry
                : year.getInBalance().entrySet()) {
            byNumber.put(entry.getKey().getNumber(), entry.getValue());
        }
        writer.replaceOpeningBalances(connection, year.getId(), byNumber);
    }

    /** Lists the voucher templates of the selected company. */
    public List<se.swedsoft.bookkeeping.data.SSVoucherTemplate> getVoucherTemplates()
            throws SQLException {
        requireCompany();
        return reader.voucherTemplates(connection, currentCompany.getId());
    }

    /** Adds or replaces a voucher template of the selected company. */
    public void addVoucherTemplate(se.swedsoft.bookkeeping.data.SSVoucherTemplate template)
            throws SQLException {
        requireCompany();
        writer.addVoucherTemplate(connection, currentCompany.getId(), template);
    }

    public void addCompany(SSNewCompany company) throws SQLException {
        writer.addCompany(connection, company);
    }

    public void updateCompany(SSNewCompany company) throws SQLException {
        writer.updateCompany(connection, company);
    }

    public void deleteCompany(SSNewCompany company) throws SQLException {
        writer.deleteCompany(connection, company.getId());
        if (currentCompany != null && Objects.equals(currentCompany.getId(), company.getId())) {
            currentCompany = null;
            currentYear = null;
        }
    }

    public void addAccountingYear(SSNewAccountingYear year) throws SQLException {
        requireCompany();
        writer.addAccountingYear(connection, currentCompany.getId(), year);
    }

    public void addVoucher(SSVoucher voucher) throws SQLException {
        requireYear();
        writer.addVoucher(connection, currentYear.getId(), voucher);
    }

    public void updateVoucher(SSVoucher voucher) throws SQLException {
        requireYear();
        writer.updateVoucher(connection, currentYear.getId(), voucher);
    }

    public void deleteVoucher(SSVoucher voucher) throws SQLException {
        requireYear();
        writer.deleteVoucher(connection, currentYear.getId(), voucher.getNumber());
    }

    /** Commits the caller's work. */
    public void commit() throws SQLException {
        connection.commit();
    }

    /** Rolls back the caller's work. */
    public void rollback() throws SQLException {
        connection.rollback();
    }

    private void requireCompany() throws SQLException {
        if (currentCompany == null) throw new SQLException("No current company selected");
    }

    private void requireYear() throws SQLException {
        if (currentYear == null) throw new SQLException("No current accounting year selected");
    }

    @Override
    public void close() throws SQLException {
        // The caller owns the connection; only local selection state is released.
        currentCompany = null;
        currentYear = null;
    }
}
