package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.money;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.postedAndReversed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Read-only balance/income snapshots share the lease, explicit accounts and report formulas. */
class NormalizedApplicationBalanceIncomeTest {
    @Test
    void postedReversalReportsExactSignsAndExposesDifferenceWithoutWritesTest() throws Exception {
        try (var f = new Fixture()) {
            try (var prepared = f.owner()) {
                postedAndReversed(f, prepared);
            }
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var lease = observed.owner.select(71, 11);
                observed.reset();
                var balance = lease.reports().balanceSheet(TO);
                var income = lease.reports().incomeStatement(FROM, TO);
                assertEquals(
                        List.of(1910),
                        balance.report().rows().stream().map(r -> r.account()).toList());
                money("15", balance.report().assets());
                money("0", balance.report().liabilitiesAndEquity());
                money("5", balance.report().currentResult());
                money("10", balance.report().difference());
                assertEquals(TO, balance.report().date());
                assertEquals(FROM, income.report().from());
                assertEquals(TO, income.report().to());
                assertEquals(3, balance.scope().maximumVoucher());
                assertTrue(income.report().rows().isEmpty());
                money("0", income.report().result());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                assertEquals(4, observed.rollbacks.get());
                assertEquals(2, observed.closes.get());
                assertTrue(lease.isActive());
                assertThrows(
                        UnsupportedOperationException.class, () -> balance.report().rows().clear());
                assertThrows(
                        UnsupportedOperationException.class, () -> income.report().rows().clear());
            }
        }
    }

    @Test
    void inclusiveAsOfDateAndPeriodDoNotInferOpeningIncomeOrPriorPeriodTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            postedAndReversed(f, owner);
            var reports = owner.select(71, 11).reports();
            var before = reports.balanceSheet(LocalDate.of(2026, 6, 14));
            money("15", before.report().assets());
            money("5", before.report().currentResult());
            var posted = reports.balanceSheet(LocalDate.of(2026, 6, 15));
            money("115.25", posted.report().assets());
            money("105.25", posted.report().currentResult());
            money("10", posted.report().difference());
            money("15", reports.balanceSheet(LocalDate.of(2026, 6, 16)).report().assets());
            money(
                    "100.25",
                    reports.incomeStatement(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15))
                            .report()
                            .result());
            money(
                    "-100.25",
                    reports.incomeStatement(LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 16))
                            .report()
                            .result());
            money("0", reports.incomeStatement(FROM, TO).report().result());
            money("5", reports.incomeStatement(LocalDate.of(2026, 1, 1), TO).report().result());
        }
    }

    @Test
    void emptyAndForeignCompanyReportsStayEmptyAndSnapshotsAreImmutableTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var reports = owner.select(71, 11).reports();
            var empty = reports.balanceSheet(TO);
            assertTrue(empty.report().rows().isEmpty());
            money("0", empty.report().difference());
            assertTrue(reports.incomeStatement(FROM, TO).report().rows().isEmpty());
            postedAndReversed(f, owner);
            var foreign = owner.select(72, 12).reports();
            assertTrue(foreign.balanceSheet(TO).report().rows().isEmpty());
            assertTrue(foreign.incomeStatement(FROM, TO).report().rows().isEmpty());
            assertTrue(empty.report().rows().isEmpty());
            money("0", empty.report().assets());
        }
    }

    @Test
    void exactDecimalsInactiveAccountsAndCurrentLiteralLabelsSurviveReopenTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            lease.vouchers().post(lease.vouchers().preview(draft(FROM, "0.01")));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET active=FALSE,description='<html>Current & raw'"
                                    + " WHERE number IN(1910,3010)");
            }
            var before = f.snapshot();
            var balance = lease.reports().balanceSheet(TO);
            var income = lease.reports().incomeStatement(FROM, TO);
            money("10.01", balance.report().assets());
            money("0.01", income.report().result());
            assertEquals("<html>Current & raw", income.report().rows().get(0).description());
            assertEquals("<html>Current & raw", balance.report().rows().get(0).description());
            try (var reopened = f.owner()) {
                money(
                        "0.01",
                        reopened.select(71, 11)
                                .reports()
                                .incomeStatement(FROM, TO)
                                .report()
                                .result());
            }
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void invalidDatesAndRetiredContextsFailClosedWithoutDatabaseChangesTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var reports = owner.select(71, 11).reports();
            var before = f.snapshot();
            for (var day : List.of(LocalDate.of(2025, 12, 31), LocalDate.of(2027, 1, 1)))
                assertThrows(IllegalArgumentException.class, () -> reports.balanceSheet(day));
            assertThrows(IllegalArgumentException.class, () -> reports.incomeStatement(TO, FROM));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.incomeStatement(LocalDate.of(2025, 1, 1), TO));
            int opens = f.opens.get();
            assertThrows(NullPointerException.class, () -> reports.balanceSheet(null));
            assertThrows(NullPointerException.class, () -> reports.incomeStatement(null, TO));
            assertEquals(opens, f.opens.get());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> reports.balanceSheet(TO));
            assertThrows(SQLException.class, () -> reports.incomeStatement(FROM, TO));
            assertEquals(opens, f.opens.get());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void readQueryRollbackAndCloseErrorsDoNotMarkCommitUncertainTest() throws Exception {
        for (String failure : List.of("query", "rollback", "close"))
            try (var f = new Fixture();
                    var observed = new Observed(f)) {
                accounts(f);
                var reports = observed.owner.select(71, 11).reports();
                var before = f.snapshot();
                observed.failure = failure;
                assertThrows(SQLException.class, () -> reports.balanceSheet(TO));
                assertThrows(SQLException.class, () -> reports.incomeStatement(FROM, TO));
                assertFalse(observed.owner.isBlocked());
                assertEquals(0, observed.commits.get());
                assertEquals(before, f.snapshot());
                observed.failure = "";
                money("10", reports.balanceSheet(TO).report().assets());
            }
    }

    @Test
    void yearDriftAndCommitUncertaintyElsewhereBlockBothNewReportsTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var token = lease.vouchers().preview(draft(FROM, "1"));
            f.failure = "after";
            assertThrows(SQLException.class, () -> lease.vouchers().post(token));
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> lease.reports().balanceSheet(TO));
            assertThrows(SQLException.class, () -> lease.reports().incomeStatement(FROM, TO));
            assertEquals(opens, f.opens.get());
        }
        try (var f = new Fixture();
                var owner = f.owner()) {
            var reports = owner.select(71, 11).reports();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE"
                                    + " legacy_id=11");
            }
            assertThrows(SQLException.class, () -> reports.balanceSheet(TO));
            assertThrows(SQLException.class, () -> reports.incomeStatement(FROM, TO));
        }
    }

    @Test
    void closedOwnerFencesPendingBalanceAndIncomeReadsWithoutCommitTest() throws Exception {
        for (boolean balance : List.of(true, false))
            try (var f = new Fixture();
                    var observed = new Observed(f);
                    var executor = Executors.newSingleThreadExecutor()) {
                accounts(f);
                var reports = observed.owner.select(71, 11).reports();
                var before = f.snapshot();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                var pending =
                        executor.submit(
                                () ->
                                        balance
                                                ? reports.balanceSheet(TO)
                                                : reports.incomeStatement(FROM, TO));
                try {
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    observed.owner.close();
                } finally {
                    observed.release.countDown();
                }
                var error =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> pending.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, error.getCause());
                assertEquals(0, observed.commits.get());
                assertEquals(before, f.snapshot());
            }
    }
}
