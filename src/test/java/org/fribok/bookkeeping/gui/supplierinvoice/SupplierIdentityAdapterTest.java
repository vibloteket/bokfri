package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierIdentityService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierIdentityService.Values;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierIdentityAdapterTest {
    static final Values PROPOSED = new Values("S60");

    @Test
    void bothFamiliesChangeOnlySupplierNumberWithoutCopyingRegisterDefaultsOrStoredName()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().supplierIdentity(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                assertEquals(new Values("S30"), loaded.source().values());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL, loaded.source().dates().invoiceDate());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.source().suppliers().clear());
                var preview = adapter.preview(loaded, PROPOSED);
                assertEquals(before, f.snapshot());
                assertTrue(preview.plan().changed());
                assertEquals(new Values("S30"), preview.plan().before());
                assertEquals(PROPOSED, preview.plan().after());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, header, PROPOSED);
                assertEquals("S60", f.stored(credit, id).getSupplierNr());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL_DUE,
                        f.stored(credit, id).getLocalDueDate());
            }
    }

    @Test
    void onlyChangedSupplierNumberColumnIsWrittenAndNoOpDoesNotIssueAnyUpdate() throws Exception {
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
                var service = new NormalizedSupplierIdentityService(wrapped);
                var loaded = service.load(71, kind, id);
                service.confirm(service.preview(loaded, loaded.values()));
                assertTrue(sql.isEmpty());
                service.confirm(service.preview(loaded, PROPOSED));
                assertEquals(1, sql.size());
                assertTrue(sql.getFirst().contains(" SET supplier_number=? WHERE"));
                f.anchor.rollback();
            }
    }

    @Test
    void exactUniqueCompanyNumbersRequiredAndClearAliasesForeignAndDuplicatesRefused()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().supplierIdentity(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                for (String bad :
                        java.util.Arrays.asList(
                                null,
                                "",
                                " ",
                                "missing",
                                "S60 ",
                                "s60",
                                "S60\n",
                                "x".repeat(256),
                                "FOREIGN")) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> adapter.preview(loaded, new Values(bad)));
                    assertEquals(before, f.snapshot());
                }
                adapter.confirm(adapter.preview(loaded, new Values("SNET60")));
                assertEquals("SNET60", adapter.load().source().values().supplierNumber());
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                                    + " 705,id,'S60','Duplicate' FROM company WHERE legacy_id=71");
                }
                f.anchor.commit();
                var current = adapter.load();
                var old = f.snapshot();
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(current, PROPOSED));
                assertEquals(old, f.snapshot());
            }
    }

    @Test
    void
            identityCatalogueRenameNameInsertionDeletionAndDuplicateInvalidatePreviewAndConfirmEvenNoOps()
                    throws Exception {
        for (String change : List.of("name", "delete", "insert", "rename", "duplicate"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, false);
                var adapter = f.session().supplierIdentity(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, PROPOSED);
                var noop = adapter.preview(loaded, loaded.source().values());
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            switch (change) {
                                case "name" ->
                                        "UPDATE supplier SET name='Changed' WHERE legacy_id=702";
                                case "delete" -> "DELETE FROM supplier WHERE legacy_id=702";
                                case "rename" ->
                                        "UPDATE supplier SET number='S61' WHERE legacy_id=702";
                                default ->
                                        "INSERT INTO supplier(legacy_id,company_id,number,name)"
                                                + " SELECT 706,id,'"
                                                + (change.equals("duplicate") ? "S60" : "NEW")
                                                + "',NULL FROM company WHERE legacy_id=71";
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
    void historicalNullBlankControlAndOrphanNumbersAndInvalidDatesRemainRawUntilExplicitlyChanged()
            throws Exception {
        for (var kind : Kind.values())
            for (String term : java.util.Arrays.asList(null, "", " ", "old\nterm", "ORPHAN"))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, kind == Kind.CREDIT);
                    try (var s =
                            f.anchor.prepareStatement(
                                    "UPDATE "
                                            + table(kind == Kind.CREDIT)
                                            + " SET supplier_number=?,invoice_date=NULL,due_date=NULL,currency_rate=-1"
                                            + " WHERE id=?")) {
                        s.setString(1, term);
                        s.setLong(2, id);
                        s.executeUpdate();
                    }
                    f.anchor.commit();
                    var adapter = f.session().supplierIdentity(kind, id);
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
                    var adapter = f.session().supplierIdentity(kind, id);
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
                    var adapter = f.session().supplierIdentity(Kind.INVOICE, id);
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
                    var adapter = f.session().supplierIdentity(kind, id);
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
                                    + " ADD CONSTRAINT reject_date CHECK(supplier_number<>'S60')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierIdentityService(f.anchor);
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
            var adapter = session.supplierIdentity(Kind.INVOICE, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var foreign = session.supplierIdentity(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, PROPOSED));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierIdentityService(f.anchor);
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
                                    .supplierIdentity(Kind.INVOICE, id)
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
                var adapter = session.supplierIdentity(Kind.INVOICE, id);
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
                        durable ? "S60" : "S30",
                        new NormalizedSupplierIdentityService(f.anchor)
                                .load(71, Kind.INVOICE, id)
                                .values()
                                .supplierNumber());
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
            var adapter = session.supplierIdentity(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            assertFalse(session.isBlocked());
            assertEquals("S60", f.stored(true, id).getSupplierNr());
        }
    }

    @Test
    void
            changingOnlySupplierNumberKeepsStockAndBalancedAccountingAndPostingPriorPreviewUsesFreshIdentity()
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
                var adapter = f.session().supplierIdentity(kind, id);
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
                assertEquals("S60", f.stored(credit, id).getSupplierNr());
                assertEquals(
                        SupplierDatesAdapterTest.ORIGINAL_DUE,
                        f.stored(credit, id).getLocalDueDate());
            }
    }

    @Test
    void supplierDefaultsAndOtherCompanyCataloguesAreNeverCopiedOrConsulted() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().supplierIdentity(kind, id);
                var loaded = adapter.load();
                var plan = adapter.preview(loaded, PROPOSED);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE supplier SET bankgiro='Changed"
                                    + " bank',our_contact_person='Changed default' WHERE"
                                    + " legacy_id=702");
                    sql.executeUpdate(
                            "UPDATE supplier SET number='S60',name='Foreign duplicate' WHERE"
                                    + " legacy_id=704");
                }
                f.anchor.commit();
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, kind == Kind.CREDIT, id);
                adapter.confirm(plan);
                assertNarrow(f, kind == Kind.CREDIT, id, before, header, PROPOSED);
                assertEquals("30", f.stored(kind == Kind.CREDIT, id).getPaymentTerm().getName());
            }
    }

    @Test
    void ambiguousHistoricalNumberIsPreservedAsNoOpButNewDuplicateTargetIsRejected()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                                + " 705,id,'S30','Duplicate old' FROM company WHERE legacy_id=71");
            }
            f.anchor.commit();
            var adapter = f.session().supplierIdentity(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            adapter.confirm(adapter.preview(loaded, loaded.source().values()));
            assertEquals(before, f.snapshot());
            adapter.confirm(adapter.preview(adapter.load(), PROPOSED));
            assertEquals("S60", f.stored(false, id).getSupplierNr());
        }
    }

    @Test
    void concurrentSupplierNumberChangeInvalidatesChangedAndNoOpPlansWithoutOverwritingIt()
            throws Exception {
        for (var kind : Kind.values()) {
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().supplierIdentity(kind, id);
                var loaded = adapter.load();
                var plan = adapter.preview(loaded, PROPOSED);
                var noop = adapter.preview(loaded, loaded.source().values());
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + table(kind == Kind.CREDIT)
                                    + " SET supplier_number='SNET60' WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> adapter.preview(loaded, PROPOSED));
                assertThrows(SQLException.class, () -> adapter.confirm(plan));
                assertThrows(SQLException.class, () -> adapter.confirm(noop));
                assertEquals(before, f.snapshot());
            }
        }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        long id = SupplierDatesAdapterTest.document(f, credit);
        try (var sql = f.anchor.createStatement()) {
            sql.executeUpdate(
                    "INSERT INTO"
                        + " supplier(legacy_id,company_id,number,name,bankgiro,payment_term_name,our_contact_person)"
                        + " SELECT 701,id,'S30','Original register name','111','30','Original"
                        + " contact' FROM company WHERE legacy_id=71");
            sql.executeUpdate(
                    "INSERT INTO"
                        + " supplier(legacy_id,company_id,number,name,bankgiro,payment_term_name,our_contact_person)"
                        + " SELECT 702,id,'S60','Different supplier name','999','30','Never copy'"
                        + " FROM company WHERE legacy_id=71");
            sql.executeUpdate(
                    "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                            + " 703,id,'SNET60','Named supplier' FROM company WHERE legacy_id=71");
            sql.executeUpdate(
                    "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                            + " 704,id,'FOREIGN','Other company' FROM company WHERE legacy_id=72");
            sql.executeUpdate(
                    "UPDATE " + table(credit) + " SET supplier_number='S30' WHERE id=" + id);
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
        expected.put("SUPPLIER_NUMBER", values.supplierNumber());
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
