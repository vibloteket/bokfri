package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierPaymentTermService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierPaymentTermService.Values;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierPaymentTermAdapterTest {
    static final Values PROPOSED = new Values("60");

    @Test
    void bothFamiliesPreviewAndChangeOnlyPaymentTermWithoutDateRecalculationOrDefaults()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().paymentTerm(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                assertEquals(new Values("30"), loaded.source().values());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL, loaded.source().dates().invoiceDate());
                assertThrows(
                        UnsupportedOperationException.class, () -> loaded.source().terms().clear());
                var preview = adapter.preview(loaded, PROPOSED);
                assertEquals(before, f.snapshot());
                assertTrue(preview.plan().changed());
                assertEquals(new Values("30"), preview.plan().before());
                assertEquals(PROPOSED, preview.plan().after());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, header, PROPOSED);
                assertEquals("60", f.stored(credit, id).getPaymentTerm().getName());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL_DUE,
                        f.stored(credit, id).getLocalDueDate());
            }
    }

    @Test
    void onlyChangedTermColumnIsWrittenAndNoOpDoesNotIssueAnyUpdate() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                List<String> sql = new ArrayList<>();
                var wrapped =
                        SupplierJournalGuiFixture.wrap(
                                f.anchor,
                                (method, args) -> {
                                    if (method.getName().equals("prepareStatement")
                                            && args[0].toString().startsWith("UPDATE "))
                                        sql.add(args[0].toString());
                                    return SupplierJournalGuiFixture.invoke(f.anchor, method, args);
                                });
                var service = new NormalizedSupplierPaymentTermService(wrapped);
                var loaded = service.load(71, kind, id);
                service.confirm(service.preview(loaded, loaded.values()));
                assertTrue(sql.isEmpty());
                service.confirm(service.preview(loaded, PROPOSED));
                assertEquals(1, sql.size());
                assertTrue(sql.getFirst().contains(" SET payment_term_name=? WHERE"));
                f.anchor.rollback();
            }
    }

    @Test
    void exactLiveNamesAndExplicitNullAreAcceptedButAliasesAndUnknownsAreRefused()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().paymentTerm(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                for (String bad :
                        List.of("", " ", "missing", "60 ", "net60", "60\n", "x".repeat(256))) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> adapter.preview(loaded, new Values(bad)));
                    assertEquals(before, f.snapshot());
                }
                adapter.confirm(adapter.preview(loaded, new Values("NET60")));
                assertEquals("NET60", adapter.load().source().values().paymentTerm());
                var current = adapter.load();
                var old = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, kind == Kind.CREDIT, id);
                adapter.confirm(adapter.preview(current, new Values(null)));
                assertNarrow(f, kind == Kind.CREDIT, id, old, header, new Values(null));
            }
    }

    @Test
    void catalogueDescriptionNameInsertionAndDeletionInvalidateBothPreviewAndConfirmEvenForNoOps()
            throws Exception {
        for (String change : List.of("description", "delete", "insert", "rename"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, false);
                var adapter = f.session().paymentTerm(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, PROPOSED);
                var noop = adapter.preview(loaded, loaded.source().values());
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            switch (change) {
                                case "description" ->
                                        "UPDATE payment_term SET description='Changed' WHERE"
                                                + " name='60'";
                                case "delete" -> "DELETE FROM payment_term WHERE name='60'";
                                case "rename" ->
                                        "UPDATE payment_term SET name='61' WHERE name='60'";
                                default ->
                                        "INSERT INTO payment_term(name,description)"
                                                + " VALUES('90',NULL)";
                            });
                }
                f.anchor.commit();
                var old = f.snapshot();
                assertThrows(SQLException.class, () -> adapter.preview(loaded, PROPOSED));
                assertThrows(SQLException.class, () -> adapter.confirm(preview));
                assertThrows(SQLException.class, () -> adapter.confirm(noop));
                assertEquals(old, f.snapshot());
            }
    }

    @Test
    void historicalNullBlankControlNamesAndInvalidDatesRemainRawUntilExplicitlyChanged()
            throws Exception {
        for (var kind : Kind.values())
            for (String term : java.util.Arrays.asList(null, "", " ", "old\nterm"))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, kind == Kind.CREDIT);
                    if (term != null) {
                        try (var s =
                                f.anchor.prepareStatement(
                                        "INSERT INTO payment_term(name,description)"
                                            + " VALUES(?,NULL)")) {
                            s.setString(1, term);
                            s.executeUpdate();
                        }
                    }
                    try (var s =
                            f.anchor.prepareStatement(
                                    "UPDATE "
                                            + table(kind == Kind.CREDIT)
                                            + " SET payment_term_name=?,invoice_date=NULL,due_date=NULL,currency_rate=-1"
                                            + " WHERE id=?")) {
                        s.setString(1, term);
                        s.setLong(2, id);
                        s.executeUpdate();
                    }
                    f.anchor.commit();
                    var adapter = f.session().paymentTerm(kind, id);
                    var loaded = adapter.load();
                    var old = f.snapshot();
                    var noop = adapter.preview(loaded, loaded.source().values());
                    assertFalse(noop.plan().changed());
                    adapter.confirm(noop);
                    assertEquals(old, f.snapshot());
                    var header =
                            SupplierDocumentMetadataAdapterTest.header(f, kind == Kind.CREDIT, id);
                    adapter.confirm(adapter.preview(adapter.load(), PROPOSED));
                    assertNarrow(f, kind == Kind.CREDIT, id, old, header, PROPOSED);
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
                    var adapter = f.session().paymentTerm(kind, id);
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
                    var adapter = f.session().paymentTerm(Kind.INVOICE, id);
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
                    var adapter = f.session().paymentTerm(kind, id);
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
                                    + " ADD CONSTRAINT reject_date CHECK(payment_term_name<>'60')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierPaymentTermService(f.anchor);
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
            var adapter = session.paymentTerm(Kind.INVOICE, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var foreign = session.paymentTerm(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, PROPOSED));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierPaymentTermService(f.anchor);
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
                                    .paymentTerm(Kind.INVOICE, id)
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
                var adapter = session.paymentTerm(Kind.INVOICE, id);
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
                        durable ? "60" : "30",
                        new NormalizedSupplierPaymentTermService(f.anchor)
                                .load(71, Kind.INVOICE, id)
                                .values()
                                .paymentTerm());
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
            var adapter = session.paymentTerm(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            assertFalse(session.isBlocked());
            assertEquals("60", f.stored(true, id).getPaymentTerm().getName());
        }
    }

    @Test
    void
            changingOnlyPaymentTermsKeepsStockAndBalancedAccountingAndPostingPriorPreviewUsesFreshTerm()
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
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                var product = se.swedsoft.bookkeeping.data.SSProduct.forPersistence();
                product.setNumber("P");
                product.setStockProduct(true);
                int stock =
                        new org.fribok.bookkeeping.dataformat.NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product);
                f.anchor.rollback();
                var adapter = f.session().paymentTerm(kind, id);
                adapter.confirm(adapter.preview(adapter.load(), PROPOSED));
                assertEquals(
                        stock,
                        new org.fribok.bookkeeping.dataformat.NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
                var fresh =
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                assertEquals(old.summary().debit(), fresh.summary().debit());
                assertEquals(old.summary().credit(), fresh.summary().credit());
                assertEquals(0, fresh.summary().debit().compareTo(fresh.summary().credit()));
                journal.post(old);
                assertEquals("60", f.stored(credit, id).getPaymentTerm().getName());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL_DUE,
                        f.stored(credit, id).getLocalDueDate());
            }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        long id = SupplierDatesAdapterTest.document(f, credit);
        try (var s = f.anchor.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO payment_term(name,description) VALUES('60','Sixty"
                            + " days'),('NET60','Named terms')");
        }
        f.anchor.commit();
        return id;
    }

    static String table(boolean credit) {
        return SupplierDatesAdapterTest.table(credit);
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
        expected.put("PAYMENT_TERM_NAME", values.paymentTerm());
        assertEquals(expected, SupplierDocumentMetadataAdapterTest.header(f, credit, id));
        var after = f.snapshot();
        String parent = table(credit).toUpperCase(java.util.Locale.ROOT);
        for (String key : before.keySet()) {
            if (!key.equals(parent)) assertEquals(before.get(key), after.get(key), key);
            else
                assertEquals(
                        before.get(key).stream()
                                .filter(r -> !r.startsWith("[" + id + ","))
                                .toList(),
                        after.get(key).stream().filter(r -> !r.startsWith("[" + id + ",")).toList(),
                        key);
        }
    }
}
