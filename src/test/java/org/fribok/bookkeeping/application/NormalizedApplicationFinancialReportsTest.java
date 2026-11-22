package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationFinancialReportsTest {
    static final LocalDate FROM = LocalDate.of(2026, 6, 1), TO = LocalDate.of(2026, 6, 30);

    static Draft draft(LocalDate date, String amount) {
        return new Draft(
                date,
                "Explicit report source",
                List.of(
                        new Row(1910, new BigDecimal(amount), null),
                        new Row(3010, null, new BigDecimal(amount))));
    }

    static void accounts(Fixture f) throws Exception {
        NormalizedApplicationManualVoucherTest.accounts(f);
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO opening_balance(accounting_year_id,account_id,amount) SELECT"
                        + " accounting_year_id,id,10 FROM account WHERE number=1910 AND"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                        + " legacy_id=11)");
            s.executeUpdate(
                    "INSERT INTO opening_balance(accounting_year_id,account_id,amount) SELECT"
                        + " accounting_year_id,id,-10 FROM account WHERE number=3010 AND"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                        + " legacy_id=11)");
        }
    }

    static void postedAndReversed(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        accounts(f);
        var lease = owner.select(71, 11);
        var commands = lease.vouchers();
        commands.post(commands.preview(draft(LocalDate.of(2026, 5, 1), "5")));
        commands.post(commands.preview(draft(LocalDate.of(2026, 6, 15), "100.25")));
        long id = lease.voucherRegister().page(null).headers().get(0).id();
        commands.postReversal(
                commands.previewReversal(id, LocalDate.of(2026, 6, 16), "Exact reversal"));
    }

    static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void reportsCombineOpeningPriorPeriodPostingAndReversalWithoutLegacySqlOrWrites()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            postedAndReversed(f, setup);
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var lease = observed.owner.select(71, 11);
                var reports = lease.reports();
                observed.reset();
                var before = f.snapshot();
                var trial = reports.trialBalance(FROM, TO);
                var ledger = reports.accountLedger(1910, FROM, TO);
                assertEquals(71, trial.scope().company());
                assertEquals(11, trial.scope().year());
                assertEquals(3, trial.scope().maximumVoucher());
                var cash =
                        trial.report().rows().stream()
                                .filter(r -> r.account() == 1910)
                                .findFirst()
                                .orElseThrow();
                money("15", cash.opening());
                money("100.25", cash.debit());
                money("100.25", cash.credit());
                money("15", cash.closing());
                money("0", trial.report().openingTotal());
                money("200.50", trial.report().debitTotal());
                money("200.50", trial.report().creditTotal());
                money("0", trial.report().closingTotal());
                money("15", ledger.report().opening());
                money("15", ledger.report().closing());
                assertEquals(
                        List.of(2, 3),
                        ledger.report().rows().stream().map(r -> r.voucherNumber()).toList());
                money("115.25", ledger.report().rows().get(0).balance());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.closes.get());
                assertEquals(4, observed.rollbacks.get());
                assertTrue(lease.isActive(), "Reads must preserve the current lease");
                assertThrows(
                        UnsupportedOperationException.class, () -> trial.report().rows().clear());
                assertThrows(
                        UnsupportedOperationException.class, () -> ledger.report().rows().clear());
            }
        }
    }

    @Test
    void rawDecimalsAddedRowsInactiveAccountsAndNullReferencesFollowExistingReportSemantics()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            accounts(f);
            long id =
                    NormalizedApplicationVoucherRegisterTest.insert(f, 11, 5, "<html>Literal\nrow");
            try (var c = f.open();
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "UPDATE account SET active=FALSE,description=NULL WHERE number=1910 AND"
                            + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
                for (String values :
                        List.of(
                                "0,a.id,1.234567890123,NULL,FALSE,TRUE",
                                "1,a.id,999,NULL,TRUE,FALSE",
                                "2,NULL,88,NULL,FALSE,FALSE",
                                "3,a.id,NULL,NULL,FALSE,FALSE"))
                    s.executeUpdate(
                            "INSERT INTO"
                                + " voucher_row(voucher_id,accounting_year_id,row_number,account_id,debit,credit,crossed,added)"
                                + " SELECT "
                                    + id
                                    + ",a.accounting_year_id,"
                                    + values
                                    + " FROM account a WHERE number=1910 AND"
                                    + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                    + " legacy_id=11)");
                s.executeUpdate(
                        "INSERT INTO voucher(accounting_year_id,number,voucher_date) SELECT"
                            + " id,99,NULL FROM accounting_year WHERE legacy_id=11");
                s.executeUpdate(
                        "INSERT INTO"
                            + " voucher_row(voucher_id,accounting_year_id,row_number,account_id,debit,crossed,added)"
                            + " SELECT v.id,v.accounting_year_id,0,a.id,900,FALSE,FALSE FROM"
                            + " voucher v JOIN account a ON"
                            + " a.accounting_year_id=v.accounting_year_id AND a.number=1910 WHERE"
                            + " v.number=99");
            }
            var reports = owner.select(71, 11).reports();
            var before = f.snapshot();
            var ledger = reports.accountLedger(1910, FROM, TO);
            assertEquals(1, ledger.report().rows().size());
            money("11.234567890123", ledger.report().closing());
            assertNull(ledger.report().description());
            assertEquals("<html>Literal\nrow", ledger.report().rows().get(0).description());
            assertEquals(99, ledger.scope().maximumVoucher());
            assertTrue(reports.accounts().stream().anyMatch(a -> a.number() == 1910));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void invalidDatesForeignAccountsAndCompanyIsolationCannotBorrowOtherYearData()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var reports = owner.select(71, 11).reports();
            var before = f.snapshot();
            assertThrows(IllegalArgumentException.class, () -> reports.trialBalance(null, TO));
            assertThrows(IllegalArgumentException.class, () -> reports.trialBalance(TO, FROM));
            assertThrows(
                    IllegalArgumentException.class, () -> reports.accountLedger(8010, FROM, TO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.trialBalance(FROM, LocalDate.of(2027, 1, 1)));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            var foreign = owner.select(72, 12).reports();
            assertTrue(foreign.trialBalance(FROM, TO).report().rows().isEmpty());
            assertTrue(foreign.accounts().stream().noneMatch(a -> a.number() == 3010));
            assertThrows(
                    IllegalArgumentException.class, () -> foreign.accountLedger(3010, FROM, TO));
        }
    }

    @Test
    void explicitRefreshUsesNewPostingsWhileEarlierImmutableResultsRemainSnapshots()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var old = lease.reports().accountLedger(1910, FROM, TO);
            money("10", old.report().closing());
            assertNull(old.scope().maximumVoucher());
            lease.vouchers()
                    .post(lease.vouchers().preview(draft(LocalDate.of(2026, 6, 15), "25.75")));
            var current = lease.reports().accountLedger(1910, FROM, TO);
            money("35.75", current.report().closing());
            assertEquals(1, current.report().rows().size());
            assertEquals(1, current.scope().maximumVoucher());
            money("10", old.report().closing());
            assertTrue(old.report().rows().isEmpty());
        }
    }

    @Test
    void changedOrUnknownYearBoundsAreRefusedWithoutFallbackDefaults() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var reports = owner.select(71, 11).reports();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE"
                                    + " legacy_id=11");
            }
            assertThrows(SQLException.class, () -> reports.trialBalance(FROM, TO));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET starts_on=NULL WHERE legacy_id=11");
            }
            var unknown = owner.select(71, 11).reports();
            assertThrows(SQLException.class, unknown::accounts);
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void replacedAndClosedReadersRefuseBeforeOpeningSql() throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            var old = observed.owner.select(71, 11).reports();
            observed.owner.select(72, 12);
            int opens = observed.opens.get();
            assertThrows(SQLException.class, old::accounts);
            assertThrows(SQLException.class, () -> old.trialBalance(FROM, TO));
            assertEquals(opens, observed.opens.get());
            var current = observed.owner.select(71, 11).reports();
            current.close();
            opens = observed.opens.get();
            assertThrows(SQLException.class, current::accounts);
            assertEquals(opens, observed.opens.get());
            var last = observed.owner.select(71, 11).reports();
            observed.owner.close();
            opens = observed.opens.get();
            assertThrows(SQLException.class, () -> last.accountLedger(1910, FROM, TO));
            assertEquals(opens, observed.opens.get());
        }
    }

    @Test
    void uncertainManualCommitBlocksReportsBeforeOpeningAnyConnection() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var reports = lease.reports();
            var token = lease.vouchers().preview(draft(LocalDate.of(2026, 6, 15), "25"));
            f.failure = "after";
            assertThrows(SQLException.class, () -> lease.vouchers().post(token));
            int opens = f.opens.get();
            assertThrows(SQLException.class, reports::accounts);
            assertThrows(SQLException.class, () -> reports.trialBalance(FROM, TO));
            assertThrows(SQLException.class, () -> reports.accountLedger(1910, FROM, TO));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void readQueryRollbackAndCloseFailuresDoNotBecomeUncertainCommitsAndCanRecover()
            throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            var reports = observed.owner.select(71, 11).reports();
            for (String mode : List.of("query", "rollback", "close")) {
                observed.reset();
                observed.failure = mode;
                assertThrows(SQLException.class, () -> reports.trialBalance(FROM, TO));
                assertFalse(observed.owner.isBlocked());
                assertEquals(0, observed.commits.get());
                assertEquals(1, observed.closes.get());
                observed.failure = "";
                assertTrue(reports.trialBalance(FROM, TO).report().rows().isEmpty());
            }
        }
    }

    @Test
    void sharedGateSerializesSelectionAndOwnerClosureDiscardsPendingReadPayload() throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f);
                var executor = Executors.newFixedThreadPool(2)) {
            var reader = observed.owner.select(71, 11).reports();
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            var reading = executor.submit(() -> reader.trialBalance(FROM, TO));
            assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
            var selecting = executor.submit(() -> observed.owner.select(72, 12));
            Thread.sleep(100);
            assertFalse(selecting.isDone());
            observed.release.countDown();
            reading.get(5, TimeUnit.SECONDS);
            selecting.get(5, TimeUnit.SECONDS);
            var next = observed.owner.select(71, 11).reports();
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            var pending =
                    executor.submit(
                            () ->
                                    assertThrows(
                                            SQLException.class, () -> next.trialBalance(FROM, TO)));
            assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
            observed.owner.close();
            observed.release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(0, observed.commits.get());
        }
    }

    static final class Observed implements AutoCloseable {
        final AtomicInteger opens = new AtomicInteger(),
                commits = new AtomicInteger(),
                rollbacks = new AtomicInteger(),
                closes = new AtomicInteger();
        final NormalizedApplicationOwner owner;
        volatile String failure = "";
        volatile CountDownLatch entered, release;

        Observed(Fixture f) {
            owner =
                    new NormalizedApplicationOwner(
                            () -> {
                                assertFalse(javax.swing.SwingUtilities.isEventDispatchThread());
                                opens.incrementAndGet();
                                var c = f.open();
                                boolean[] report = {false};
                                return (Connection)
                                        Proxy.newProxyInstance(
                                                Connection.class.getClassLoader(),
                                                new Class<?>[] {Connection.class},
                                                (p, m, a) -> {
                                                    try {
                                                        if (m.getName().equals("commit")) {
                                                            commits.incrementAndGet();
                                                            fail("Reports must never commit");
                                                        }
                                                        if (m.getName().equals("rollback")) {
                                                            rollbacks.incrementAndGet();
                                                            if (report[0]
                                                                    && failure.equals("rollback"))
                                                                throw new SQLException(
                                                                        "report rollback");
                                                        }
                                                        if (m.getName().equals("close")) {
                                                            closes.incrementAndGet();
                                                            c.close();
                                                            if (failure.equals("close"))
                                                                throw new SQLException(
                                                                        "report close");
                                                            return null;
                                                        }
                                                        if (m.getName()
                                                                .equals("prepareStatement")) {
                                                            String sql = (String) a[0];
                                                            assertFalse(
                                                                    sql.matches(
                                                                            "(?is)\\s*(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)\\b.*"));
                                                            if (sql.toLowerCase(Locale.ROOT)
                                                                    .contains("from voucher ")) {
                                                                report[0] = true;
                                                                if (failure.equals("query"))
                                                                    throw new SQLException(
                                                                            "report query");
                                                                var signal = entered;
                                                                var wait = release;
                                                                if (signal != null) {
                                                                    signal.countDown();
                                                                    if (!wait.await(
                                                                            5, TimeUnit.SECONDS))
                                                                        throw new SQLException(
                                                                                "report timeout");
                                                                }
                                                            }
                                                        }
                                                        return m.invoke(c, a);
                                                    } catch (InvocationTargetException e) {
                                                        throw e.getCause();
                                                    }
                                                });
                            },
                            Clock.systemUTC());
        }

        void reset() {
            commits.set(0);
            rollbacks.set(0);
            closes.set(0);
        }

        public void close() {
            if (release != null) release.countDown();
            owner.close();
        }
    }
}
