package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAccountingService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAccountingService.Values;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSProduct;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierRowAccountingAdapterTest {
    @Test
    void bothFamiliesPreviewExplicitSelectedYearTuplesAndWriteOnlyChangedAllocationCells()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().rowAccounting(kind, id);
                var before = f.snapshot();
                var old = rows(f, credit, id);
                var loaded = adapter.load();
                assertEquals(11, loaded.source().yearId());
                assertEquals(
                        List.of(0, 7),
                        loaded.source().rows().stream().map(row -> row.key()).toList());
                assertTrue(
                        loaded.source().accounts().stream()
                                .anyMatch(
                                        a ->
                                                a.number() == 4990
                                                        && a.active()
                                                        && a.projectRequired()
                                                        && a.resultUnitRequired()));
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.source().accounts().clear());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.source().projects().clear());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.source().resultUnits().clear());
                var values = proposal(loaded);
                var preview = adapter.preview(loaded, values.reversed());
                assertEquals(before, f.snapshot());
                assertEquals(1, preview.plan().changes().size());
                assertEquals(
                        new Values(0, 4990, "P1", "R1"),
                        preview.plan().changes().getFirst().after());
                adapter.confirm(preview);
                assertNarrow(
                        f, credit, id, before, old, Map.of(0, new Values(0, 4990, "P1", "R1")));
            }
    }

    @Test
    void independentAccountProjectResultUnitAndNullableClearUpdatesStayNarrow() throws Exception {
        for (var kind : Kind.values())
            for (int mode : List.of(0, 1, 2, 3))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    if (mode == 3) {
                        try (var sql = f.anchor.createStatement()) {
                            sql.executeUpdate(
                                    "UPDATE "
                                            + table(credit)
                                            + "_row SET"
                                            + " account_number=4991,project_number='P1',result_unit_number='R1'"
                                            + " WHERE "
                                            + table(credit)
                                            + "_id="
                                            + id
                                            + " AND row_number=0");
                        }
                        f.anchor.commit();
                    }
                    List<String> updates = new ArrayList<>();
                    var wrapped =
                            SupplierJournalGuiFixture.wrap(
                                    f.anchor,
                                    (method, args) -> {
                                        if (method.getName().equals("prepareStatement")
                                                && args[0].toString().startsWith("UPDATE "))
                                            updates.add(args[0].toString());
                                        return SupplierJournalGuiFixture.invoke(
                                                f.anchor, method, args);
                                    });
                    var service = new NormalizedSupplierRowAccountingService(wrapped);
                    var source = service.load(71, 11, kind, id);
                    var next =
                            switch (mode) {
                                case 0 -> new Values(0, 4991, null, null);
                                case 1 -> new Values(0, 4010, "P1", null);
                                case 2 -> new Values(0, 4010, null, "R1");
                                default -> new Values(0, 4991, null, null);
                            };
                    var before = f.snapshot();
                    var old = rows(f, credit, id);
                    service.confirm(service.preview(source, replace(source.rows(), next)));
                    f.anchor.commit();
                    assertEquals(1, updates.size());
                    assertTrue(
                            updates.getFirst()
                                    .contains(
                                            switch (mode) {
                                                case 0 -> " SET account_number=? WHERE";
                                                case 1 -> " SET project_number=? WHERE";
                                                case 2 -> " SET result_unit_number=? WHERE";
                                                default ->
                                                        " SET project_number=?,result_unit_number=?"
                                                                + " WHERE";
                                            }));
                    assertNarrow(f, credit, id, before, old, Map.of(0, next));
                }
    }

    @Test
    void changedTuplesRequireActiveSelectedYearAccountsAndExactSelectableCompanyDimensions()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var adapter = f.session().rowAccounting(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (var invalid :
                    List.of(
                            new Values(0, null, null, null),
                            new Values(0, -1, null, null),
                            new Values(0, 9999, null, null),
                            new Values(0, 7888, null, null),
                            new Values(0, 5888, null, null),
                            new Values(0, 4992, null, null),
                            new Values(0, 4990, null, null),
                            new Values(0, 4990, "P1", null),
                            new Values(0, 4990, null, "R1"),
                            new Values(0, 4991, "OLD", null),
                            new Values(0, 4991, "F1", null),
                            new Values(0, 4991, null, "RX"),
                            new Values(0, 4991, "P1 ", null),
                            new Values(0, 4991, "p1", null),
                            new Values(0, 4991, "", null),
                            new Values(0, 4991, null, ""))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.preview(loaded, replace(loaded.source().rows(), invalid)));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void
            selectedYearAndFullCatalogueChangesInvalidatePreviewsAndSavesIncludingMetadataFlagsAndCodes()
                    throws Exception {
        for (var kind : Kind.values())
            for (String change :
                    List.of(
                            "active",
                            "required",
                            "name",
                            "vat",
                            "project",
                            "concluded",
                            "unit",
                            "year",
                            "deleteaccount",
                            "deleteproject"))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, kind == Kind.CREDIT);
                    var adapter = f.session().rowAccounting(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, proposal(loaded));
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                switch (change) {
                                    case "active" ->
                                            "UPDATE account SET active=false WHERE"
                                                    + " accounting_year_id=(SELECT id FROM"
                                                    + " accounting_year WHERE legacy_id=11) AND"
                                                    + " number=4990";
                                    case "required" ->
                                            "UPDATE account SET project_required=false WHERE"
                                                    + " accounting_year_id=(SELECT id FROM"
                                                    + " accounting_year WHERE legacy_id=11) AND"
                                                    + " number=4990";
                                    case "name" ->
                                            "UPDATE account SET description='Changed' WHERE"
                                                    + " accounting_year_id=(SELECT id FROM"
                                                    + " accounting_year WHERE legacy_id=11) AND"
                                                    + " number=4990";
                                    case "vat" ->
                                            "UPDATE account SET vat_code='Changed' WHERE"
                                                    + " accounting_year_id=(SELECT id FROM"
                                                    + " accounting_year WHERE legacy_id=11) AND"
                                                    + " number=4990";
                                    case "project" ->
                                            "UPDATE project SET name='Changed' WHERE"
                                                    + " company_id=(SELECT id FROM company WHERE"
                                                    + " legacy_id=71) AND number='P1'";
                                    case "concluded" ->
                                            "UPDATE project SET concluded=true WHERE"
                                                    + " company_id=(SELECT id FROM company WHERE"
                                                    + " legacy_id=71) AND number='P1'";
                                    case "unit" ->
                                            "UPDATE result_unit SET description='Changed' WHERE"
                                                    + " company_id=(SELECT id FROM company WHERE"
                                                    + " legacy_id=71) AND number='R1'";
                                    case "year" ->
                                            "UPDATE accounting_year SET ends_on=DATE '2026-12-30'"
                                                    + " WHERE legacy_id=11";
                                    case "deleteaccount" ->
                                            "DELETE FROM account WHERE accounting_year_id=(SELECT"
                                                + " id FROM accounting_year WHERE legacy_id=11) AND"
                                                + " number=4990";
                                    default ->
                                            "DELETE FROM project WHERE company_id=(SELECT id FROM"
                                                + " company WHERE legacy_id=71) AND number='P1'";
                                });
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(
                            SQLException.class, () -> adapter.preview(loaded, proposal(loaded)));
                    assertThrows(SQLException.class, () -> adapter.confirm(preview));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void foreignCompanyAndOtherYearCatalogueChangesDoNotLeakOrInvalidateThisSelection()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().rowAccounting(kind, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, proposal(loaded));
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE project SET concluded=true,name='Foreign' WHERE"
                                    + " company_id=(SELECT id FROM company WHERE legacy_id=72)");
                    sql.executeUpdate(
                            "UPDATE result_unit SET name='Foreign' WHERE company_id=(SELECT id FROM"
                                    + " company WHERE legacy_id=72)");
                    sql.executeUpdate(
                            "UPDATE account SET active=false WHERE accounting_year_id IN(SELECT id"
                                    + " FROM accounting_year WHERE legacy_id IN(12,13))");
                }
                f.anchor.commit();
                var before = f.snapshot();
                var old = rows(f, credit, id);
                adapter.confirm(preview);
                assertNarrow(
                        f, credit, id, before, old, Map.of(0, new Values(0, 4990, "P1", "R1")));
            }
    }

    @Test
    void historicalUnknownInactiveNullAndConcludedTuplesRemainNoOpsWithoutFinancialRepairOrUpdates()
            throws Exception {
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
                var service = new NormalizedSupplierRowAccountingService(wrapped);
                for (String allocation :
                        List.of(
                                "account_number=NULL,project_number=NULL,result_unit_number=NULL",
                                "account_number=9999,project_number='OLD',result_unit_number=NULL",
                                "account_number=4992,project_number=NULL,result_unit_number=NULL")) {
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET currency_rate=NULL,tax_sum=NULL WHERE id="
                                        + id);
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + "_row SET "
                                        + allocation
                                        + ",quantity=NULL,unit_price=NULL WHERE "
                                        + table(credit)
                                        + "_id="
                                        + id
                                        + " AND row_number=0");
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    var source = service.load(71, 11, kind, id);
                    var plan =
                            service.preview(
                                    source,
                                    source.rows().stream()
                                            .map(NormalizedSupplierRowAccountingService::values)
                                            .toList());
                    assertTrue(plan.changes().isEmpty());
                    service.confirm(plan);
                    f.anchor.commit();
                    assertEquals(before, f.snapshot());
                }
                assertEquals(0, updates.get());
            }
    }

    @Test
    void invalidRowSetsCannotInsertRemoveReorderOrRetargetPersistedKeys() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var adapter = f.session().rowAccounting(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (var invalid :
                    List.of(
                            List.<Values>of(),
                            List.of(
                                    new Values(0, 4991, null, null),
                                    new Values(0, 4991, null, null)),
                            List.of(
                                    new Values(99, 4991, null, null),
                                    new Values(7, 4991, null, null)),
                            List.of(
                                    new Values(0, 4991, null, null),
                                    new Values(7, 4991, null, null),
                                    new Values(99, 4991, null, null)))) {
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(loaded, invalid));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void lateSecondRowFailurePreservesEarlierCallerWorkAndSupportsRetryAndOuterRollback()
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
                                    + "_row ADD CONSTRAINT reject_allocation CHECK(row_number<>7 OR"
                                    + " account_number<>4991)");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierRowAccountingService(f.anchor);
                var source = service.load(71, 11, kind, id);
                var plan =
                        service.preview(
                                source,
                                List.of(
                                        new Values(0, 4990, "P1", "R1"),
                                        new Values(7, 4991, null, null)));
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirm(plan));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE " + t + "_row DROP CONSTRAINT reject_allocation");
                }
                f.anchor.commit();
                before = f.snapshot();
                service.confirm(plan);
                f.anchor.rollback();
                assertEquals(before, f.snapshot());
                service.confirm(plan);
                f.anchor.commit();
                var current = service.load(71, 11, kind, id);
                assertEquals(4990, current.rows().getFirst().account());
                assertEquals("P1", current.rows().getFirst().project());
                assertEquals(4991, current.rows().get(1).account());
            }
    }

    @Test
    void noOpConfirmationStillRechecksCompleteSourceAndTheFullCatalogue() throws Exception {
        for (var kind : Kind.values())
            for (boolean catalogue : List.of(false, true))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, kind == Kind.CREDIT);
                    var adapter = f.session().rowAccounting(kind, id);
                    var loaded = adapter.load();
                    var plan =
                            adapter.preview(
                                    loaded,
                                    loaded.source().rows().stream()
                                            .map(NormalizedSupplierRowAccountingService::values)
                                            .toList());
                    assertTrue(plan.plan().changes().isEmpty());
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                catalogue
                                        ? "UPDATE account SET description='Changed' WHERE"
                                                + " accounting_year_id=(SELECT id FROM"
                                                + " accounting_year WHERE legacy_id=11) AND"
                                                + " number=4990"
                                        : "UPDATE "
                                                + table(kind == Kind.CREDIT)
                                                + " SET reference_number='Changed' WHERE id="
                                                + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, () -> adapter.confirm(plan));
                    assertEquals(before, f.snapshot());
                }
    }

    @Test
    void
            allocationsPreserveStockAndAmountsInvalidateOldJournalAndPostExpectedScopedAccountsAndDimensions()
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
                var adapter = f.session().rowAccounting(kind, id);
                var loaded = adapter.load();
                adapter.confirm(adapter.preview(loaded, proposal(loaded)));
                assertThrows(IllegalArgumentException.class, () -> journal.post(original));
                var fresh =
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                assertEquals(0, original.summary().debit().compareTo(fresh.summary().debit()));
                assertEquals(0, fresh.summary().debit().compareTo(fresh.summary().credit()));
                assertTrue(
                        fresh.summary().rows().stream()
                                .anyMatch(
                                        row ->
                                                Integer.valueOf(4990).equals(row.account())
                                                        && "P1".equals(row.project())
                                                        && "R1".equals(row.resultUnit())));
                journal.post(fresh);
                assertEquals(1, f.voucherCount());
                assertEquals(
                        stock,
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
                var current = rows(f, credit, id).get(0);
                assertEquals("4990", current.get("ACCOUNT_NUMBER"));
                assertEquals("P1", current.get("PROJECT_NUMBER"));
                assertEquals("R1", current.get("RESULT_UNIT_NUMBER"));
            }
    }

    @Test
    void initialAndLateReferencesAndProtectedStatesAreRefusedWithoutDetachment() throws Exception {
        for (String ref : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false);
                    var adapter = f.session().rowAccounting(Kind.INVOICE, id);
                    var loaded = adapter.load();
                    var plan = adapter.preview(loaded, proposal(loaded));
                    SupplierDocumentDeletionAdapterTest.reference(f, ref, company, 5);
                    var before = f.snapshot();
                    if (company == 71) {
                        assertThrows(SQLException.class, adapter::load);
                        assertThrows(
                                SQLException.class,
                                () -> adapter.preview(loaded, proposal(loaded)));
                        assertThrows(SQLException.class, () -> adapter.confirm(plan));
                        assertEquals(before, f.snapshot());
                    } else {
                        adapter.confirm(plan);
                        assertEquals(
                                4990,
                                new NormalizedSupplierRowAccountingService(f.anchor)
                                        .load(71, 11, Kind.INVOICE, id)
                                        .rows()
                                        .getFirst()
                                        .account());
                        f.anchor.rollback();
                    }
                }
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().rowAccounting(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, proposal(loaded));
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
                    var adapter = f.session().rowAccounting(kind, id);
                    var loaded = adapter.load();
                    var proposed = proposal(loaded);
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
            var adapter = session.rowAccounting(Kind.INVOICE, id);
            var loaded = adapter.load();
            assertThrows(UnsupportedOperationException.class, () -> loaded.source().rows().clear());
            var values = new ArrayList<>(proposal(loaded));
            var preview = adapter.preview(loaded, values);
            values.clear();
            assertThrows(
                    UnsupportedOperationException.class, () -> preview.plan().changes().clear());
            var foreign = session.rowAccounting(Kind.INVOICE, id);
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
                var adapter = session.rowAccounting(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, proposal(loaded));
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
                        new NormalizedSupplierRowAccountingService(f.anchor)
                                .load(71, 11, Kind.INVOICE, id);
                assertEquals(
                        durable ? 4990 : loaded.source().rows().getFirst().account(),
                        current.rows().getFirst().account());
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
            var adapter = session.rowAccounting(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, proposal(loaded));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertFalse(session.isBlocked());
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierRowAccountingService(f.anchor);
            var source = service.load(71, 11, Kind.CREDIT, id);
            var noOp =
                    service.preview(
                            source,
                            source.rows().stream()
                                    .map(NormalizedSupplierRowAccountingService::values)
                                    .toList());
            f.anchor.commit();
            f.anchor.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.confirm(noOp));
            f.anchor.setAutoCommit(false);
            assertThrows(SQLException.class, () -> service.load(72, 11, Kind.CREDIT, id));
            assertThrows(SQLException.class, () -> service.load(71, 11, Kind.CREDIT, -1));
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(
                                            f::open,
                                            f.gate,
                                            71,
                                            12,
                                            SupplierJournalGuiFixture.CLOCK)
                                    .rowAccounting(Kind.CREDIT, id)
                                    .load());
        }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        long id = SupplierRowDescriptionAdapterTest.document(f, credit);
        try (var sql = f.anchor.createStatement()) {
            sql.executeUpdate(
                    "UPDATE account SET"
                        + " active=true,project_required=false,result_unit_required=false WHERE"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE legacy_id=11)"
                        + " AND number=4010");
            sql.executeUpdate(
                    "INSERT INTO accounting_year(legacy_id,company_id,starts_on,ends_on) SELECT"
                            + " 13,id,DATE '2027-01-01',DATE '2027-12-31' FROM company WHERE"
                            + " legacy_id=71");
            sql.executeUpdate(
                    "INSERT INTO account_plan(accounting_year_id,name) SELECT id,'Other plan' FROM"
                            + " accounting_year WHERE legacy_id=13");
        }
        account(f, 11, 4990, true, true, true);
        account(f, 11, 4991, true, false, false);
        account(f, 11, 4992, false, false, false);
        account(f, 12, 7888, true, false, false);
        account(f, 13, 5888, true, false, false);
        dimension(f, 71, "P1", true, false);
        dimension(f, 71, "P2", true, false);
        dimension(f, 71, "OLD", true, true);
        dimension(f, 71, "R1", false, false);
        dimension(f, 71, "R2", false, false);
        dimension(f, 72, "P1", true, false);
        dimension(f, 72, "F1", true, false);
        dimension(f, 72, "R1", false, false);
        dimension(f, 72, "RX", false, false);
        f.anchor.commit();
        return id;
    }

    static void account(
            SupplierJournalGuiFixture f,
            int year,
            int number,
            boolean active,
            boolean project,
            boolean unit)
            throws Exception {
        try (var sql =
                f.anchor.prepareStatement(
                        "INSERT INTO"
                            + " account(accounting_year_id,number,description,active,project_required,result_unit_required)"
                            + " SELECT id,?,?,?,?,? FROM accounting_year WHERE legacy_id=?")) {
            sql.setInt(1, number);
            sql.setString(2, "Account " + number);
            sql.setBoolean(3, active);
            sql.setBoolean(4, project);
            sql.setBoolean(5, unit);
            sql.setInt(6, year);
            sql.executeUpdate();
        }
    }

    static void dimension(
            SupplierJournalGuiFixture f,
            int company,
            String code,
            boolean project,
            boolean concluded)
            throws Exception {
        String table = project ? "project" : "result_unit";
        try (var sql =
                f.anchor.prepareStatement(
                        "INSERT INTO "
                                + table
                                + "(company_id,number,name"
                                + (project ? ",concluded" : "")
                                + ") SELECT id,?,?"
                                + (project ? ",?" : "")
                                + " FROM company WHERE legacy_id=?")) {
            sql.setString(1, code);
            sql.setString(2, "Name " + code);
            int parameter = 3;
            if (project) sql.setBoolean(parameter++, concluded);
            sql.setInt(parameter, company);
            sql.executeUpdate();
        }
    }

    static List<Values> proposal(SupplierRowAccountingAdapter.Loaded loaded) {
        return replace(loaded.source().rows(), new Values(0, 4990, "P1", "R1"));
    }

    static List<Values> replace(
            List<
                            org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierRowAmountsService.Row>
                    rows,
            Values next) {
        return rows.stream()
                .map(
                        row ->
                                row.key() == next.key()
                                        ? next
                                        : NormalizedSupplierRowAccountingService.values(row))
                .toList();
    }

    static String table(boolean credit) {
        return SupplierRowDescriptionAdapterTest.table(credit);
    }

    static Map<Integer, Map<String, String>> rows(
            SupplierJournalGuiFixture f, boolean credit, long id) throws Exception {
        return SupplierRowDescriptionAdapterTest.rows(f, credit, id);
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            Map<Integer, Map<String, String>> old,
            Map<Integer, Values> changed)
            throws Exception {
        Map<Integer, Map<String, String>> expected = new LinkedHashMap<>();
        for (var entry : old.entrySet()) {
            var row = new LinkedHashMap<>(entry.getValue());
            var next = changed.get(entry.getKey());
            if (next != null) {
                row.put(
                        "ACCOUNT_NUMBER",
                        next.account() == null ? null : next.account().toString());
                row.put("PROJECT_NUMBER", next.project());
                row.put("RESULT_UNIT_NUMBER", next.resultUnit());
            }
            expected.put(entry.getKey(), row);
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
