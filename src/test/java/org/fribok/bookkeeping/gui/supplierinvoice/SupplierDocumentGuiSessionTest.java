package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.CLOCK;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.FROM;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.LegacyGuard;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.TO;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.invoke;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalGuiFixture.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierDocumentGuiSessionTest {
    @Test
    void selectionUsesExplicitCompanyAndYearWithoutWritingSavedSelectionsOrUsingLegacyJdbc()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture();
                var ignored = new LegacyGuard()) {
            var before = f.snapshot();
            AtomicInteger opens = new AtomicInteger(),
                    closes = new AtomicInteger(),
                    commits = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                var connection = f.open();
                                return wrap(
                                        connection,
                                        (method, args) -> {
                                            if (method.getName().equals("close"))
                                                closes.incrementAndGet();
                                            if (method.getName().equals("commit"))
                                                commits.incrementAndGet();
                                            return invoke(connection, method, args);
                                        });
                            },
                            f.gate,
                            72,
                            12,
                            CLOCK);
            var selection = session.selection();
            assertEquals(72, selection.companyId());
            assertEquals(12, selection.yearId());
            assertEquals("GUI company 72", selection.companyName());
            assertEquals(FROM, selection.from());
            assertEquals(TO, selection.to());
            assertEquals(1, opens.get());
            assertEquals(1, closes.get());
            assertEquals(0, commits.get());
            assertEquals(before, f.snapshot());
            session.close();
            assertFalse(session.isActive());
            assertFalse(session.isBlocked());
            assertThrows(SQLException.class, session::selection);
            assertEquals(1, opens.get());
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(f::open, f.gate, 71, 12, CLOCK)
                                    .selection());
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(f::open, f.gate, 999, 11, CLOCK)
                                    .selection());
        }
    }

    @Test
    void unknownCommitBlocksBothFamiliesAndNewLeasesInTheSameSelection() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long invoice = f.add(false, 5, "100");
                long credit = f.add(true, 5, "10");
                AtomicInteger opens = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    opens.incrementAndGet();
                                    var connection = f.open();
                                    return wrap(
                                            connection,
                                            (method, args) -> {
                                                if (method.getName().equals("commit")) {
                                                    if (durable) connection.commit();
                                                    throw new SQLException("Unknown durability");
                                                }
                                                return invoke(connection, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                CLOCK);
                var invoices = session.journal(SupplierJournalAdapter.Kind.INVOICE);
                var credits = session.journal(SupplierJournalAdapter.Kind.CREDIT);
                var invoicePreview = invoices.preview(FROM, TO);
                var creditPreview = credits.preview(FROM, TO);
                assertThrows(SQLException.class, () -> invoices.post(invoicePreview));
                assertTrue(session.isBlocked());
                assertTrue(invoices.isBlocked());
                assertTrue(credits.isBlocked());
                assertThrows(SQLException.class, () -> credits.post(creditPreview));
                assertThrows(SQLException.class, () -> credits.preview(FROM, TO));
                assertThrows(
                        SQLException.class,
                        () -> session.journal(SupplierJournalAdapter.Kind.INVOICE));
                assertThrows(SQLException.class, session::selection);
                assertEquals(3, opens.get());
                assertEquals(durable, f.stored(false, invoice).isEntered());
                assertFalse(f.stored(true, credit).isEntered());
                assertEquals(durable ? 1 : 0, f.voucherCount());
            }
    }

    @Test
    void sharedGateSerializesSelectionRequestsAndClosedWaitingLeasesCannotOpen() throws Exception {
        try (var f = new SupplierJournalGuiFixture();
                var executor = Executors.newFixedThreadPool(2)) {
            f.add(false, 5, "100");
            f.add(true, 5, "10");
            var before = f.snapshot();
            CountDownLatch opening = new CountDownLatch(1),
                    release = new CountDownLatch(1),
                    secondStarted = new CountDownLatch(1);
            AtomicInteger opens = new AtomicInteger();
            var first =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                opening.countDown();
                                await(release);
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            CLOCK);
            var waiting =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            CLOCK);
            var invoices = first.journal(SupplierJournalAdapter.Kind.INVOICE);
            var credits = waiting.journal(SupplierJournalAdapter.Kind.CREDIT);
            var read = executor.submit(() -> invoices.preview(FROM, TO));
            try {
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                var blocked =
                        executor.submit(
                                () -> {
                                    secondStarted.countDown();
                                    return credits.preview(FROM, TO);
                                });
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                waiting.close();
                release.countDown();
                read.get(10, TimeUnit.SECONDS);
                assertInstanceOf(
                        SQLException.class,
                        assertThrows(
                                        java.util.concurrent.ExecutionException.class,
                                        () -> blocked.get(10, TimeUnit.SECONDS))
                                .getCause());
                assertEquals(1, opens.get());
                assertEquals(before, f.snapshot());
            } finally {
                release.countDown();
                first.close();
                waiting.close();
            }
        }
    }

    @Test
    void missingOrTamperedSchemaAndLegacyFormatAreRefusedWithoutRepairOrMigration()
            throws Exception {
        for (String changed : List.of("legacy", "missing-v21", "checksum"))
            try (var f = new SupplierJournalGuiFixture()) {
                f.add(false, 5, "100");
                if (changed.equals("legacy")) DataFormatManager.recordVersion(f.anchor, 2);
                else
                    try (var statement = f.anchor.createStatement()) {
                        statement.executeUpdate(
                                changed.equals("missing-v21")
                                        ? "DELETE FROM bokfri_schema_history WHERE version=21"
                                        : "UPDATE bokfri_schema_history SET script_sha256='bad'"
                                                + " WHERE version=21");
                    }
                f.anchor.commit();
                var before = f.snapshot();
                var session = f.session();
                assertThrows(SQLException.class, session::selection);
                assertThrows(
                        SQLException.class,
                        () ->
                                session.journal(SupplierJournalAdapter.Kind.INVOICE)
                                        .preview(FROM, TO));
                assertEquals(before, f.snapshot());
                assertFalse(session.isBlocked());
            }
    }

    @Test
    void nonFreshFactoryConnectionIsClosedWithoutCommittingOrExplicitlyRollingBackCallerWork()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            AtomicInteger commits = new AtomicInteger(),
                    rollbacks = new AtomicInteger(),
                    closes = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                var connection = f.open();
                                connection.setAutoCommit(false);
                                return wrap(
                                        connection,
                                        (method, args) -> {
                                            if (method.getName().equals("commit"))
                                                commits.incrementAndGet();
                                            if (method.getName().equals("rollback"))
                                                rollbacks.incrementAndGet();
                                            if (method.getName().equals("close"))
                                                closes.incrementAndGet();
                                            return invoke(connection, method, args);
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            CLOCK);
            assertThrows(SQLException.class, session::selection);
            assertEquals(0, commits.get());
            assertEquals(0, rollbacks.get());
            assertEquals(1, closes.get());
            assertFalse(session.isBlocked());
            assertEquals(0, f.voucherCount());
        }
    }

    static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Test latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SQLException(failure);
        }
    }
}
