package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.report.FinancialReportService;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnostics;
import org.fribok.bookkeeping.service.vat.VatSettlementDimensionDiagnostics;

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

    public record Vat(Scope scope, org.fribok.bookkeeping.service.vat.VatReport report) {}

    /** One frozen proposal row; absent debit/credit stays absent, not an invented amount. */
    public record VatSettlementRow(
            int account,
            String description,
            BigDecimal debit,
            BigDecimal credit,
            String project,
            String resultUnit) {}

    /**
     * Read-only settlement proposal. Contains no mutable voucher, writer or posting token. Balanced
     * arithmetic is not a bookability, stale-source or duplicate-period guarantee.
     */
    public record VatSettlementPreview(
            Scope scope,
            org.fribok.bookkeeping.service.vat.VatReport report,
            LocalDate date,
            String description,
            List<VatSettlementRow> rows,
            BigDecimal debitTotal,
            BigDecimal creditTotal,
            List<VatSettlementDiagnostics.Finding> findings) {
        public VatSettlementPreview {
            rows = List.copyOf(rows);
            findings = List.copyOf(findings);
        }
    }

    /**
     * Explicit selected-year VAT boxes only; no settlement writer or filing operation is exposed.
     */
    public Vat vatReport(LocalDate from, LocalDate to) throws SQLException {
        return vatReport(from, to, false);
    }

    /**
     * Read-only opt-in field-total whole-krona policy; no settlement plan or writer is exposed.
     *
     * @param from explicit inclusive selected-year start
     * @param to explicit inclusive selected-year end
     * @return one immutable report with its SQL read scope
     * @throws SQLException if the selection is retired, blocked or the read fails
     */
    public Vat vatDeclarationReport(LocalDate from, LocalDate to) throws SQLException {
        return vatReport(from, to, true);
    }

    private Vat vatReport(LocalDate from, LocalDate to, boolean declaration) throws SQLException {
        Objects.requireNonNull(from, "VAT period start");
        Objects.requireNonNull(to, "VAT period end");
        return read(
                context -> {
                    var service = vatService(context, from, to);
                    return new Vat(
                            context.scope(),
                            declaration
                                    ? service.declarationReport(from, to)
                                    : service.report(from, to));
                });
    }

    /**
     * Captures a frozen field-total settlement proposal in one rollback-only read. Requires
     * explicit R1/R2/A roles. No voucher number, posting command or mutable plan escapes.
     *
     * @param from explicit inclusive selected-year start
     * @param to explicit inclusive selected-year end and proposal date
     * @return immutable rows and the declaration report calculated from the same read scope
     * @throws SQLException if the selection is blocked/retired or the read fails
     */
    public VatSettlementPreview vatSettlementPreview(LocalDate from, LocalDate to)
            throws SQLException {
        Objects.requireNonNull(from, "VAT period start");
        Objects.requireNonNull(to, "VAT period end");
        return read(
                context -> {
                    var plan = vatService(context, from, to).declarationPlan(from, to);
                    var descriptions = new java.util.HashMap<Integer, String>();
                    for (var account : context.year().getAccountPlan().getAccounts()) {
                        descriptions.put(account.getNumber(), account.getDescription());
                    }
                    var rows = new java.util.ArrayList<VatSettlementRow>();
                    BigDecimal debit = BigDecimal.ZERO;
                    BigDecimal credit = BigDecimal.ZERO;
                    for (var row : plan.voucher().getRows()) {
                        rows.add(
                                new VatSettlementRow(
                                        row.getAccountNr(),
                                        descriptions.get(row.getAccountNr()),
                                        row.getDebet(),
                                        row.getCredit(),
                                        row.getProjectNr(),
                                        row.getResultUnitNr()));
                        if (row.getDebet() != null) debit = debit.add(row.getDebet());
                        if (row.getCredit() != null) credit = credit.add(row.getCredit());
                    }
                    if (plan.voucher().getNumber() != 0
                            || !to.equals(plan.voucher().getLocalDate())
                            || debit.compareTo(credit) != 0) {
                        throw new IllegalStateException(
                                "Invalid read-only VAT settlement proposal");
                    }
                    var findings =
                            new java.util.ArrayList<>(
                                    VatSettlementDiagnostics.inspect(
                                            plan.report(),
                                            context.year().getAccountPlan().getAccounts(),
                                            context.year().getVouchers(),
                                            plan.voucher()));
                    findings.addAll(
                            VatSettlementDimensionDiagnostics.inspect(
                                    plan.voucher(), context.dimensions()));
                    return new VatSettlementPreview(
                            context.scope(),
                            plan.report(),
                            to,
                            plan.voucher().getDescription(),
                            rows,
                            debit,
                            credit,
                            findings);
                },
                true);
    }

    private static org.fribok.bookkeeping.service.vat.VatService vatService(
            Context context, LocalDate from, LocalDate to) {
        if (to.isBefore(from)
                || from.isBefore(context.year().getLocalFrom())
                || to.isAfter(context.year().getLocalTo())) {
            throw new IllegalArgumentException(
                    "VAT period must be ordered and inside selected year");
        }
        return new org.fribok.bookkeeping.service.vat.VatService(
                context.year(),
                voucher -> {
                    throw new SQLException("Read-only VAT report has no settlement writer");
                });
    }

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

    private record Context(
            Scope scope,
            SSNewAccountingYear year,
            VatSettlementDimensionDiagnostics.Catalogue dimensions) {}

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

    private Context load(Connection connection, boolean dimensions) throws SQLException {
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
        VatSettlementDimensionDiagnostics.Catalogue catalogue = null;
        if (dimensions) {
            var registers =
                    new org.fribok.bookkeeping.dataformat.NormalizedRegisterStore(connection);
            catalogue =
                    new VatSettlementDimensionDiagnostics.Catalogue(
                            registers.getProjects(selection.company().id()).stream()
                                    .map(
                                            p ->
                                                    new VatSettlementDimensionDiagnostics.Project(
                                                            p.getNumber(),
                                                            p.getConcluded(),
                                                            p.getLocalConcludedDate()))
                                    .toList(),
                            registers.getResultUnits(selection.company().id()).stream()
                                    .map(u -> u.getNumber())
                                    .toList());
        }
        return new Context(
                new Scope(
                        selection.company().id(),
                        selection.year().id(),
                        company.getName(),
                        maximum),
                year,
                catalogue);
    }

    private <T> T read(Read<T> work) throws SQLException {
        return read(work, false);
    }

    private <T> T read(Read<T> work, boolean dimensions) throws SQLException {
        requireAvailable();
        synchronized (gate) {
            requireAvailable();
            try (var connection = connections.open()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    T result = work.run(load(connection, dimensions));
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
