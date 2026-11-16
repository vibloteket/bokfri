package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService.Values;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSProduct;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierTaxRoundingAdapterTest {
    static final Values PROPOSED = new Values(new BigDecimal("25"), new BigDecimal("0.05"));

    @Test
    void bothFamiliesPreviewExactTotalsWithoutWritesAndOnlyTwoChangedHeaderColumnsAreUpdated()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().taxRounding(kind, id);
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                var loaded = adapter.load();
                assertNull(loaded.source().values().tax());
                assertNull(loaded.source().values().rounding());
                assertEquals(before, f.snapshot());
                var preview = adapter.preview(loaded, PROPOSED);
                assertTrue(preview.plan().changed());
                decimal("150", preview.plan().beforeTotals().payable());
                decimal("150", preview.plan().afterTotals().net());
                decimal("175.05", preview.plan().afterTotals().payable());
                decimal("218.8125", preview.plan().afterTotals().accountingEquivalent());
                assertEquals(before, f.snapshot());
                adapter.confirm(preview);
                assertNarrow(f, credit, id, before, header, PROPOSED);
                decimal("175.05", SSSupplierInvoiceMath.getTotalSum(f.stored(credit, id)));
            }
    }

    @Test
    void taxAndSignedRoundingAreIndependentNullableColumnsAndNoOtherAmountIsRecalculated()
            throws Exception {
        for (var kind : Kind.values())
            for (boolean tax : List.of(false, true))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
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
                    var service = new NormalizedSupplierTaxRoundingService(wrapped);
                    var loaded = service.load(71, kind, id);
                    var proposed =
                            tax
                                    ? new Values(BigDecimal.ZERO, null)
                                    : new Values(null, new BigDecimal("-0.05"));
                    var before = f.snapshot();
                    var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                    service.confirm(service.preview(loaded, proposed));
                    f.anchor.commit();
                    assertEquals(1, updates.size());
                    assertTrue(
                            updates.getFirst()
                                    .contains(
                                            tax
                                                    ? " SET tax_sum=? WHERE"
                                                    : " SET rounding_sum=? WHERE"));
                    assertNarrow(f, credit, id, before, header, proposed);
                    var current = service.load(71, kind, id);
                    var cleared = new Values(null, null);
                    service.confirm(service.preview(current, cleared));
                    f.anchor.commit();
                    assertNull(service.load(71, kind, id).values().tax());
                    assertNull(service.load(71, kind, id).values().rounding());
                    f.anchor.rollback();
                }
    }

    @Test
    void
            noOpsPreserveRawNullNegativeHistoryInvalidRatesAndNumericallyEqualDecimalScalesWithoutUpdateStatements()
                    throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                AtomicInteger writes = new AtomicInteger();
                var wrapped =
                        SupplierJournalGuiFixture.wrap(
                                f.anchor,
                                (method, args) -> {
                                    if (method.getName().equals("prepareStatement")
                                            && args[0].toString().startsWith("UPDATE "))
                                        writes.incrementAndGet();
                                    return SupplierJournalGuiFixture.invoke(f.anchor, method, args);
                                });
                var service = new NormalizedSupplierTaxRoundingService(wrapped);
                for (String values :
                        List.of(
                                "tax_sum=NULL,rounding_sum=NULL,currency_rate=NULL",
                                "tax_sum=-1,rounding_sum=-200,currency_rate=0",
                                "tax_sum=20,rounding_sum=0.05,currency_rate=-1")) {
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE " + table(credit) + " SET " + values + " WHERE id=" + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    var source = service.load(71, kind, id);
                    var plan = service.preview(source, source.values());
                    assertFalse(plan.changed());
                    service.confirm(plan);
                    f.anchor.commit();
                    assertEquals(before, f.snapshot());
                    if (source.values().tax() != null) {
                        var scaled =
                                new Values(
                                        source.values().tax().setScale(10),
                                        source.values().rounding().setScale(12));
                        plan = service.preview(source, scaled);
                        assertFalse(plan.changed());
                        service.confirm(plan);
                        f.anchor.commit();
                        assertEquals(before, f.snapshot());
                    }
                }
                assertEquals(0, writes.get());
            }
    }

    @Test
    void changedNegativeVatPrecisionOverflowNegativePayableAndInvalidRatesNeverWrite()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var adapter = f.session().taxRounding(Kind.INVOICE, id);
            var loaded = adapter.load();
            var before = f.snapshot();
            for (var invalid :
                    List.of(
                            new Values(BigDecimal.ONE.negate(), null),
                            new Values(new BigDecimal("1e-31"), null),
                            new Values(null, new BigDecimal("1e-31")),
                            new Values(new BigDecimal("1e70"), null),
                            new Values(null, new BigDecimal("1e70")),
                            new Values(null, new BigDecimal("-150.01")))) {
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(loaded, invalid));
                assertEquals(before, f.snapshot());
            }
            for (String rate : List.of("NULL", "0", "-1")) {
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE supplier_invoice SET currency_rate="
                                    + rate
                                    + " WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var source = adapter.load();
                var original = f.snapshot();
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(source, PROPOSED));
                adapter.confirm(adapter.preview(source, source.source().values()));
                assertEquals(original, f.snapshot());
            }
        }
    }

    @Test
    void maximumExactDecimalsAndAggregateOverflowAreCheckedWithoutRoundingAwayDigits()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var adapter = f.session().taxRounding(Kind.INVOICE, id);
            String maximum = "9".repeat(70) + "." + "9".repeat(30);
            var max = new BigDecimal(maximum);
            var loaded = adapter.load();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter.preview(loaded, new Values(max, null)));
            var precise =
                    new Values(
                            new BigDecimal("0.123456789012345678901234567890"),
                            new BigDecimal("-0.000000000000000000000000000001"));
            adapter.confirm(adapter.preview(loaded, precise));
            var current = adapter.load();
            decimal(precise.tax().toPlainString(), current.source().values().tax());
            decimal(precise.rounding().toPlainString(), current.source().values().rounding());
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "UPDATE supplier_invoice_row SET quantity=0 WHERE supplier_invoice_id="
                                + id);
            }
            f.anchor.commit();
            current = adapter.load();
            adapter.confirm(adapter.preview(current, new Values(max, null)));
            decimal(maximum, adapter.load().source().values().tax());
        }
    }

    @Test
    void completeSourceChangesInvalidatePreviewsAndSavesIncludingDatesRowsAccountsAndAuditNanos()
            throws Exception {
        for (var kind : Kind.values())
            for (String change :
                    List.of(
                            "tax",
                            "rounding",
                            "rate",
                            "date",
                            "row",
                            "account",
                            "voucher",
                            "audit"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().taxRounding(kind, id);
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
                                        case "tax" ->
                                                "UPDATE " + t + " SET tax_sum=0 WHERE id=" + id;
                                        case "rounding" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET rounding_sum=0 WHERE id="
                                                        + id;
                                        case "rate" ->
                                                "UPDATE "
                                                        + t
                                                        + " SET currency_rate=2 WHERE id="
                                                        + id;
                                        case "date" ->
                                                "UPDATE " + t + " SET due_date=NULL WHERE id=" + id;
                                        case "row" ->
                                                "UPDATE "
                                                        + t
                                                        + "_row SET quantity=2 WHERE "
                                                        + t
                                                        + "_id="
                                                        + id;
                                        case "account" ->
                                                "UPDATE "
                                                        + t
                                                        + "_default_account SET account_number=NULL"
                                                        + " WHERE "
                                                        + t
                                                        + "_id="
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
    void liveReferencesAndBookedBankExportedStatesAreRefusedWithoutDetachmentOrForeignScopeLeakage()
            throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false);
                    var adapter = f.session().taxRounding(Kind.INVOICE, id);
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
                    var adapter = f.session().taxRounding(kind, id);
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
    void lateSqlFailureRollsBackOnlyLocalWorkAndAllowsRetryAndCallerRollback() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                String t = table(credit);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "ALTER TABLE " + t + " ADD CONSTRAINT reject_tax CHECK(tax_sum<>25)");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierTaxRoundingService(f.anchor);
                var plan = service.preview(service.load(71, kind, id), PROPOSED);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirm(plan));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("ALTER TABLE " + t + " DROP CONSTRAINT reject_tax");
                }
                f.anchor.commit();
                before = f.snapshot();
                service.confirm(plan);
                f.anchor.rollback();
                assertEquals(before, f.snapshot());
                service.confirm(plan);
                f.anchor.commit();
                decimal("25", service.load(71, kind, id).values().tax());
            }
    }

    @Test
    void foreignTokensContextsClosedSessionsAndAutoCommitAreRefusedAndReplayOpensNoConnection()
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
            var adapter = session.taxRounding(Kind.INVOICE, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var foreign = session.taxRounding(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, PROPOSED));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierTaxRoundingService(f.anchor);
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
                                    .taxRounding(Kind.INVOICE, id)
                                    .load());
        }
    }

    @Test
    void unknownCommitBlocksAllSupplierEditingAndJournalsOnBothDurabilitySides() throws Exception {
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
                                                    throw new SQLException(
                                                            "Unknown VAT/rounding commit");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.taxRounding(Kind.INVOICE, id);
                var loaded = adapter.load();
                var preview = adapter.preview(loaded, PROPOSED);
                var dates =
                        session.dates(
                                org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierDatesService.Kind.INVOICE,
                                id);
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
                assertThrows(SQLException.class, dates::load);
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
                var current =
                        new NormalizedSupplierTaxRoundingService(f.anchor)
                                .load(71, Kind.INVOICE, id);
                if (durable) decimal("25", current.values().tax());
                else assertNull(current.values().tax());
                f.anchor.rollback();
            }
    }

    @Test
    void knownCommitCloseFailureKeepsResultWithoutBlockingOrRetry() throws Exception {
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
            var adapter = session.taxRounding(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            assertFalse(session.isBlocked());
            decimal("25", f.stored(true, id).getStoredTaxSum());
        }
    }

    @Test
    void
            editedAmountsLeaveStockUnchangedInvalidateOldJournalAndProduceBalancedInvoiceAndCreditJournals()
                    throws Exception {
        for (var kind : Kind.values())
            for (String rounding : List.of("0.05", "-0.05"))
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
                    var old =
                            journal.preview(
                                    SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                    var adapter = f.session().taxRounding(kind, id);
                    var loaded = adapter.load();
                    adapter.confirm(
                            adapter.preview(
                                    loaded,
                                    new Values(new BigDecimal("25"), new BigDecimal(rounding))));
                    assertThrows(IllegalArgumentException.class, () -> journal.post(old));
                    assertEquals(
                            stock,
                            new NormalizedStockStore(f.anchor)
                                    .read(71, List.of(product))
                                    .getQuantity(product));
                    f.anchor.rollback();
                    var accounting =
                            new NormalizedAccountingStore(
                                    f.anchor, SupplierJournalGuiFixture.CLOCK);
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
                    var expected =
                            f.stored(credit, id).generateVoucher(accounts, List.of(), List.of());
                    var total = SSVoucherMath.getDebetSum(expected, accounts.getAccounts());
                    assertEquals(
                            0,
                            total.compareTo(
                                    SSVoucherMath.getCreditSum(expected, accounts.getAccounts())));
                    f.anchor.rollback();
                    var fresh =
                            journal.preview(
                                    SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                    assertEquals(0, total.compareTo(fresh.summary().debit()));
                    assertEquals(0, total.compareTo(fresh.summary().credit()));
                    journal.post(fresh);
                    var ledger = accounting.getVouchers(year).getFirst();
                    assertEquals(
                            0,
                            total.compareTo(
                                    SSVoucherMath.getDebetSum(ledger, accounts.getAccounts())));
                    assertEquals(
                            0,
                            total.compareTo(
                                    SSVoucherMath.getCreditSum(ledger, accounts.getAccounts())));
                    assertEquals(
                            stock,
                            new NormalizedStockStore(f.anchor)
                                    .read(71, List.of(product))
                                    .getQuantity(product));
                    f.anchor.rollback();
                }
    }

    static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        long id = SupplierDatesAdapterTest.document(f, credit);
        try (var s = f.anchor.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO"
                        + " account(accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,3740,'Rounding',true,false,false FROM accounting_year WHERE"
                        + " legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO "
                            + table(credit)
                            + "_default_account("
                            + table(credit)
                            + "_id,account_type,account_number) VALUES("
                            + id
                            + ",'IncommingTax',2640),("
                            + id
                            + ",'Rounding',3740)");
        }
        f.anchor.commit();
        return id;
    }

    static String table(boolean credit) {
        return SupplierDatesAdapterTest.table(credit);
    }

    static void decimal(String expected, BigDecimal actual) {
        SupplierRowAmountsAdapterTest.decimal(expected, actual);
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
                "TAX_SUM", values.tax() == null ? null : values.tax().setScale(30).toPlainString());
        expected.put(
                "ROUNDING_SUM",
                values.rounding() == null ? null : values.rounding().setScale(30).toPlainString());
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
