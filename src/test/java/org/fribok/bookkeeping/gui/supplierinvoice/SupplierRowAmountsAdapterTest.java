package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierJournalService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Values;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierRowAmountsAdapterTest {
    @Test
    void bothFamiliesPreviewWithoutWritesAndConfirmOnlyChangedRowColumnsPreservingAllOtherSql()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit, 5);
                var adapter = f.session().rowAmounts(kind, id);
                var before = f.snapshot();
                var loaded = adapter.load();
                assertEquals(before, f.snapshot());
                assertEquals(
                        List.of(0, 7),
                        loaded.source().rows().stream().map(row -> row.key()).toList());
                assertEquals("P", loaded.source().rows().getFirst().product());
                assertEquals("historical unit", loaded.source().rows().getFirst().unit());
                var proposed = proposal(loaded, 3, "20", "4");
                var preview = adapter.preview(loaded, proposed.reversed());
                assertEquals(before, f.snapshot());
                decimal("150", preview.plan().before().net());
                decimal("110", preview.plan().after().payable());
                decimal("137.50", preview.plan().after().accountingEquivalent());
                assertNull(preview.plan().after().tax());
                assertNull(preview.plan().after().rounding());
                assertEquals(1, preview.plan().changes().size());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, 3, "20", "4");
            }
    }

    @Test
    void freightChangesValuationInputButNotLegacyPayableAndVatRoundingRateRemainRaw()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit, 5);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + table(credit)
                                    + " SET tax_sum=20,rounding_sum=0.05,currency_rate=2 WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var adapter = f.session().rowAmounts(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                var preview = adapter.preview(loaded, proposal(loaded, 1, "100", "7"));
                decimal("170.05", preview.plan().before().payable());
                decimal("170.05", preview.plan().after().payable());
                decimal("340.10", preview.plan().after().accountingEquivalent());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, 1, "100", "7");
                decimal("170.05", SSSupplierInvoiceMath.getTotalSum(f.stored(credit, id)));
            }
    }

    @Test
    void invalidAmountsQuantitiesPrecisionAndRowSetsNeverWriteOrAllocateKeys() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false, 5);
            var adapter = f.session().rowAmounts(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (Values bad :
                    List.of(
                            new Values(0, null, BigDecimal.ONE, null),
                            new Values(0, 0, BigDecimal.ONE, null),
                            new Values(0, -1, BigDecimal.ONE, null),
                            new Values(0, 1, null, null),
                            new Values(0, 1, BigDecimal.ONE.negate(), null),
                            new Values(0, 1, BigDecimal.ONE, BigDecimal.ONE.negate()),
                            new Values(0, 1, new BigDecimal("1e-31"), null),
                            new Values(0, 1, new BigDecimal("1e70"), null),
                            new Values(0, Integer.MAX_VALUE, new BigDecimal("1e69"), null))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                adapter.preview(
                                        loaded,
                                        List.of(bad, loaded.source().rows().get(1).values())));
                assertEquals(before, f.snapshot());
            }
            var original = loaded.source().rows().stream().map(row -> row.values()).toList();
            for (var bad :
                    List.of(
                            List.<Values>of(),
                            List.of(original.getFirst(), original.getFirst()),
                            List.of(new Values(99, 1, BigDecimal.ONE, null), original.get(1)),
                            List.of(
                                    original.getFirst(),
                                    original.get(1),
                                    new Values(99, 1, BigDecimal.ONE, null)))) {
                assertThrows(IllegalArgumentException.class, () -> adapter.preview(loaded, bad));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void initialAndLateReferencesAndProtectedStatesAreRefusedWithoutDetachment() throws Exception {
        for (String ref : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false, 5);
                    var adapter = f.session().rowAmounts(Kind.INVOICE, id);
                    var loaded = adapter.load();
                    var plan = adapter.preview(loaded, proposal(loaded, 3, "20", "4"));
                    SupplierDocumentDeletionAdapterTest.reference(f, ref, company, 5);
                    var before = f.snapshot();
                    if (company == 71) {
                        assertThrows(SQLException.class, adapter::load);
                        assertThrows(
                                SQLException.class,
                                () -> adapter.preview(loaded, proposal(loaded, 3, "20", "4")));
                        assertThrows(SQLException.class, () -> adapter.confirm(plan));
                        assertEquals(before, f.snapshot());
                    } else {
                        adapter.confirm(plan);
                        assertNarrow(f, false, id, before, 3, "20", "4");
                    }
                }
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit, 5);
                    var adapter = f.session().rowAmounts(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, proposal(loaded, 3, "20", "4"));
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
                    long id = document(f, credit, 5);
                    var adapter = f.session().rowAmounts(kind, id);
                    var loaded = adapter.load();
                    var proposed = proposal(loaded, 3, "20", "4");
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
    void lateSecondRowFailureRollsBackFirstRowAndRetainsCallerWorkThenAllowsRetry()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit, 5);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row ADD CONSTRAINT reject_late_amount CHECK(row_number<>7"
                                    + " OR unit_price<=25)");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierRowAmountsService(f.anchor);
                var source = service.load(71, kind, id);
                var preview =
                        service.preview(
                                source,
                                List.of(
                                        new Values(0, 3, new BigDecimal("20"), BigDecimal.ONE),
                                        new Values(
                                                7, 2, new BigDecimal("26"), new BigDecimal("5"))));
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirm(preview));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row DROP CONSTRAINT reject_late_amount");
                }
                f.anchor.commit();
                var original = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                service.confirm(preview);
                f.anchor.rollback();
                assertEquals(original, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                service.confirm(preview);
                f.anchor.commit();
                var current = service.load(71, kind, id);
                assertEquals(3, current.rows().getFirst().quantity());
                decimal("26", current.rows().get(1).unitPrice());
            }
    }

    @Test
    void noOpPreservesHistoricalNullAndNegativeValuesAndScaleOnlyChangesDoNotRewrite()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false, 5);
            var adapter = f.session().rowAmounts(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            var values =
                    List.of(
                            new Values(0, 1, new BigDecimal("100.0"), null),
                            new Values(7, 2, new BigDecimal("25.00"), new BigDecimal("5.0")));
            var preview = adapter.preview(loaded, values);
            assertTrue(preview.plan().changes().isEmpty());
            adapter.confirm(preview);
            assertEquals(before, f.snapshot());
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "UPDATE supplier_invoice_row SET"
                            + " quantity=NULL,unit_price=NULL,unit_freight=-1 WHERE"
                            + " supplier_invoice_id="
                                + id
                                + " AND row_number=0");
            }
            f.anchor.commit();
            loaded = adapter.load();
            before = f.snapshot();
            preview =
                    adapter.preview(
                            loaded,
                            loaded.source().rows().stream().map(row -> row.values()).toList());
            assertTrue(preview.plan().changes().isEmpty());
            adapter.confirm(preview);
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void immutableTokensForeignContextsAndConfirmedReplayNeverCauseAnotherWrite() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false, 5);
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
            var adapter = session.rowAmounts(Kind.INVOICE, id);
            var loaded = adapter.load();
            assertThrows(UnsupportedOperationException.class, () -> loaded.source().rows().clear());
            var values = new ArrayList<>(proposal(loaded, 3, "20", "4"));
            var preview = adapter.preview(loaded, values);
            values.clear();
            assertThrows(
                    UnsupportedOperationException.class, () -> preview.plan().changes().clear());
            var foreign = session.rowAmounts(Kind.INVOICE, id);
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
                long id = document(f, false, 5);
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
                var adapter = session.rowAmounts(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, proposal(loaded, 3, "20", "4"));
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
                        new NormalizedSupplierRowAmountsService(f.anchor)
                                .load(71, Kind.INVOICE, id);
                assertEquals(durable ? 3 : 1, current.rows().getFirst().quantity());
                f.anchor.rollback();
            }
    }

    @Test
    void confirmedCloseFailureKeepsKnownResultAndServiceRequiresCallerTransaction()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, true, 5);
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
            var adapter = session.rowAmounts(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, proposal(loaded, 3, "20", "4"));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertFalse(session.isBlocked());
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierRowAmountsService(f.anchor);
            var source = service.load(71, Kind.CREDIT, id);
            var noOp =
                    service.preview(
                            source, source.rows().stream().map(row -> row.values()).toList());
            f.anchor.commit();
            f.anchor.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.confirm(noOp));
            f.anchor.setAutoCommit(false);
            assertThrows(SQLException.class, () -> service.load(72, Kind.CREDIT, id));
            assertThrows(SQLException.class, () -> service.load(71, Kind.CREDIT, -1));
            assertThrows(
                    SQLException.class,
                    () ->
                            new SupplierDocumentGuiSession(
                                            f::open,
                                            f.gate,
                                            71,
                                            12,
                                            SupplierJournalGuiFixture.CLOCK)
                                    .rowAmounts(Kind.CREDIT, id)
                                    .load());
        }
    }

    @Test
    void positiveStoredRateIsRequiredOnlyForRealChangesAndNeverInvented() throws Exception {
        for (String rate : List.of("NULL", "0", "-1"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, false, 5);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE supplier_invoice SET currency_rate="
                                    + rate
                                    + " WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var adapter = f.session().rowAmounts(Kind.INVOICE, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.preview(loaded, proposal(loaded, 3, "20", "4")));
                assertEquals(before, f.snapshot());
                adapter.confirm(
                        adapter.preview(
                                loaded,
                                loaded.source().rows().stream().map(row -> row.values()).toList()));
                assertEquals(before, f.snapshot());
            }
    }

    @Test
    void productStockDirectionAndExplicitInvoiceCreditJournalRemainCorrectAfterAmountChanges()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit, 5);
                SSProduct product = SSProduct.forPersistence();
                product.setNumber("P");
                product.setStockProduct(true);
                assertEquals(
                        credit ? -1 : 1,
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
                var adapter = f.session().rowAmounts(kind, id);
                var loaded = adapter.load();
                adapter.confirm(adapter.preview(loaded, proposal(loaded, 3, "20", "7")));
                assertEquals(
                        credit ? -3 : 3,
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
                var accounting =
                        new NormalizedAccountingStore(f.anchor, SupplierJournalGuiFixture.CLOCK);
                var company =
                        accounting.getCompanies().stream()
                                .filter(c -> c.getId() == 71)
                                .findFirst()
                                .orElseThrow();
                accounting.setCurrentCompany(company);
                var year =
                        accounting.getYears().stream()
                                .filter(y -> y.getId() == 11)
                                .findFirst()
                                .orElseThrow();
                SSAccountPlan accounts = new SSAccountPlan();
                accounts.setAccounts(accounting.getAccounts(year));
                var stored = f.stored(credit, id);
                var generated = stored.generateVoucher(accounts, List.of(), List.of());
                var expected = SSVoucherMath.getDebetSum(generated, accounts.getAccounts());
                assertEquals(
                        0,
                        expected.compareTo(
                                SSVoucherMath.getCreditSum(generated, accounts.getAccounts())));
                var service =
                        new NormalizedSupplierJournalService(
                                f.anchor, SupplierJournalGuiFixture.CLOCK);
                var journal =
                        credit
                                ? service.planCredits(
                                        71,
                                        11,
                                        SupplierJournalGuiFixture.FROM,
                                        SupplierJournalGuiFixture.TO)
                                : service.planInvoices(
                                        71,
                                        11,
                                        SupplierJournalGuiFixture.FROM,
                                        SupplierJournalGuiFixture.TO);
                service.post(journal);
                f.anchor.commit();
                var ledger = accounting.getVouchers(year).getFirst();
                assertEquals(
                        0,
                        expected.compareTo(
                                SSVoucherMath.getDebetSum(ledger, accounts.getAccounts())));
                assertEquals(
                        0,
                        expected.compareTo(
                                SSVoucherMath.getCreditSum(ledger, accounts.getAccounts())));
                assertEquals(
                        credit ? -3 : 3,
                        new NormalizedStockStore(f.anchor)
                                .read(71, List.of(product))
                                .getQuantity(product));
                f.anchor.rollback();
            }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit, int number) throws Exception {
        var document = SupplierJournalGuiFixture.document(credit, number, "100");
        document.setStockInfluencing(true);
        document.setTaxSum(null);
        document.setRoundingSum(null);
        document.setCurrencyRate(new BigDecimal("1.25"));
        var second = new SSSupplierInvoiceRow();
        second.setDescription(null);
        second.setQuantity(2);
        second.setUnitprice(new BigDecimal("25"));
        second.setUnitFreight(new BigDecimal("5"));
        second.setAccountNr(4010);
        document.getRows().add(second);
        var voucher = new SSVoucher(44, SupplierJournalGuiFixture.FROM);
        voucher.setDescription("Historical snapshot");
        var debit = new SSVoucherRow();
        debit.setAccountNr(4010);
        debit.setDebet(new BigDecimal("2"));
        debit.setLocalEditedDate(LocalDateTime.parse("2026-10-01T12:13:14.123456789"));
        debit.setEditedSignature("audit");
        debit.setAdded(true);
        var control = new SSVoucherRow();
        control.setAccountNr(2440);
        control.setCredit(new BigDecimal("2"));
        voucher.getRows().add(debit);
        voucher.getRows().add(control);
        document.setVoucher(voucher);
        document.setCorrection(new SSVoucher(voucher));
        var store = new NormalizedSupplierInvoiceStore(f.anchor);
        long id =
                credit
                        ? store.addSupplierCreditInvoice(71, (SSSupplierCreditInvoice) document)
                        : store.addSupplierInvoice(71, document);
        try (var s = f.anchor.createStatement()) {
            s.executeUpdate("INSERT INTO unit_definition(name) VALUES('historical unit')");
            s.executeUpdate(
                    "UPDATE "
                            + table(credit)
                            + "_row SET row_number=7 WHERE "
                            + table(credit)
                            + "_id="
                            + id
                            + " AND row_number=1");
            s.executeUpdate(
                    "UPDATE "
                            + table(credit)
                            + "_row SET product_number='P',unit_name='historical unit' WHERE "
                            + table(credit)
                            + "_id="
                            + id
                            + " AND row_number=0");
        }
        f.anchor.commit();
        return id;
    }

    static List<Values> proposal(
            SupplierRowAmountsAdapter.Loaded loaded, int quantity, String price, String freight) {
        return List.of(
                new Values(
                        0,
                        quantity,
                        new BigDecimal(price),
                        freight == null ? null : new BigDecimal(freight)),
                loaded.source().rows().get(1).values());
    }

    static String table(boolean credit) {
        return credit ? "supplier_credit_invoice" : "supplier_invoice";
    }

    static void decimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            int quantity,
            String price,
            String freight)
            throws Exception {
        var after = f.snapshot();
        String rows = (table(credit) + "_row").toUpperCase(java.util.Locale.ROOT);
        for (String name : before.keySet()) {
            if (!name.equals(rows)) {
                assertEquals(before.get(name), after.get(name), name);
            }
        }
        // Verify every non-amount column and untouched row by matching the original SQL row string.
        try (var s =
                f.anchor.prepareStatement(
                        "SELECT * FROM "
                                + table(credit)
                                + "_row WHERE "
                                + table(credit)
                                + "_id=? ORDER BY row_number")) {
            s.setLong(1, id);
            try (var result = s.executeQuery()) {
                while (result.next()) {
                    int key = result.getInt("row_number");
                    List<String> current = new ArrayList<>();
                    String prefix = "[" + id + ", ";
                    String original =
                            before.get(rows).stream()
                                    .filter(
                                            value ->
                                                    value.startsWith(prefix)
                                                            && value.substring(
                                                                            1, value.length() - 1)
                                                                    .split(", ", -1)[2]
                                                                    .equals(Integer.toString(key)))
                                    .findFirst()
                                    .orElseThrow();
                    if (key == 0) {
                        assertEquals(quantity, result.getInt("quantity"));
                        decimal(price, result.getBigDecimal("unit_price"));
                        if (freight == null) assertNull(result.getBigDecimal("unit_freight"));
                        else decimal(freight, result.getBigDecimal("unit_freight"));
                    }
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        String column = result.getMetaData().getColumnName(i);
                        String value = result.getString(i);
                        // Replace changed cells with their original raw fields before comparison.
                        if (key == 0
                                && List.of("QUANTITY", "UNIT_PRICE", "UNIT_FREIGHT")
                                        .contains(column)) {
                            String[] old =
                                    original.substring(1, original.length() - 1).split(", ", -1);
                            value = old[i - 1].equals("null") ? null : old[i - 1];
                        }
                        current.add(value);
                    }
                    assertEquals(original, current.toString());
                }
            }
        }
        f.anchor.rollback();
    }
}
