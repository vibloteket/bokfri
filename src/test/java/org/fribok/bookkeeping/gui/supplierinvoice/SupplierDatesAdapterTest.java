package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Values;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierJournalService;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierDatesAdapterTest {
    static final LocalDate ORIGINAL = LocalDate.of(2026, 2, 2),
            ORIGINAL_DUE = LocalDate.of(2026, 3, 4),
            NEXT = LocalDate.of(2026, 4, 1),
            NEXT_DUE = LocalDate.of(2026, 5, 1);
    static final Values PROPOSED = new Values(NEXT, NEXT_DUE);

    @Test
    void bothFamiliesPreviewWithoutWritesAndUpdateOnlyRequestedDatesPreservingEveryOtherSqlValue()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().dates(kind, id);
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                var loaded = adapter.load();
                assertEquals(new Values(ORIGINAL, ORIGINAL_DUE), loaded.source().values());
                assertEquals("30", loaded.source().paymentTerm());
                assertEquals(before, f.snapshot());
                var preview = adapter.preview(loaded, PROPOSED);
                assertEquals(before, f.snapshot());
                assertEquals(loaded.source().values(), preview.plan().before());
                assertEquals(PROPOSED, preview.plan().after());
                assertTrue(preview.plan().changed());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, header, PROPOSED);
                var stored = f.stored(credit, id);
                assertEquals(NEXT, stored.getLocalDate());
                assertEquals(NEXT_DUE, stored.getLocalDueDate());
            }
    }

    @Test
    void independentDateEditsNeverRecalculatePaymentTermsOrRewriteTheOtherColumn()
            throws Exception {
        for (var kind : Kind.values())
            for (boolean invoice : List.of(false, true))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    List<String> sql = new ArrayList<>();
                    var wrapped =
                            SupplierJournalGuiFixture.wrap(
                                    f.anchor,
                                    (method, args) -> {
                                        if (method.getName().equals("prepareStatement")
                                                && args[0].toString().startsWith("UPDATE "))
                                            sql.add(args[0].toString());
                                        return SupplierJournalGuiFixture.invoke(
                                                f.anchor, method, args);
                                    });
                    var service = new NormalizedSupplierDatesService(wrapped);
                    var source = service.load(71, kind, id);
                    Values next =
                            invoice
                                    ? new Values(ORIGINAL.minusDays(1), ORIGINAL_DUE)
                                    : new Values(ORIGINAL, NEXT_DUE);
                    var before = f.snapshot();
                    var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                    service.confirm(service.preview(source, next));
                    f.anchor.commit();
                    assertEquals(1, sql.size());
                    assertTrue(
                            sql.getFirst()
                                    .contains(
                                            invoice
                                                    ? " SET invoice_date=? WHERE"
                                                    : " SET due_date=? WHERE"));
                    assertNarrow(f, credit, id, before, header, next);
                }
    }

    @Test
    void invalidDatesAreRejectedWithoutWritesWhileNoOpHistoricalNullsAndReversedDatesRemainRaw()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().dates(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                for (var bad :
                        List.of(
                                new Values(null, NEXT_DUE),
                                new Values(NEXT, null),
                                new Values(null, null),
                                new Values(NEXT, NEXT.minusDays(1)),
                                new Values(LocalDate.MIN, NEXT_DUE),
                                new Values(NEXT, LocalDate.MAX),
                                new Values(LocalDate.of(0, 1, 1), NEXT_DUE),
                                new Values(NEXT, LocalDate.of(10000, 1, 1)))) {
                    assertThrows(
                            IllegalArgumentException.class, () -> adapter.preview(loaded, bad));
                    assertEquals(before, f.snapshot());
                }
                for (String dates :
                        List.of(
                                "invoice_date=NULL,due_date=NULL",
                                "invoice_date=NULL,due_date=DATE '2026-01-01'",
                                "invoice_date=DATE '2026-02-02',due_date=DATE '2026-01-01'")) {
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + table(kind == Kind.CREDIT)
                                        + " SET "
                                        + dates
                                        + " WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var historical = adapter.load();
                    var old = f.snapshot();
                    var plan = adapter.preview(historical, historical.source().values());
                    assertFalse(plan.plan().changed());
                    adapter.confirm(plan);
                    assertEquals(old, f.snapshot());
                }
            }
    }

    @Test
    void exactSupportedDateBoundsAndLeapDaysRoundTripWithoutTimezoneOrCenturyRepair()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().dates(kind, id);
                for (var values :
                        List.of(
                                new Values(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)),
                                new Values(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)),
                                new Values(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1)))) {
                    var loaded = adapter.load();
                    adapter.confirm(adapter.preview(loaded, values));
                    assertEquals(values, adapter.load().source().values());
                }
            }
    }

    @Test
    void
            completeSourceChangesInvalidatePreviewAndSaveIncludingAuditNanosAndUnrelatedFinancialFields()
                    throws Exception {
        for (var kind : Kind.values())
            for (String change :
                    List.of("date", "due", "name", "term", "row", "voucher", "audit", "rate"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().dates(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, PROPOSED);
                    String t = table(credit);
                    if (change.equals("audit"))
                        try (var s =
                                f.anchor.prepareStatement(
                                        "UPDATE "
                                                + t
                                                + "_voucher_row SET edited_at=? WHERE "
                                                + t
                                                + "_id=?")) {
                            s.setObject(1, OffsetDateTime.parse("2026-10-01T12:13:14.123456788Z"));
                            s.setLong(2, id);
                            s.executeUpdate();
                        }
                    else
                        try (var s = f.anchor.createStatement()) {
                            s.executeUpdate(
                                    switch (change) {
                                        case "date" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET invoice_date=DATE '2026-02-03'"
                                                        + " WHERE id="
                                                        + id;
                                        case "due" ->
                                                "UPDATE " + t + " SET due_date=NULL WHERE id=" + id;
                                        case "name" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET supplier_name='Changed' WHERE id="
                                                        + id;
                                        case "term" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET payment_term_name=NULL WHERE id="
                                                        + id;
                                        case "row" ->
                                                "UPDATE "
                                                        + t
                                                        + "_row SET quantity=2 WHERE "
                                                        + t
                                                        + "_id="
                                                        + id;
                                        case "rate" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET currency_rate=2 WHERE id="
                                                        + id;
                                        default ->
                                                "UPDATE "
                                                        + t
                                                        + "_voucher SET description='Changed' WHERE"
                                                        + " "
                                                        + t
                                                        + "_id="
                                                        + id;
                                    });
                        }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, () -> adapter.preview(loaded, PROPOSED));
                    assertThrows(SQLException.class, () -> adapter.confirm(preview));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void
            liveReferencesAndBookedBankExportedStatesAreRefusedWithoutDetachmentOrForeignCompanyLeakage()
                    throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false);
                    var adapter = f.session().dates(Kind.INVOICE, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, PROPOSED);
                    SupplierDocumentDeletionAdapterTest.reference(f, reference, company, 5);
                    var before = f.snapshot();
                    var header = SupplierDocumentMetadataAdapterTest.header(f, false, id);
                    if (company == 71) {
                        assertThrows(SQLException.class, adapter::load);
                        assertThrows(SQLException.class, () -> adapter.preview(loaded, PROPOSED));
                        assertThrows(SQLException.class, () -> adapter.confirm(preview));
                        assertEquals(before, f.snapshot());
                    } else {
                        adapter.confirm(preview);
                        assertNarrow(f, false, id, before, header, PROPOSED);
                    }
                }
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, kind == Kind.CREDIT);
                    var adapter = f.session().dates(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, PROPOSED);
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + table(kind == Kind.CREDIT)
                                        + " SET "
                                        + flag
                                        + "=true WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, adapter::load);
                    assertThrows(SQLException.class, () -> adapter.confirm(preview));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void lateSqlFailurePreservesCallerWorkAndSavepointThenRetryAndOuterRollbackWork()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                String table = table(credit);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE "
                                    + table
                                    + " ADD CONSTRAINT reject_date CHECK(due_date<>DATE"
                                    + " '2026-05-01')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierDatesService(f.anchor);
                var source = service.load(71, kind, id);
                var plan = service.preview(source, PROPOSED);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirm(plan));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("ALTER TABLE " + table + " DROP CONSTRAINT reject_date");
                }
                f.anchor.commit();
                var old = f.snapshot();
                service.confirm(plan);
                f.anchor.rollback();
                assertEquals(old, f.snapshot());
                service.confirm(plan);
                f.anchor.commit();
                assertEquals(PROPOSED, service.load(71, kind, id).values());
            }
    }

    @Test
    void foreignTokensClosedWrongYearAndAutoCommitAreRefusedAndConfirmedReplayOpensNothing()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.dates(Kind.INVOICE, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var foreign = session.dates(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, PROPOSED));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierDatesService(f.anchor);
            var source = service.load(71, Kind.INVOICE, id);
            var noop = service.preview(source, source.values());
            f.anchor.commit();
            f.anchor.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.confirm(noop));
            f.anchor.setAutoCommit(false);
            assertThrows(SQLException.class, () -> service.load(72, Kind.INVOICE, id));
            assertThrows(SQLException.class, () -> service.load(71, Kind.INVOICE, -1));
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(
                                            f::open,
                                            f.gate,
                                            71,
                                            12,
                                            SupplierJournalGuiFixture.CLOCK)
                                    .dates(Kind.INVOICE, id)
                                    .load());
        }
    }

    @Test
    void unknownCommitBlocksEverySupplierFamilyAndEditingRouteOnBothDurabilitySides()
            throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, false);
                AtomicInteger opens = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    opens.incrementAndGet();
                                    var c = f.open();
                                    return SupplierJournalGuiFixture.wrap(
                                            c,
                                            (method, args) -> {
                                                if (method.getName().equals("commit")) {
                                                    if (durable) c.commit();
                                                    throw new SQLException("Unknown date commit");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.dates(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, PROPOSED);
                var amounts =
                        session.rowAmounts(
                                org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierRowAmountsService.Kind.INVOICE,
                                id);
                var metadata =
                        session.metadata(
                                org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierDocumentMetadataService.Kind.INVOICE,
                                id);
                var deletion =
                        session.deletion(
                                org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierDocumentDeletionService.Kind.INVOICE,
                                id);
                var journal = session.journal(SupplierJournalAdapter.Kind.CREDIT);
                assertThrows(SQLException.class, () -> adapter.confirm(preview));
                int count = opens.get();
                assertTrue(session.isBlocked());
                assertThrows(SQLException.class, adapter::load);
                assertThrows(SQLException.class, amounts::load);
                assertThrows(SQLException.class, metadata::load);
                assertThrows(SQLException.class, deletion::preview);
                assertThrows(
                        SQLException.class,
                        () ->
                                journal.preview(
                                        SupplierJournalGuiFixture.FROM,
                                        SupplierJournalGuiFixture.TO));
                assertThrows(SQLException.class, session::documents);
                assertEquals(count, opens.get());
                assertEquals(
                        durable ? NEXT : ORIGINAL,
                        new NormalizedSupplierDatesService(f.anchor)
                                .load(71, Kind.INVOICE, id)
                                .values()
                                .invoiceDate());
                f.anchor.rollback();
            }
    }

    @Test
    void knownCommitCloseFailureKeepsResultAndNeverRepeatsWrites() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, true);
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                int count = opens.incrementAndGet();
                                var c = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        c,
                                        (method, args) -> {
                                            Object result =
                                                    SupplierJournalGuiFixture.invoke(
                                                            c, method, args);
                                            if (count == 3 && method.getName().equals("close"))
                                                throw new SQLException("Confirmed close failure");
                                            return result;
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.dates(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            assertFalse(session.isBlocked());
            assertEquals(NEXT, f.stored(true, id).getLocalDate());
        }
    }

    @Test
    void
            editedDatesMoveJournalSelectionAndInvalidateOldJournalWhileStoredVoucherDatesRemainUntouched()
                    throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var journal =
                        f.session()
                                .journal(
                                        credit
                                                ? SupplierJournalAdapter.Kind.CREDIT
                                                : SupplierJournalAdapter.Kind.INVOICE);
                var old =
                        journal.preview(SupplierJournalGuiFixture.FROM, LocalDate.of(2026, 3, 31));
                assertEquals(
                        List.of(id),
                        old.summary().documents().stream()
                                .map(SupplierJournalAdapter.Document::id)
                                .toList());
                var adapter = f.session().dates(kind, id);
                var loaded = adapter.load();
                adapter.confirm(adapter.preview(loaded, PROPOSED));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                journal.preview(
                                        SupplierJournalGuiFixture.FROM, LocalDate.of(2026, 3, 31)));
                assertThrows(
                        NormalizedSupplierJournalService.EmptyJournalException.class,
                        () -> journal.post(old));
                var fresh = journal.preview(NEXT, NEXT_DUE);
                assertEquals(
                        List.of(id),
                        fresh.summary().documents().stream()
                                .map(SupplierJournalAdapter.Document::id)
                                .toList());
                journal.post(fresh);
                assertEquals(1, f.voucherCount());
            }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        long id = SupplierRowAmountsAdapterTest.document(f, credit, 5);
        try (var s = f.anchor.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO payment_term(name,description) VALUES('30','Thirty days')");
            s.executeUpdate(
                    "UPDATE "
                            + table(credit)
                            + " SET invoice_date=DATE '2026-02-02',due_date=DATE"
                            + " '2026-03-04',payment_term_name='30' WHERE id="
                            + id);
        }
        f.anchor.commit();
        return id;
    }

    static String table(boolean credit) {
        return SupplierRowAmountsAdapterTest.table(credit);
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            Map<String, String> oldHeader,
            Values values)
            throws Exception {
        Map<String, String> expected = new LinkedHashMap<>(oldHeader);
        expected.put(
                "INVOICE_DATE",
                values.invoiceDate() == null ? null : values.invoiceDate().toString());
        expected.put("DUE_DATE", values.dueDate() == null ? null : values.dueDate().toString());
        assertEquals(expected, SupplierDocumentMetadataAdapterTest.header(f, credit, id));
        var after = f.snapshot();
        String parent = table(credit).toUpperCase(java.util.Locale.ROOT);
        for (String key : before.keySet()) {
            if (!key.equals(parent)) {
                assertEquals(before.get(key), after.get(key), key);
            } else {
                assertEquals(
                        before.get(key).stream()
                                .filter(r -> !r.startsWith("[" + id + ","))
                                .toList(),
                        after.get(key).stream().filter(r -> !r.startsWith("[" + id + ",")).toList(),
                        key);
            }
        }
    }
}
