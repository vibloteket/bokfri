package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierDocumentMetadataAdapterTest {
    @Test
    void bothFamiliesLoadReadOnlyAndUpdateOnlyChangedMetadataWithoutTouchingFinancialSnapshots()
            throws Exception {
        for (var kind : Kind.values())
            for (String field : List.of("name", "reference", "both"))
                try (var f = new SupplierJournalGuiFixture();
                        var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = SupplierDocumentDeletionAdapterTest.addGraph(f, credit, 5);
                    f.add(credit, 6, "25");
                    var adapter = f.session().metadata(kind, id);
                    var before = f.snapshot();
                    var header = header(f, credit, id);
                    var loaded = adapter.load();
                    assertEquals(before, f.snapshot());
                    assertEquals(71, loaded.source().companyId());
                    assertEquals(id, loaded.source().id());
                    assertEquals(5, loaded.source().number());
                    assertEquals(kind, loaded.source().kind());
                    String name =
                            field.equals("reference")
                                    ? loaded.source().supplierName()
                                    : "  L'é Ö supplier  ";
                    String reference =
                            field.equals("name") ? loaded.source().reference() : " Ref '42' ";
                    adapter.save(loaded, name, reference);
                    assertNarrow(f, credit, id, before, header, name, reference);
                }
    }

    @Test
    void nullEmptyWhitespaceAndHistoricalControlValuesArePreservedUnlessExplicitlyChanged()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = f.add(credit, 5, "100");
                setRaw(f, credit, id, null, " old\tref\n ");
                var before = f.snapshot();
                var adapter = f.session().metadata(kind, id);
                var loaded = adapter.load();
                assertNull(loaded.source().supplierName());
                assertEquals(" old\tref\n ", loaded.source().reference());
                adapter.save(loaded, loaded.source().supplierName(), loaded.source().reference());
                assertEquals(before, f.snapshot());
                loaded = adapter.load();
                adapter.save(loaded, "", loaded.source().reference());
                assertEquals("", header(f, credit, id).get("SUPPLIER_NAME"));
                loaded = adapter.load();
                adapter.save(loaded, "  exact  ", "");
                assertEquals("", header(f, credit, id).get("REFERENCE_NUMBER"));
                loaded = adapter.load();
                adapter.save(loaded, null, null);
                assertNull(header(f, credit, id).get("SUPPLIER_NAME"));
                assertNull(header(f, credit, id).get("REFERENCE_NUMBER"));
            }
    }

    @Test
    void bookedExportedMissingForeignAndWrongYearSelectionsAreRefusedWithoutWrites()
            throws Exception {
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = f.add(credit, 5, "100");
                    var adapter = f.session().metadata(kind, id);
                    var loaded = adapter.load();
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET "
                                        + flag
                                        + "=true WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, adapter::load);
                    assertThrows(SQLException.class, () -> adapter.save(loaded, "Changed", null));
                    assertThrows(
                            SQLException.class,
                            () ->
                                    new NormalizedSupplierDocumentMetadataService(f.anchor)
                                            .load(72, kind, id));
                    assertThrows(SQLException.class, () -> f.session().metadata(kind, -1).load());
                    assertThrows(
                            SQLException.class,
                            () ->
                                    new SupplierDocumentGuiSession(
                                                    f::open,
                                                    f.gate,
                                                    71,
                                                    12,
                                                    SupplierJournalGuiFixture.CLOCK)
                                            .metadata(kind, id)
                                            .load());
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void initialAndLateReferencesBlockEditsWhileForeignReferencesRemainUntouched()
            throws Exception {
        for (String ref : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = f.add(false, 5, "100");
                    var adapter = f.session().metadata(Kind.INVOICE, id);
                    var loaded = adapter.load();
                    SupplierDocumentDeletionAdapterTest.reference(f, ref, company, 5);
                    var before = f.snapshot();
                    if (company == 71) {
                        assertThrows(
                                SQLException.class, () -> adapter.save(loaded, "Changed", null));
                        assertThrows(SQLException.class, adapter::load);
                        assertEquals(before, f.snapshot());
                    } else {
                        var header = header(f, false, id);
                        adapter.save(loaded, "Changed", null);
                        assertNarrow(f, false, id, before, header, "Changed", null);
                    }
                }
    }

    @Test
    void fullSourceChangesInvalidateLoadEvenWhenOnlyAnotherFieldWasEdited() throws Exception {
        for (var kind : Kind.values())
            for (String change : List.of("header", "row", "key", "account", "voucher", "audit"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = SupplierDocumentDeletionAdapterTest.addGraph(f, credit, 5);
                    var adapter = f.session().metadata(kind, id);
                    var loaded = adapter.load();
                    String table = table(credit), owner = table + "_id";
                    if (change.equals("audit"))
                        try (var s =
                                f.anchor.prepareStatement(
                                        "UPDATE "
                                                + table
                                                + "_voucher_row SET edited_at=? WHERE "
                                                + owner
                                                + "=?")) {
                            s.setObject(1, OffsetDateTime.parse("2026-10-01T12:13:14.123456788Z"));
                            s.setLong(2, id);
                            s.executeUpdate();
                        }
                    else
                        try (var s = f.anchor.createStatement()) {
                            String sql =
                                    switch (change) {
                                        case "header" ->
                                                "UPDATE "
                                                        + table
                                                        + " SET reference_number='' WHERE id="
                                                        + id;
                                        case "row" ->
                                                "UPDATE "
                                                        + table
                                                        + "_row SET description='Other edit' WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "key" ->
                                                "UPDATE "
                                                        + table
                                                        + "_row SET row_number=99 WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "account" ->
                                                "UPDATE "
                                                        + table
                                                        + "_default_account SET account_number=NULL"
                                                        + " WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        default ->
                                                "UPDATE "
                                                        + table
                                                        + "_voucher SET description='Other edit'"
                                                        + " WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                    };
                            s.executeUpdate(sql);
                        }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(
                            SQLException.class,
                            () -> adapter.save(loaded, "Changed", loaded.source().reference()),
                            change);
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void lateSqlFailurePreservesEarlierCallerWorkAndAllowsRollbackAndRetryWithoutCommitOwnership()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierDocumentDeletionAdapterTest.addGraph(f, credit, 5);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + " ADD CONSTRAINT reject_metadata CHECK(reference_number IS"
                                    + " NULL OR reference_number<>'reject')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierDocumentMetadataService(f.anchor);
                var loaded = service.load(71, kind, id);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.update(loaded, "Changed", "reject"));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE " + table(credit) + " DROP CONSTRAINT reject_metadata");
                }
                f.anchor.commit();
                var original = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                service.update(loaded, "Changed", "ok");
                f.anchor.rollback();
                assertEquals(original, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                service.update(loaded, "Changed", "ok");
                f.anchor.commit();
                assertEquals("ok", header(f, credit, id).get("REFERENCE_NUMBER"));
                f.anchor.setAutoCommit(true);
                var current = service.load(71, kind, id);
                assertThrows(SQLException.class, () -> service.update(current, "Another", "ok"));
                f.anchor.setAutoCommit(false);
            }
    }

    @Test
    void invalidNewValuesNeverWriteAndUnchangedHistoricalValuesNeedNoRepair() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            setRaw(f, false, id, " old\nname ", "\told-ref");
            var adapter = f.session().metadata(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (String bad : List.of("x".repeat(256), "new\nname", "new\tref", "new\u0000ref")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.save(loaded, bad, loaded.source().reference()));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.save(loaded, loaded.source().supplierName(), bad));
                assertEquals(before, f.snapshot());
            }
            adapter.save(loaded, loaded.source().supplierName(), loaded.source().reference());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void confirmedReplayForeignTokensAndClosedSelectionsNeverCauseAnotherWrite() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
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
            var adapter = session.metadata(Kind.INVOICE, id);
            var loaded = adapter.load();
            assertThrows(
                    SQLException.class,
                    () -> session.metadata(Kind.INVOICE, id).save(loaded, "Changed", null));
            assertEquals(1, opens.get());
            var saved = adapter.save(loaded, "Changed", "Exact");
            assertSame(saved, adapter.save(loaded, "Changed", "Exact"));
            assertThrows(SQLException.class, () -> adapter.save(loaded, "Different", "Exact"));
            assertEquals(2, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.save(loaded, "Changed", "Exact"));
            assertEquals(2, opens.get());
        }
    }

    @Test
    void uncertainCommitBlocksMetadataDeletionJournalsAndReadsOnBothDurabilitySides()
            throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100");
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
                                                    throw new SQLException(
                                                            "Unknown metadata durability");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.metadata(Kind.INVOICE, id);
                var loaded = adapter.load();
                var deletion =
                        session.deletion(
                                org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierDocumentDeletionService.Kind.INVOICE,
                                id);
                var journal = session.journal(SupplierJournalAdapter.Kind.INVOICE);
                assertThrows(SQLException.class, () -> adapter.save(loaded, "Changed", "New"));
                int count = opens.get();
                assertTrue(session.isBlocked());
                assertThrows(SQLException.class, deletion::preview);
                assertThrows(
                        SQLException.class,
                        () ->
                                journal.preview(
                                        SupplierJournalGuiFixture.FROM,
                                        SupplierJournalGuiFixture.TO));
                assertThrows(SQLException.class, session::documents);
                assertThrows(SQLException.class, adapter::load);
                assertEquals(count, opens.get());
                assertEquals(
                        durable ? "Changed" : "Supplier 5",
                        header(f, false, id).get("SUPPLIER_NAME"));
            }
    }

    @Test
    void confirmedCloseFailureStillReturnsTheSameResultWithoutRetryingTheUpdate() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(true, 5, "100");
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
                                            if (count == 2 && method.getName().equals("close"))
                                                throw new SQLException("Confirmed close failure");
                                            return result;
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.metadata(Kind.CREDIT, id);
            var loaded = adapter.load();
            var saved = adapter.save(loaded, null, "");
            assertSame(saved, adapter.save(loaded, null, ""));
            assertFalse(session.isBlocked());
            assertEquals(2, opens.get());
            assertEquals("", header(f, true, id).get("REFERENCE_NUMBER"));
        }
    }

    static String table(boolean credit) {
        return credit ? "supplier_credit_invoice" : "supplier_invoice";
    }

    static void setRaw(
            SupplierJournalGuiFixture f, boolean credit, long id, String name, String reference)
            throws Exception {
        try (var s =
                f.anchor.prepareStatement(
                        "UPDATE "
                                + table(credit)
                                + " SET supplier_name=?,reference_number=? WHERE id=?")) {
            s.setString(1, name);
            s.setString(2, reference);
            s.setLong(3, id);
            s.executeUpdate();
        }
        f.anchor.commit();
    }

    static Map<String, String> header(SupplierJournalGuiFixture f, boolean credit, long id)
            throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (var s = f.anchor.prepareStatement("SELECT * FROM " + table(credit) + " WHERE id=?")) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                    values.put(r.getMetaData().getColumnName(i), r.getString(i));
            }
        }
        f.anchor.rollback();
        return values;
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            Map<String, String> oldHeader,
            String name,
            String reference)
            throws Exception {
        Map<String, String> expected = new LinkedHashMap<>(oldHeader);
        expected.put("SUPPLIER_NAME", name);
        expected.put("REFERENCE_NUMBER", reference);
        assertEquals(expected, header(f, credit, id));
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
