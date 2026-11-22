package org.fribok.bookkeeping.service.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class ExplicitFinancialReportServiceTest {
    static final LocalDate FROM = LocalDate.of(2026, 3, 1), TO = LocalDate.of(2026, 6, 30);

    @Test
    void explicitResolutionMatchesLegacyFormulasWithoutAmbientRowGettersOrMutation() {
        var legacy = new FinancialReportService(year(false));
        var strictYear = year(true);
        var explicit = FinancialReportService.withExplicitAccounts(strictYear);
        assertEquals(legacy.trialBalance(FROM, TO), explicit.trialBalance(FROM, TO));
        assertEquals(legacy.accountLedger(1910, FROM, TO), explicit.accountLedger(1910, FROM, TO));
        assertEquals(legacy.accountBalance(1910, TO), explicit.accountBalance(1910, TO));
        assertEquals(
                List.of(2, 20),
                explicit.accountLedger(1910, FROM, TO).rows().stream()
                        .map(FinancialReportService.LedgerRow::voucherNumber)
                        .toList());
        assertThrows(
                AssertionError.class,
                () -> new FinancialReportService(strictYear).trialBalance(FROM, TO),
                "Legacy constructor must retain its original getter behavior");
    }

    @Test
    void datesAccountsAndMalformedExplicitCataloguesFailClosed() {
        var year = year(true);
        var service = FinancialReportService.withExplicitAccounts(year);
        assertThrows(IllegalArgumentException.class, () -> service.trialBalance(null, TO));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.trialBalance(FROM, FROM.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> service.accountLedger(9999, FROM, TO));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.accountLedger(1910, FROM, LocalDate.of(2027, 1, 1)));
        year.getAccountPlan()
                .setAccounts(new ArrayList<>(List.of(new SSAccount(1910), new SSAccount(1910))));
        assertThrows(
                IllegalArgumentException.class,
                () -> FinancialReportService.withExplicitAccounts(year));
        assertThrows(
                NullPointerException.class,
                () -> FinancialReportService.withExplicitAccounts(null));
    }

    @Test
    void openingPeriodAndClosingRemainExactWithCrossedMissingAndNullDatedRowsExcluded() {
        var service = FinancialReportService.withExplicitAccounts(year(true));
        var trial = service.trialBalance(FROM, TO);
        var ledger = service.accountLedger(1910, FROM, TO);
        assertEquals(0, new BigDecimal("12.625").compareTo(ledger.opening()));
        assertEquals(0, new BigDecimal("11.250000000001").compareTo(ledger.closing()));
        var row = trial.rows().stream().filter(r -> r.account() == 1910).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.000000000001").compareTo(row.debit()));
        assertEquals(0, new BigDecimal("1.375").compareTo(row.credit()));
        assertEquals(row.closing(), ledger.closing());
        assertThrows(UnsupportedOperationException.class, () -> trial.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.rows().clear());
        var whole = service.trialBalance(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertEquals(0, new BigDecimal("10.125").compareTo(whole.rows().get(0).opening()));
    }

    static SSNewAccountingYear year(boolean strict) {
        var year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2026, 1, 1));
        year.setLocalTo(LocalDate.of(2026, 12, 31));
        var cash = new SSAccount(1910);
        cash.setDescription("Cash");
        var income = new SSAccount(3010);
        income.setDescription("Income");
        var plan = new SSAccountPlan();
        plan.setAccounts(new ArrayList<>(List.of(cash, income)));
        year.setAccountPlan(plan);
        year.setInBalance(
                new LinkedHashMap<>(
                        Map.of(cash, new BigDecimal("10.125"), income, new BigDecimal("-10.125"))));
        var old = new SSVoucher(3, LocalDate.of(2026, 1, 1));
        old.setDescription("prior");
        old.getRows().add(row(cash, "2.50", null, strict));
        old.getRows().add(row(income, null, "2.50", strict));
        var later = new SSVoucher(20, LocalDate.of(2026, 3, 5));
        later.setDescription("later");
        later.getRows().add(row(cash, null, "1.375", strict));
        later.getRows().add(row(income, "1.375", null, strict));
        var crossed = row(cash, "1000", null, strict);
        crossed.setCrossed(true);
        later.getRows().add(crossed);
        later.getRows().add(row(null, "90", null, strict));
        later.getRows().add(row(cash, null, null, strict));
        later.getRows().add(row(new SSAccount(9999), "44", null, strict));
        var earlier = new SSVoucher(2, LocalDate.of(2026, 3, 5));
        earlier.setDescription("earlier");
        earlier.getRows().add(row(cash, "0.000000000001", null, strict));
        var undated = new SSVoucher(9, (LocalDate) null);
        undated.getRows().add(row(cash, "900", null, strict));
        var outside = new SSVoucher(30, LocalDate.of(2026, 12, 31));
        outside.getRows().add(row(cash, "700", null, strict));
        year.setVouchers(List.of(outside, later, undated, old, earlier));
        return year;
    }

    static SSVoucherRow row(SSAccount account, String debit, String credit, boolean strict) {
        BigDecimal d = debit == null ? null : new BigDecimal(debit),
                c = credit == null ? null : new BigDecimal(credit);
        return new SSVoucherRow(account, d, c, null, null) {
            @Override
            public SSAccount getAccount() {
                if (strict) throw new AssertionError("ambient account getter");
                return account;
            }

            @Override
            public SSAccount getAccount(List<SSAccount> accounts) {
                if (strict)
                    throw new AssertionError("Report reads must not bind or mutate row accounts");
                return account;
            }

            @Override
            public boolean isValid() {
                if (strict) throw new AssertionError("ambient validity getter");
                return account != null && (d != null || c != null);
            }
        };
    }
}
