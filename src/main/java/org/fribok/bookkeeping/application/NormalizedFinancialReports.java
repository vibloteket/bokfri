package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.report.FinancialReportService;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Lease-owned read-only financial calculations with explicit selected-year account resolution. */
public final class NormalizedFinancialReports implements AutoCloseable {
    public record Account(int number, String description) {}

    /** Context of one SQL read snapshot; maximum is not a promise that data never changes later. */
    public record Scope(int company, int year, String companyName, Integer maximumVoucher) {}

    public record Trial(Scope scope, FinancialReportService.TrialBalance report) {}

    public record Ledger(Scope scope, FinancialReportService.AccountLedger report) {}

    public record Balance(Scope scope, FinancialReportService.BalanceSheet report) {}

    public record Income(Scope scope, FinancialReportService.IncomeStatement report) {}

    /** Read-only balance sheet from stored year opening through an explicit inclusive date. */
    public Balance balanceSheet(LocalDate date) throws SQLException {
        Objects.requireNonNull(date, "balance-sheet date");
        return read(
                context ->
                        new Balance(
                                context.scope(),
                                FinancialReportService.withExplicitAccounts(context.year())
                                        .balanceSheet(date)));
    }

    /** Read-only income statement for an explicit inclusive period, without opening amounts. */
    public Income incomeStatement(LocalDate from, LocalDate to) throws SQLException {
        Objects.requireNonNull(from, "income-statement from");
        Objects.requireNonNull(to, "income-statement to");
        return read(
                context ->
                        new Income(
                                context.scope(),
                                FinancialReportService.withExplicitAccounts(context.year())
                                        .incomeStatement(from, to)));
    }

    private record Context(Scope scope, SSNewAccountingYear year) {}

    @FunctionalInterface
    private interface Read<T> {
        T run(Context context) throws SQLException;
    }

    private final NormalizedApplicationOwner.Connections connections;
    private final Object gate;
    private final Clock clock;
    private final NormalizedApplicationOwner.Selection selection;
    private volatile boolean active = true;

    NormalizedFinancialReports(
            NormalizedApplicationOwner.Connections connections,
            Object gate,
            Clock clock,
            NormalizedApplicationOwner.Selection selection) {
        this.connections = connections;
        this.gate = gate;
        this.clock = clock;
        this.selection = selection;
    }

    /** Explicit account catalogue read; inactive accounts remain reportable. Run off EDT. */
    public List<Account> accounts() throws SQLException {
        return read(
                context ->
                        context.year().getAccounts().stream()
                                .sorted(java.util.Comparator.comparing(SSAccount::getNumber))
                                .map(a -> new Account(a.getNumber(), a.getDescription()))
                                .toList());
    }

    /**
     * Opening at from + period debit - period credit = closing, using the shared report formulas.
     */
    public Trial trialBalance(LocalDate from, LocalDate to) throws SQLException {
        return read(
                context ->
                        new Trial(
                                context.scope(),
                                FinancialReportService.withExplicitAccounts(context.year())
                                        .trialBalance(from, to)));
    }

    /** Explicit selected-year account, inclusive dates, chronological rows and running balance. */
    public Ledger accountLedger(int account, LocalDate from, LocalDate to) throws SQLException {
        return read(
                context ->
                        new Ledger(
                                context.scope(),
                                FinancialReportService.withExplicitAccounts(context.year())
                                        .accountLedger(account, from, to)));
    }

    private Context load(Connection connection) throws SQLException {
        var store = new NormalizedAccountingStore(connection, clock);
        var company =
                store.getCompanies().stream()
                        .filter(c -> c.getId() == selection.company().id())
                        .findFirst()
                        .orElseThrow(() -> new SQLException("Selected company no longer exists"));
        store.setCurrentCompany(company);
        var year =
                store.getYears().stream()
                        .filter(
                                y ->
                                        y.getId() == selection.year().id()
                                                && Objects.equals(
                                                        y.getLocalFrom(), selection.year().from())
                                                && Objects.equals(
                                                        y.getLocalTo(), selection.year().to()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new SQLException(
                                                "Selected year changed or no longer belongs to"
                                                        + " company"));
        if (year.getLocalFrom() == null
                || year.getLocalTo() == null
                || year.getLocalTo().isBefore(year.getLocalFrom())
                || year.getAccountPlan() == null) {
            throw new SQLException(
                    "Known year bounds and account plan required for financial reports");
        }
        var accounts = store.getAccounts(year);
        year.getAccountPlan().setAccounts(accounts);
        var byNumber = new LinkedHashMap<Integer, SSAccount>();
        for (var a : accounts) byNumber.put(a.getNumber(), a);
        var opening = new LinkedHashMap<SSAccount, BigDecimal>();
        for (var entry : store.getOpeningBalances(year).entrySet()) {
            var account = byNumber.get(entry.getKey());
            if (account == null || entry.getValue() == null) {
                throw new SQLException("Opening balance missing its selected-year account/value");
            }
            opening.put(account, entry.getValue());
        }
        year.setInBalance(opening);
        var vouchers = store.getVouchers(year);
        year.setVouchers(vouchers); // Even an empty list must never fall back to SSDB.
        Integer maximum =
                vouchers.stream()
                        .map(v -> v.getNumber())
                        .filter(Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(null);
        return new Context(
                new Scope(
                        selection.company().id(),
                        selection.year().id(),
                        company.getName(),
                        maximum),
                year);
    }

    private <T> T read(Read<T> work) throws SQLException {
        requireAvailable();
        synchronized (gate) {
            requireAvailable();
            try (var connection = connections.open()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    T result = work.run(load(connection));
                    requireAvailable();
                    connection.rollback();
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            }
        }
    }

    private void requireAvailable() throws SQLException {
        if (!active || !selection.isActive() || selection.isBlocked()) {
            throw new SQLException("Financial report context is closed, replaced or blocked");
        }
    }

    /** Retires reads; does not own the database or cancel writes accepted by another command. */
    @Override
    public void close() {
        active = false;
    }
}
