package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.RowValue;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSProduct;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierRowDescriptionAdapterTest {
    @Test
    void bothFamiliesPreviewExactDescriptionsAndOnlyChangedRowDescriptionCellsAreWritten()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().rowDescriptions(kind, id);
                var before = f.snapshot();
                var old = rows(f, credit, id);
                var loaded = adapter.load();
                assertEquals(
                        List.of(0, 7),
                        loaded.source().rows().stream().map(row -> row.key()).toList());
                assertEquals("P", loaded.source().rows().getFirst().product());
                assertEquals("historical unit", loaded.source().rows().getFirst().unit());
                var proposal = proposal(loaded, "  Edited Ö  ");
                var preview = adapter.preview(loaded, proposal.reversed());
                assertEquals(before, f.snapshot());
                assertEquals(1, preview.plan().changes().size());
                assertEquals(
                        "  Edited Ö  ", preview.plan().changes().getFirst().after().description());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, old, Map.of(0, "  Edited Ö  "));
            }
    }

    @Test
    void invalidNewDescriptionsLengthsAndRowSetsNeverWriteOrAllocateKeys() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            setRaw(f, false, id, "Original");
            var adapter = f.session().rowDescriptions(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (String invalid :
                    new String[] {
                        null, "", "  ", "x\n", "x\r", "x\t", "x\u0000", "x".repeat(4097)
                    }) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.preview(loaded, proposal(loaded, invalid)));
                assertEquals(before, f.snapshot());
            }
            for (var invalid :
                    List.of(
                            List.<RowValue>of(),
                            List.of(new RowValue(0, "A"), new RowValue(0, "B")),
                            List.of(new RowValue(99, "A"), new RowValue(7, "B")),
                            List.of(
                                    new RowValue(0, "A"),
                                    new RowValue(7, "B"),
                                    new RowValue(9, "C")))) {
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(loaded, invalid));
                assertEquals(before, f.snapshot());
            }
            String maximum = "x".repeat(4096);
            var old = rows(f, false, id);
            adapter.confirm(adapter.preview(loaded, proposal(loaded, maximum)));
            assertNarrow(f, false, id, before, old, Map.of(0, maximum));
        }
    }

    @Test
    void unchangedHistoricalNullBlankAndControlsIssueNoUpdateAndRemainExact() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                AtomicInteger updates = new AtomicInteger();
                var wrapped =
                        SupplierJournalGuiFixture.wrap(
                                f.anchor,
                                (method, args) -> {
                                    if (method.getName().equals("prepareStatement")
                                            && args[0].toString().startsWith("UPDATE "))
                                        updates.incrementAndGet();
                                    return SupplierJournalGuiFixture.invoke(f.anchor, method, args);
                                });
                var service = new NormalizedSupplierDocumentMetadataService(wrapped);
                for (String raw :
                        new String[] {null, "", "  ", "old\ntext\t", "<html>literal</html>"}) {
                    setRaw(f, credit, id, raw);
                    var before = f.snapshot();
                    var source = service.loadRows(71, kind, id);
                    var values =
                            source.rows().stream()
                                    .map(row -> new RowValue(row.key(), row.description()))
                                    .toList();
                    var plan = service.previewRows(source, values);
                    assertTrue(plan.changes().isEmpty());
                    service.confirmRows(plan);
                    f.anchor.commit();
                    assertEquals(before, f.snapshot());
                }
                assertEquals(0, updates.get());
            }
    }

    @Test
    void descriptionEditsDoNotRequireFinancialRepairOrInventRatesTaxPricesOrAccounts()
            throws Exception {
        for (var kind : Kind.values())
            for (String rate : List.of("NULL", "0", "-1"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET currency_rate="
                                        + rate
                                        + ",tax_sum=NULL,rounding_sum=NULL WHERE id="
                                        + id);
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + "_row SET quantity=NULL,unit_price=NULL WHERE "
                                        + table(credit)
                                        + "_id="
                                        + id
                                        + " AND row_number=7");
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    var old = rows(f, credit, id);
                    var adapter = f.session().rowDescriptions(kind, id);
                    var loaded = adapter.load();
                    adapter.confirm(
                            adapter.preview(loaded, proposal(loaded, "Explicit description")));
                    assertNarrow(f, credit, id, before, old, Map.of(0, "Explicit description"));
                }
    }

    @Test
    void lateSecondDescriptionFailureRollsBackFirstAndRetainsCallerWorkThenAllowsRetry()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                String t = table(credit);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + t
                                    + "_row ADD CONSTRAINT reject_description CHECK(row_number<>7"
                                    + " OR description<>'Rejected')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierDocumentMetadataService(f.anchor);
                var source = service.loadRows(71, kind, id);
                var plan =
                        service.previewRows(
                                source,
                                List.of(
                                        new RowValue(0, "First changed"),
                                        new RowValue(7, "Rejected")));
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirmRows(plan));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE " + t + "_row DROP CONSTRAINT reject_description");
                }
                f.anchor.commit();
                before = f.snapshot();
                service.confirmRows(plan);
                f.anchor.rollback();
                assertEquals(before, f.snapshot());
                service.confirmRows(plan);
                f.anchor.commit();
                var current = service.loadRows(71, kind, id);
                assertEquals("First changed", current.rows().getFirst().description());
                assertEquals("Rejected", current.rows().get(1).description());
            }
    }

    @Test
    void noOpConfirmationStillRechecksCompleteSourceAndCurrentEligibility() throws Exception {
        for (var kind : Kind.values())
            for (String change : List.of("source", "entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().rowDescriptions(kind, id);
                    var loaded = adapter.load();
                    var plan =
                            adapter.preview(
                                    loaded,
                                    loaded.source().rows().stream()
                                            .map(row -> new RowValue(row.key(), row.description()))
                                            .toList());
                    assertTrue(plan.plan().changes().isEmpty());
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET "
                                        + (change.equals("source")
                                                ? "reference_number='Changed'"
                                                : "entered=true")
                                        + " WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, () -> adapter.confirm(plan));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void descriptionsLeaveStockAndJournalTotalsUnchangedAndPostingPreservesEditedText()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                SSProduct product = SSProduct.forPersistence();
                product.setNumber("P");
                product.setStockProduct(true);
                int stock =
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product);
                f.anchor.rollback();
                var journal =
                        f.session()
                                .journal(
                                        credit
                                                ? SupplierJournalAdapter.Kind.CREDIT
                                                : SupplierJournalAdapter.Kind.INVOICE);
                var original =
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                var adapter = f.session().rowDescriptions(kind, id);
                var loaded = adapter.load();
                adapter.confirm(adapter.preview(loaded, proposal(loaded, "Changed text only")));
                var fresh =
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                assertEquals(original.summary().debit(), fresh.summary().debit());
                assertEquals(original.summary().credit(), fresh.summary().credit());
                assertEquals(0, fresh.summary().debit().compareTo(fresh.summary().credit()));
                // Journal tokens cover generated accounting semantics; nonfinancial row text is
                // reloaded at posting, not replayed from the old presentation snapshot.
                journal.post(original);
                assertEquals(1, f.voucherCount());
                assertEquals("Changed text only", rows(f, credit, id).get(0).get("DESCRIPTION"));
                assertEquals(
                        stock,
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
            }
    }

    @Test
    void initialAndLateReferencesAndProtectedStatesAreRefusedWithoutDetachment() throws Exception {
        for (String ref : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false);
                    var adapter = f.session().rowDescriptions(Kind.INVOICE, id);
                    var loaded = adapter.load();
                    var plan = adapter.preview(loaded, proposal(loaded, "Edited"));
                    SupplierDocumentDeletionAdapterTest.reference(f, ref, company, 5);
                    var before = f.snapshot();
                    if (company == 71) {
                        assertThrows(SQLException.class, adapter::load);
                        assertThrows(
                                SQLException.class,
                                () -> adapter.preview(loaded, proposal(loaded, "Edited")));
                        assertThrows(SQLException.class, () -> adapter.confirm(plan));
                        assertEquals(before, f.snapshot());
                    } else {
                        adapter.confirm(plan);
                        assertEquals(
                                "Edited",
                                new NormalizedSupplierDocumentMetadataService(f.anchor)
                                        .loadRows(71, Kind.INVOICE, id)
                                        .rows()
                                        .getFirst()
                                        .description());
                        f.anchor.rollback();
                    }
                }
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().rowDescriptions(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, proposal(loaded, "Edited"));
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
                    assertThrows(SQLException.class, () -> adapter.confirm(preview));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void completeDocumentChangesInvalidatePreviewAndConfirmationIncludingAuditNanosAndRate()
            throws Exception {
        for (var kind : Kind.values())
            for (String change :
                    List.of("header", "row", "key", "account", "voucher", "audit", "rate"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().rowDescriptions(kind, id);
                    var loaded = adapter.load();
                    var proposed = proposal(loaded, "Edited");
                    var preview = adapter.preview(loaded, proposed);
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
                                                        + "_row SET description='Changed' WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "key" ->
                                                "UPDATE "
                                                        + table
                                                        + "_row SET row_number=8 WHERE "
                                                        + owner
                                                        + "="
                                                        + id
                                                        + " AND row_number=7";
                                        case "account" ->
                                                "UPDATE "
                                                        + table
                                                        + "_default_account SET account_number=NULL"
                                                        + " WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "rate" ->
                                                "UPDATE "
                                                        + table
                                                        + " SET currency_rate=1.2 WHERE id="
                                                        + id;
                                        default ->
                                                "UPDATE "
                                                        + table
                                                        + "_voucher SET description='Changed' WHERE"
                                                        + " "
                                                        + owner
                                                        + "="
                                                        + id;
                                    };
                            s.executeUpdate(sql);
                        }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, () -> adapter.preview(loaded, proposed));
                    assertThrows(SQLException.class, () -> adapter.confirm(preview));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void immutableTokensForeignContextsAndConfirmedReplayNeverCauseAnotherWrite() throws Exception {
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
            var adapter = session.rowDescriptions(Kind.INVOICE, id);
            var loaded = adapter.load();
            assertThrows(UnsupportedOperationException.class, () -> loaded.source().rows().clear());
            var values = new ArrayList<>(proposal(loaded, "Edited"));
            var preview = adapter.preview(loaded, values);
            values.clear();
            assertThrows(
                    UnsupportedOperationException.class, () -> preview.plan().changes().clear());
            var foreign = session.rowDescriptions(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, List.of()));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
        }
    }

    @Test
    void uncertainCommitBlocksAllSupplierActionsOnBothDurabilitySides() throws Exception {
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
                                                    throw new SQLException("Unknown amount commit");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.rowDescriptions(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, proposal(loaded, "Edited"));
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
                var journal = session.journal(SupplierJournalAdapter.Kind.INVOICE);
                assertThrows(SQLException.class, () -> adapter.confirm(preview));
                int count = opens.get();
                assertTrue(session.isBlocked());
                assertThrows(SQLException.class, metadata::load);
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
                var current =
                        new NormalizedSupplierDocumentMetadataService(f.anchor)
                                .loadRows(71, Kind.INVOICE, id);
                assertEquals(
                        durable ? "Edited" : loaded.source().rows().getFirst().description(),
                        current.rows().getFirst().description());
                f.anchor.rollback();
            }
    }

    @Test
    void confirmedCloseFailureKeepsKnownResultAndServiceRequiresCallerTransaction()
            throws Exception {
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
            var adapter = session.rowDescriptions(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, proposal(loaded, "Edited"));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertFalse(session.isBlocked());
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierDocumentMetadataService(f.anchor);
            var source = service.loadRows(71, Kind.CREDIT, id);
            var noOp =
                    service.previewRows(
                            source,
                            source.rows().stream()
                                    .map(row -> new RowValue(row.key(), row.description()))
                                    .toList());
            f.anchor.commit();
            f.anchor.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.confirmRows(noOp));
            f.anchor.setAutoCommit(false);
            assertThrows(SQLException.class, () -> service.loadRows(72, Kind.CREDIT, id));
            assertThrows(SQLException.class, () -> service.loadRows(71, Kind.CREDIT, -1));
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(
                                            f::open,
                                            f.gate,
                                            71,
                                            12,
                                            SupplierJournalGuiFixture.CLOCK)
                                    .rowDescriptions(Kind.CREDIT, id)
                                    .load());
        }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        return SupplierRowAmountsAdapterTest.document(f, credit, 5);
    }

    static List<RowValue> proposal(
            SupplierRowDescriptionAdapter.Loaded loaded, String description) {
        return loaded.source().rows().stream()
                .map(
                        row ->
                                new RowValue(
                                        row.key(),
                                        row.key() == 0 ? description : row.description()))
                .toList();
    }

    static String table(boolean credit) {
        return SupplierRowAmountsAdapterTest.table(credit);
    }

    static void setRaw(SupplierJournalGuiFixture f, boolean credit, long id, String value)
            throws Exception {
        try (var sql =
                f.anchor.prepareStatement(
                        "UPDATE "
                                + table(credit)
                                + "_row SET description=? WHERE "
                                + table(credit)
                                + "_id=? AND row_number=0")) {
            sql.setString(1, value);
            sql.setLong(2, id);
            sql.executeUpdate();
        }
        f.anchor.commit();
    }

    static Map<Integer, Map<String, String>> rows(
            SupplierJournalGuiFixture f, boolean credit, long id) throws Exception {
        Map<Integer, Map<String, String>> rows = new LinkedHashMap<>();
        try (var sql =
                f.anchor.prepareStatement(
                        "SELECT * FROM "
                                + table(credit)
                                + "_row WHERE "
                                + table(credit)
                                + "_id=? ORDER BY row_number")) {
            sql.setLong(1, id);
            try (var r = sql.executeQuery()) {
                while (r.next()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                        values.put(r.getMetaData().getColumnName(i), r.getString(i));
                    rows.put(r.getInt("row_number"), values);
                }
            }
        }
        f.anchor.rollback();
        return rows;
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            Map<Integer, Map<String, String>> old,
            Map<Integer, String> changed)
            throws Exception {
        Map<Integer, Map<String, String>> expected = new LinkedHashMap<>();
        for (var item : old.entrySet()) {
            var row = new LinkedHashMap<>(item.getValue());
            if (changed.containsKey(item.getKey()))
                row.put("DESCRIPTION", changed.get(item.getKey()));
            expected.put(item.getKey(), row);
        }
        assertEquals(expected, rows(f, credit, id));
        var after = f.snapshot();
        String target = (table(credit) + "_row").toUpperCase(java.util.Locale.ROOT);
        for (String key : before.keySet()) {
            if (!key.equals(target)) assertEquals(before.get(key), after.get(key), key);
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
