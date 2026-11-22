package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationVoucherRegisterTest {
    static long insert(Fixture f, int year, int number, String description) throws Exception {
        try (var c = f.open();
                var q =
                        c.prepareStatement(
                                "INSERT INTO"
                                    + " voucher(accounting_year_id,number,voucher_date,description)"
                                    + " SELECT id,?,DATE '2026-06-15',? FROM accounting_year WHERE"
                                    + " legacy_id=?",
                                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            q.setInt(1, number);
            q.setString(2, description);
            q.setInt(3, year);
            assertEquals(1, q.executeUpdate());
            try (var r = q.getGeneratedKeys()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    static long historical(Fixture f) throws Exception {
        NormalizedApplicationVoucherDimensionsTest.dimensions(f);
        long id = insert(f, 11, 1, "<html>literal\nheader");
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO voucher_row(voucher_id,accounting_year_id,row_number,"
                            + "account_id,project_number,result_unit_number,debit,credit,edited_at,"
                            + "edited_signature,crossed,added) SELECT "
                            + id
                            + ",y.id,7,a.id,'P-common','U-common',1.234567890123456789,NULL,"
                            + "TIMESTAMP '2026-06-15 12:34:56.123456+02:00',"
                            + "'<html>signature',TRUE,TRUE FROM accounting_year y JOIN account a ON"
                            + " a.accounting_year_id=y.id AND a.number=4010 WHERE y.legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO"
                        + " voucher_row(voucher_id,accounting_year_id,row_number,project_number,result_unit_number,debit,credit,crossed,added)"
                        + " SELECT "
                            + id
                            + ",id,0,'gone','',NULL,0,FALSE,FALSE FROM accounting_year WHERE"
                            + " legacy_id=11");
        }
        return id;
    }

    @Test
    void rawOrderedRowsNullsPrecisionFlagsAndCurrentCompanyLabelsArePreservedWithoutLegacySql()
            throws Exception {
        try (var f = new Fixture();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            long id = historical(f);
            try (var observed = new Observed(f)) {
                var reader = observed.owner.select(71, 11).voucherRegister();
                observed.reset();
                var before = f.snapshot();
                var page = reader.page(null);
                assertEquals(1, page.headers().size());
                assertNull(page.nextBefore());
                var detail = reader.detail(id);
                assertEquals("<html>literal\nheader", detail.header().description());
                assertEquals(
                        List.of(0, 7),
                        detail.rows().stream().map(NormalizedVoucherRegister.Row::number).toList());
                var absent = detail.rows().get(0);
                assertNull(absent.accountId());
                assertNull(absent.accountNumber());
                assertNull(absent.accountDescription());
                assertNull(absent.debit());
                assertEquals(0, absent.credit().signum());
                assertEquals("gone", absent.project());
                assertEquals("", absent.unit());
                assertFalse(absent.projectExists());
                assertFalse(absent.unitExists());
                assertNull(absent.editedAt());
                assertNull(absent.signature());
                var raw = detail.rows().get(1);
                assertEquals(4010, raw.accountNumber());
                assertEquals(0, new BigDecimal("1.234567890123456789").compareTo(raw.debit()));
                assertNull(raw.credit());
                assertEquals("P-common", raw.project());
                assertEquals("Local project", raw.projectName());
                assertTrue(raw.projectExists());
                assertEquals("Local unit", raw.unitName());
                assertTrue(raw.unitExists());
                assertTrue(raw.crossed());
                assertTrue(raw.added());
                assertEquals(
                        OffsetDateTime.parse("2026-06-15T12:34:56.123456+02:00").toInstant(),
                        raw.editedAt().toInstant());
                assertEquals("<html>signature", raw.signature());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                // Each read also rolls back the owner's preceding schema-validation transaction.
                assertEquals(4, observed.rollbacks.get());
                assertEquals(2, observed.closes.get());
                assertThrows(UnsupportedOperationException.class, () -> detail.rows().clear());
                assertThrows(UnsupportedOperationException.class, () -> page.headers().clear());
            }
        }
    }

    @Test
    void keysetPagesAreBoundedDescendingExactAndDoNotMixYearsOrSameNumbers() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            for (int number = -102; number <= 102; number++) insert(f, 11, number, "local");
            insert(f, 12, 102, "foreign");
            var reader = owner.select(71, 11).voucherRegister();
            List<Integer> numbers = new ArrayList<>();
            var first = reader.page(null);
            assertEquals(100, first.headers().size());
            assertEquals(3, first.nextBefore());
            numbers.addAll(
                    first.headers().stream()
                            .map(NormalizedVoucherRegister.Header::number)
                            .toList());
            var second = reader.page(first.nextBefore());
            assertEquals(100, second.headers().size());
            assertEquals(-97, second.nextBefore());
            numbers.addAll(
                    second.headers().stream()
                            .map(NormalizedVoucherRegister.Header::number)
                            .toList());
            var third = reader.page(second.nextBefore());
            assertEquals(5, third.headers().size());
            assertNull(third.nextBefore());
            numbers.addAll(
                    third.headers().stream()
                            .map(NormalizedVoucherRegister.Header::number)
                            .toList());
            assertEquals(
                    java.util.stream.IntStream.iterate(102, i -> i - 1).limit(205).boxed().toList(),
                    numbers);
            assertTrue(reader.page(Integer.MIN_VALUE).headers().isEmpty());
            assertNull(reader.page(Integer.MIN_VALUE).nextBefore());
        }
    }

    @Test
    void correctionReferencesNullHeadersAndEmptyVouchersRemainRawNotRepaired() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            long original = insert(f, 11, 9, null), correction = insert(f, 11, 10, "");
            try (var c = f.open();
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "UPDATE voucher SET corrected_by_voucher_id="
                                + correction
                                + ",voucher_date=NULL WHERE id="
                                + original);
                s.executeUpdate(
                        "UPDATE voucher SET corrects_voucher_id="
                                + original
                                + " WHERE id="
                                + correction);
            }
            var reader = owner.select(71, 11).voucherRegister();
            var before = f.snapshot();
            var detail = reader.detail(original);
            assertNull(detail.header().description());
            assertNull(detail.header().date());
            assertNull(detail.header().correctsId());
            assertEquals(correction, detail.header().correctedById());
            assertEquals(10, detail.header().correctedByNumber());
            assertTrue(detail.rows().isEmpty());
            var other = reader.detail(correction);
            assertEquals(original, other.header().correctsId());
            assertEquals(9, other.header().correctsNumber());
            assertEquals("", other.header().description());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void sqlIdentityCannotReadForeignYearAndRefreshReflectsEditsWithoutRewritingSnapshots()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            long local = insert(f, 11, 1, "before"), foreign = insert(f, 12, 1, "foreign");
            var reader = owner.select(71, 11).voucherRegister();
            var old = reader.page(null);
            assertThrows(SQLException.class, () -> reader.detail(foreign));
            assertThrows(SQLException.class, () -> reader.detail(Long.MAX_VALUE));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate("UPDATE voucher SET description='after' WHERE id=" + local);
            }
            assertEquals("before", old.headers().get(0).description());
            assertEquals("after", reader.detail(local).header().description());
            assertEquals("after", reader.page(null).headers().get(0).description());
            try (var c = f.open()) {
                c.createStatement().executeUpdate("DELETE FROM voucher WHERE id=" + local);
            }
            assertThrows(SQLException.class, () -> reader.detail(local));
            assertTrue(reader.page(null).headers().isEmpty());
        }
    }

    @Test
    void manualDimensionedPostingReopensUnderNewOwnerWithoutImplicitRegisterChanges()
            throws Exception {
        try (var f = new Fixture()) {
            NormalizedApplicationVoucherDimensionsTest.dimensions(f);
            try (var owner = f.owner()) {
                var lease = owner.select(71, 11);
                var old = lease.voucherRegister().page(null);
                lease.vouchers()
                        .post(
                                lease.vouchers()
                                        .preview(
                                                NormalizedApplicationVoucherDimensionsTest
                                                        .draft()));
                assertTrue(old.headers().isEmpty());
            }
            try (var owner = f.owner();
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reader = owner.select(71, 11).voucherRegister();
                var detail = reader.detail(reader.page(null).headers().get(0).id());
                assertEquals(2, detail.rows().size());
                assertEquals("P-common", detail.rows().get(0).project());
                assertEquals("U-common", detail.rows().get(1).unit());
            }
        }
    }

    @Test
    void changedYearScopeIsRefusedAndClosedReplacedReadersRefuseBeforeOpeningConnections()
            throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            var reader = observed.owner.select(71, 11).voucherRegister();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE"
                                        + " legacy_id=11");
            }
            assertThrows(SQLException.class, () -> reader.page(null));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET ends_on=DATE '2026-12-31' WHERE"
                                        + " legacy_id=11");
            }
            observed.owner.select(72, 12);
            int count = observed.opens.get();
            assertThrows(SQLException.class, () -> reader.page(null));
            assertThrows(SQLException.class, () -> reader.detail(1));
            assertEquals(count, observed.opens.get());
            var current = observed.owner.select(71, 11).voucherRegister();
            current.close();
            count = observed.opens.get();
            assertThrows(SQLException.class, () -> current.page(null));
            assertEquals(count, observed.opens.get());
            var finalReader = observed.owner.select(71, 11).voucherRegister();
            observed.owner.close();
            count = observed.opens.get();
            assertThrows(SQLException.class, () -> finalReader.page(null));
            assertEquals(count, observed.opens.get());
        }
    }

    @Test
    void uncertainManualCommitBlocksRegisterBeforeOpeningSql() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationManualVoucherTest.accounts(f);
            var lease = owner.select(71, 11);
            var reader = lease.voucherRegister();
            var token = lease.vouchers().preview(NormalizedApplicationManualVoucherTest.draft());
            f.failure = "after";
            assertThrows(SQLException.class, () -> lease.vouchers().post(token));
            assertTrue(owner.isBlocked());
            int count = f.opens.get();
            assertThrows(SQLException.class, () -> reader.page(null));
            assertThrows(SQLException.class, () -> reader.detail(1));
            assertEquals(count, f.opens.get());
        }
    }

    @Test
    void failedReadsRollbackCloseAndRecoverWithoutUnknownCommitOrAnyCommitAttempt()
            throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            var reader = observed.owner.select(71, 11).voucherRegister();
            for (String mode : List.of("query", "rollback", "close")) {
                observed.reset();
                observed.failure = mode;
                assertThrows(SQLException.class, () -> reader.page(null));
                assertEquals(0, observed.commits.get());
                assertEquals(1, observed.closes.get());
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                assertTrue(reader.page(null).headers().isEmpty());
            }
        }
    }

    @Test
    void selectionWaitsForReadGateAndOwnerClosureDiscardsPendingReadResult() throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f);
                var executor = Executors.newFixedThreadPool(2)) {
            var reader = observed.owner.select(71, 11).voucherRegister();
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            var read = executor.submit(() -> reader.page(null));
            assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
            var selecting = executor.submit(() -> observed.owner.select(72, 12));
            Thread.sleep(100);
            assertFalse(selecting.isDone());
            observed.release.countDown();
            assertTrue(read.get(5, TimeUnit.SECONDS).headers().isEmpty());
            selecting.get(5, TimeUnit.SECONDS);
            var nextReader = observed.owner.select(71, 11).voucherRegister();
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            var pending =
                    executor.submit(
                            () -> assertThrows(SQLException.class, () -> nextReader.page(null)));
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
                                boolean[] registerRead = {false};
                                return (Connection)
                                        Proxy.newProxyInstance(
                                                Connection.class.getClassLoader(),
                                                new Class<?>[] {Connection.class},
                                                (p, m, a) -> {
                                                    try {
                                                        if (m.getName().equals("commit")) {
                                                            commits.incrementAndGet();
                                                            fail("Register must never commit");
                                                        }
                                                        if (m.getName().equals("rollback")) {
                                                            rollbacks.incrementAndGet();
                                                            if (failure.equals("rollback")
                                                                    && registerRead[0])
                                                                throw new SQLException(
                                                                        "read rollback");
                                                        }
                                                        if (m.getName().equals("close")) {
                                                            closes.incrementAndGet();
                                                            c.close();
                                                            if (failure.equals("close"))
                                                                throw new SQLException(
                                                                        "read close");
                                                            return null;
                                                        }
                                                        if (m.getName()
                                                                .equals("prepareStatement")) {
                                                            assertFalse(
                                                                    ((String) a[0])
                                                                            .matches(
                                                                                    "(?is)\\s*(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)\\b.*"),
                                                                    "Register must never mutate"
                                                                        + " SQL");
                                                        }
                                                        if (m.getName().equals("prepareStatement")
                                                                && ((String) a[0])
                                                                        .startsWith(
                                                                                "SELECT v.id")) {
                                                            registerRead[0] = true;
                                                            if (failure.equals("query"))
                                                                throw new SQLException(
                                                                        "read query");
                                                            var signal = entered;
                                                            var wait = release;
                                                            if (signal != null) {
                                                                signal.countDown();
                                                                if (!wait.await(
                                                                        5, TimeUnit.SECONDS))
                                                                    throw new SQLException(
                                                                            "read timeout");
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
