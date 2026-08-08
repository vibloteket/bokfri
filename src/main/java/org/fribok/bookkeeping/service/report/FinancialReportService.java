package org.fribok.bookkeeping.service.report;

import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic financial reports independent of Swing and print rendering. */
public final class FinancialReportService {
    private final SSNewAccountingYear year;

    public FinancialReportService(SSNewAccountingYear year) {
        this.year = year;
    }

    public TrialBalance trialBalance(LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        Map<SSAccount, BigDecimal> opening = openingAt(from);
        Map<SSAccount, BigDecimal> debit = new LinkedHashMap<>();
        Map<SSAccount, BigDecimal> credit = new LinkedHashMap<>();
        accumulatePeriod(from, to, debit, credit);
        List<TrialBalanceRow> rows = year.getAccounts().stream().sorted(accountOrder())
                .map(account -> new TrialBalanceRow(account.getNumber(), account.getDescription(),
                        value(opening, account), value(debit, account), value(credit, account),
                        value(opening, account).add(value(debit, account)).subtract(value(credit, account))))
                .filter(row -> nonZero(row.opening()) || nonZero(row.debit())
                        || nonZero(row.credit()) || nonZero(row.closing()))
                .toList();
        BigDecimal openingTotal = sum(rows.stream().map(TrialBalanceRow::opening).toList());
        BigDecimal debitTotal = sum(rows.stream().map(TrialBalanceRow::debit).toList());
        BigDecimal creditTotal = sum(rows.stream().map(TrialBalanceRow::credit).toList());
        BigDecimal closingTotal = sum(rows.stream().map(TrialBalanceRow::closing).toList());
        return new TrialBalance(from, to, rows, openingTotal, debitTotal, creditTotal, closingTotal);
    }

    public BalanceSheet balanceSheet(LocalDate date) {
        requireInYear(date, "Balance-sheet date");
        Map<SSAccount, BigDecimal> balances = openingAt(date.plusDays(1));
        List<BalanceSheetRow> rows = year.getAccounts().stream()
                .filter(account -> SSAccountMath.isBalanceAccount(account, year)).sorted(accountOrder())
                .map(account -> new BalanceSheetRow(account.getNumber(), account.getDescription(),
                        value(balances, account)))
                .filter(row -> nonZero(row.balance())).toList();
        BigDecimal assets = sum(rows.stream().map(BalanceSheetRow::balance)
                .filter(value -> value.signum() > 0).toList());
        BigDecimal liabilitiesAndEquity = sum(rows.stream().map(BalanceSheetRow::balance)
                .filter(value -> value.signum() < 0).map(BigDecimal::abs).toList());
        BigDecimal currentResult = incomeStatement(year.getLocalFrom(), date).result();
        BigDecimal difference = assets.subtract(liabilitiesAndEquity).subtract(currentResult);
        return new BalanceSheet(date, rows, assets, liabilitiesAndEquity, currentResult, difference);
    }

    public IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        Map<SSAccount, BigDecimal> debit = new LinkedHashMap<>();
        Map<SSAccount, BigDecimal> credit = new LinkedHashMap<>();
        accumulatePeriod(from, to, debit, credit);
        List<IncomeStatementRow> rows = year.getAccounts().stream()
                .filter(account -> SSAccountMath.isResultAccount(account, year)).sorted(accountOrder())
                .map(account -> new IncomeStatementRow(account.getNumber(), account.getDescription(),
                        value(credit, account).subtract(value(debit, account))))
                .filter(row -> nonZero(row.amount())).toList();
        return new IncomeStatement(from, to, rows,
                sum(rows.stream().map(IncomeStatementRow::amount).toList()));
    }

    public AccountLedger accountLedger(int accountNumber, LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        SSAccount account = year.getAccountPlan().getAccount(accountNumber);
        if (account == null) {
            throw new IllegalArgumentException("Account not found: " + accountNumber);
        }
        BigDecimal opening = value(openingAt(from), account);
        BigDecimal running = opening;
        List<LedgerRow> rows = new ArrayList<>();
        for (SSVoucher voucher : sortedVouchers()) {
            if (voucher.getLocalDate() == null || voucher.getLocalDate().isBefore(from)
                    || voucher.getLocalDate().isAfter(to)) {
                continue;
            }
            for (SSVoucherRow row : voucher.getRows()) {
                if (!usable(row) || row.getAccountNr() == null || row.getAccountNr() != accountNumber) {
                    continue;
                }
                BigDecimal debit = zero(row.getDebet());
                BigDecimal credit = zero(row.getCredit());
                running = running.add(debit).subtract(credit);
                rows.add(new LedgerRow(voucher.getNumber(), voucher.getLocalDate(),
                        voucher.getDescription(), debit, credit, running));
            }
        }
        return new AccountLedger(accountNumber, account.getDescription(), from, to, opening,
                List.copyOf(rows), running);
    }

    public AccountBalance accountBalance(int accountNumber, LocalDate date) {
        requireInYear(date, "Balance date");
        SSAccount account = year.getAccountPlan().getAccount(accountNumber);
        if (account == null) {
            throw new IllegalArgumentException("Account not found: " + accountNumber);
        }
        return new AccountBalance(accountNumber, account.getDescription(), date,
                value(openingAt(date.plusDays(1)), account));
    }

    private Map<SSAccount, BigDecimal> openingAt(LocalDate date) {
        Map<SSAccount, BigDecimal> result = new LinkedHashMap<>(year.getInBalance());
        for (SSVoucher voucher : sortedVouchers()) {
            if (voucher.getLocalDate() == null || !voucher.getLocalDate().isBefore(date)) {
                continue;
            }
            for (SSVoucherRow row : voucher.getRows()) {
                if (usable(row)) {
                    add(result, row.getAccount(), SSVoucherMath.getDebetMinusCredit(row));
                }
            }
        }
        return result;
    }

    private void accumulatePeriod(LocalDate from, LocalDate to, Map<SSAccount, BigDecimal> debit,
            Map<SSAccount, BigDecimal> credit) {
        for (SSVoucher voucher : sortedVouchers()) {
            if (voucher.getLocalDate() == null || voucher.getLocalDate().isBefore(from)
                    || voucher.getLocalDate().isAfter(to)) {
                continue;
            }
            for (SSVoucherRow row : voucher.getRows()) {
                if (usable(row)) {
                    add(debit, row.getAccount(), zero(row.getDebet()));
                    add(credit, row.getAccount(), zero(row.getCredit()));
                }
            }
        }
    }

    private List<SSVoucher> sortedVouchers() {
        return year.getVouchers().stream().sorted(Comparator
                .comparing(SSVoucher::getLocalDate, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(SSVoucher::getNumber, Comparator.nullsLast(Integer::compareTo))).toList();
    }

    private static boolean usable(SSVoucherRow row) {
        return row.isValid() && !row.isCrossed() && row.getAccount() != null;
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        requireInYear(from, "Period start");
        requireInYear(to, "Period end");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Report period is invalid");
        }
    }

    private void requireInYear(LocalDate date, String label) {
        if (date == null || date.isBefore(year.getLocalFrom()) || date.isAfter(year.getLocalTo())) {
            throw new IllegalArgumentException(label + " must be within the selected accounting year");
        }
    }

    private static Comparator<SSAccount> accountOrder() {
        return Comparator.comparing(SSAccount::getNumber);
    }

    private static BigDecimal value(Map<SSAccount, BigDecimal> values, SSAccount account) {
        return values.getOrDefault(account, BigDecimal.ZERO);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean nonZero(BigDecimal value) {
        return value.signum() != 0;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void add(Map<SSAccount, BigDecimal> values, SSAccount account, BigDecimal amount) {
        values.merge(account, amount, BigDecimal::add);
    }

    public record TrialBalanceRow(int account, String description, BigDecimal opening,
                                  BigDecimal debit, BigDecimal credit, BigDecimal closing) {}
    public record TrialBalance(LocalDate from, LocalDate to, List<TrialBalanceRow> rows,
                               BigDecimal openingTotal, BigDecimal debitTotal,
                               BigDecimal creditTotal, BigDecimal closingTotal) {}
    public record BalanceSheetRow(int account, String description, BigDecimal balance) {}
    public record BalanceSheet(LocalDate date, List<BalanceSheetRow> rows, BigDecimal assets,
                               BigDecimal liabilitiesAndEquity, BigDecimal currentResult,
                               BigDecimal difference) {}
    public record IncomeStatementRow(int account, String description, BigDecimal amount) {}
    public record IncomeStatement(LocalDate from, LocalDate to, List<IncomeStatementRow> rows,
                                  BigDecimal result) {}
    public record LedgerRow(int voucherNumber, LocalDate date, String description,
                            BigDecimal debit, BigDecimal credit, BigDecimal balance) {}
    public record AccountLedger(int account, String description, LocalDate from, LocalDate to,
                                BigDecimal opening, List<LedgerRow> rows, BigDecimal closing) {}
    public record AccountBalance(int account, String description, LocalDate date,
                                 BigDecimal balance) {}
}
