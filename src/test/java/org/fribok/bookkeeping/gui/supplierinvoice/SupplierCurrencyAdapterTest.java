package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.CurrencyValues;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.Kind;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSProduct;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierCurrencyAdapterTest {
    static final CurrencyValues PROPOSED = new CurrencyValues("USD", new BigDecimal("2"));

    @Test
    void bothFamiliesPreviewExplicitCurrencyRateAndPreserveAllNumericAmountsAndOtherSql()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                var adapter = f.session().currency(kind, id);
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                var loaded = adapter.load();
                assertTrue(loaded.currencies().containsAll(List.of("EUR", "USD", "SEK")));
                assertThrows(
                        UnsupportedOperationException.class, () -> loaded.currencies().clear());
                var plan = adapter.preview(loaded, PROPOSED);
                assertEquals("EUR", plan.plan().before().currency());
                assertEquals("USD", plan.plan().after().currency());
                decimal("150", plan.plan().afterTotals().payable());
                decimal("187.5", plan.plan().beforeTotals().accountingEquivalent());
                decimal("300", plan.plan().afterTotals().accountingEquivalent());
                assertEquals(before, f.snapshot());
                adapter.confirm(plan);
                assertNarrow(f, credit, id, before, header, PROPOSED);
            }
    }

    @Test
    void independentCodeRateAndCombinedEditsUpdateOnlyRequestedColumnsWithoutFetchingDefaultRates()
            throws Exception {
        for (var kind : Kind.values())
            for (int mode : List.of(0, 1, 2))
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
                    var service = new NormalizedSupplierExchangeRateService(wrapped);
                    var source = service.load(71, kind, id);
                    var next =
                            new CurrencyValues(
                                    mode == 1 ? "EUR" : "USD",
                                    mode == 0 ? new BigDecimal("1.2500") : new BigDecimal("2"));
                    var before = f.snapshot();
                    var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                    service.confirmCurrency(service.previewCurrency(source, next));
                    f.anchor.commit();
                    assertNarrow(f, credit, id, before, header, next);
                    assertEquals(1, updates.size());
                    assertTrue(
                            updates.getFirst()
                                    .contains(
                                            mode == 0
                                                    ? " SET currency_code=? WHERE"
                                                    : mode == 1
                                                            ? " SET currency_rate=? WHERE"
                                                            : " SET currency_code=?,currency_rate=?"
                                                                    + " WHERE"));
                }
    }

    @Test
    void changedPairsRequireLiveCatalogueCodeExplicitPositiveExactRateAndCompanyCurrencyRules()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, kind == Kind.CREDIT);
                var adapter = f.session().currency(kind, id);
                var loaded = adapter.load();
                var before = f.snapshot();
                for (var invalid :
                        List.of(
                                new CurrencyValues(null, BigDecimal.ONE),
                                new CurrencyValues("", BigDecimal.ONE),
                                new CurrencyValues(" ", BigDecimal.ONE),
                                new CurrencyValues("MISSING", BigDecimal.ONE),
                                new CurrencyValues("USD ", BigDecimal.ONE),
                                new CurrencyValues("usd", BigDecimal.ONE),
                                new CurrencyValues("USD\n", BigDecimal.ONE),
                                new CurrencyValues("USD", null),
                                new CurrencyValues("USD", BigDecimal.ZERO),
                                new CurrencyValues("USD", BigDecimal.ONE.negate()),
                                new CurrencyValues("USD", new BigDecimal("1e-31")),
                                new CurrencyValues("USD", new BigDecimal("1e70")),
                                new CurrencyValues("SEK", new BigDecimal("2")))) {
                    assertThrows(
                            IllegalArgumentException.class, () -> adapter.preview(loaded, invalid));
                    assertEquals(before, f.snapshot());
                }
                var base = new CurrencyValues("SEK", BigDecimal.ONE);
                var originalHeader =
                        SupplierDocumentMetadataAdapterTest.header(f, kind == Kind.CREDIT, id);
                adapter.confirm(adapter.preview(loaded, base));
                assertNarrow(f, kind == Kind.CREDIT, id, before, originalHeader, base);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("UPDATE company SET currency_code=NULL WHERE legacy_id=71");
                }
                f.anchor.commit();
                var missing = adapter.load();
                assertThrows(
                        IllegalArgumentException.class, () -> adapter.preview(missing, PROPOSED));
            }
    }

    @Test
    void
            rawNullAndScaleNoOpsStayUntouchedAndCatalogueDefaultsAreIgnoredButLateCodeDeletionRefusesSave()
                    throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
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
            var service = new NormalizedSupplierExchangeRateService(wrapped);
            for (String values :
                    List.of(
                            "currency_code=NULL,currency_rate=NULL",
                            "currency_code=NULL,currency_rate=-1",
                            "currency_code='EUR',currency_rate=1.25")) {
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("UPDATE supplier_invoice SET " + values + " WHERE id=" + id);
                }
                f.anchor.commit();
                var before = f.snapshot();
                var source = service.load(71, Kind.INVOICE, id);
                var pair =
                        new CurrencyValues(
                                source.currency(),
                                source.values().rate() == null
                                        ? null
                                        : source.values().rate().setScale(8));
                var noop = service.previewCurrency(source, pair);
                assertFalse(noop.changed());
                service.confirmCurrency(noop);
                f.anchor.commit();
                assertEquals(before, f.snapshot());
            }
            assertEquals(0, updates.get());
            var adapter = f.session().currency(Kind.INVOICE, id);
            var loaded = adapter.load();
            var plan = adapter.preview(loaded, PROPOSED);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "UPDATE currency SET description='Changed',exchange_rate=777 WHERE"
                                + " code='USD'");
            }
            f.anchor.commit();
            adapter.confirm(plan);
            decimal("2", adapter.load().source().values().rate());
            loaded = adapter.load();
            var target = new CurrencyValues("GBP", new BigDecimal("3"));
            var late = adapter.preview(loaded, target);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("DELETE FROM currency WHERE code='GBP'");
            }
            f.anchor.commit();
            var before = f.snapshot();
            assertThrows(IllegalArgumentException.class, () -> adapter.confirm(late));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void fullGraphAndCompanyCurrencyChangesInvalidateBothPreviewAndConfirmation() throws Exception {
        for (var kind : Kind.values())
            for (String change : List.of("company", "document", "rate", "vat", "row", "audit"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    var adapter = f.session().currency(kind, id);
                    var loaded = adapter.load();
                    var preview = adapter.preview(loaded, PROPOSED);
                    String t = table(credit);
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                switch (change) {
                                    case "company" ->
                                            "UPDATE company SET currency_code='EUR' WHERE"
                                                    + " legacy_id=71";
                                    case "document" ->
                                            "UPDATE "
                                                    + t
                                                    + " SET currency_code='SEK' WHERE id="
                                                    + id;
                                    case "rate" ->
                                            "UPDATE " + t + " SET currency_rate=1.3 WHERE id=" + id;
                                    case "vat" -> "UPDATE " + t + " SET tax_sum=1 WHERE id=" + id;
                                    case "row" ->
                                            "UPDATE "
                                                    + t
                                                    + "_row SET quantity=2 WHERE "
                                                    + t
                                                    + "_id="
                                                    + id;
                                    default ->
                                            "UPDATE "
                                                    + t
                                                    + "_voucher_row SET edited_at=TIMESTAMP"
                                                    + " '2026-10-01 12:13:14.123456788+0:00' WHERE "
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
    void exactRateBoundariesAndUnroundedProductsRoundTripWithoutRewritingDocumentAmounts()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var adapter = f.session().currency(Kind.INVOICE, id);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE supplier_invoice SET tax_sum=0.123456789012345678901234567890 WHERE"
                                + " id="
                                + id);
            }
            f.anchor.commit();
            var loaded = adapter.load();
            var before = f.snapshot();
            var header = SupplierDocumentMetadataAdapterTest.header(f, false, id);
            var precise =
                    new CurrencyValues("USD", new BigDecimal("0.123456789012345678901234567890"));
            var plan = adapter.preview(loaded, precise);
            assertEquals(
                    0,
                    loaded.source()
                            .totals()
                            .payable()
                            .multiply(precise.rate())
                            .compareTo(plan.plan().afterTotals().accountingEquivalent()));
            adapter.confirm(plan);
            assertNarrow(f, false, id, before, header, precise);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE supplier_invoice SET tax_sum=0 WHERE id=" + id);
                sql.executeUpdate(
                        "UPDATE supplier_invoice_row SET quantity=0 WHERE supplier_invoice_id="
                                + id);
            }
            f.anchor.commit();
            loaded = adapter.load();
            var maximum =
                    new CurrencyValues(
                            "USD", new BigDecimal("9".repeat(70) + "." + "9".repeat(30)));
            adapter.confirm(adapter.preview(loaded, maximum));
            decimal(maximum.rate().toPlainString(), adapter.load().source().values().rate());
        }
    }

    @Test
    void liveReferencesAndBookedBankExportedStatesAreRefusedWithoutDetachmentOrForeignScopeLeakage()
            throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            for (int company : List.of(71, 72))
                try (var f = new SupplierJournalGuiFixture()) {
                    long id = document(f, false);
                    var adapter = f.session().currency(Kind.INVOICE, id);
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
                    var adapter = f.session().currency(kind, id);
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
                            "ALTER TABLE "
                                    + t
                                    + " ADD CONSTRAINT reject_tax CHECK(currency_code<>'USD')");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierExchangeRateService(f.anchor);
                var plan = service.previewCurrency(service.load(71, kind, id), PROPOSED);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = SupplierDocumentDeletionAdapterTest.snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.confirmCurrency(plan));
                assertEquals(before, SupplierDocumentDeletionAdapterTest.snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("ALTER TABLE " + t + " DROP CONSTRAINT reject_tax");
                }
                f.anchor.commit();
                before = f.snapshot();
                service.confirmCurrency(plan);
                f.anchor.rollback();
                assertEquals(before, f.snapshot());
                service.confirmCurrency(plan);
                f.anchor.commit();
                decimal("2", service.load(71, kind, id).values().rate());
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
            var adapter = session.currency(Kind.INVOICE, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var foreign = session.currency(Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> foreign.preview(loaded, PROPOSED));
            assertThrows(SQLException.class, () -> foreign.confirm(preview));
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            session.close();
            assertThrows(SQLException.class, adapter::load);
            assertThrows(SQLException.class, () -> adapter.confirm(preview));
            assertEquals(3, opens.get());
            var service = new NormalizedSupplierExchangeRateService(f.anchor);
            var source = service.load(71, Kind.INVOICE, id);
            var noop =
                    service.previewCurrency(
                            source, new CurrencyValues(source.currency(), source.values().rate()));
            f.anchor.commit();
            f.anchor.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.confirmCurrency(noop));
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
                                    .currency(Kind.INVOICE, id)
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
                var adapter = session.currency(Kind.INVOICE, id);
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
                        new NormalizedSupplierExchangeRateService(f.anchor)
                                .load(71, Kind.INVOICE, id);
                if (durable) decimal("2", current.values().rate());
                else decimal("1.25", current.values().rate());
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
            var adapter = session.currency(Kind.CREDIT, id);
            var loaded = adapter.load();
            var preview = adapter.preview(loaded, PROPOSED);
            var saved = adapter.confirm(preview);
            assertSame(saved, adapter.confirm(preview));
            assertEquals(3, opens.get());
            assertFalse(session.isBlocked());
            decimal("2", f.stored(true, id).getCurrencyRate());
        }
    }

    @Test
    void
            editedAmountsLeaveStockUnchangedInvalidateOldJournalAndProduceBalancedInvoiceAndCreditJournals()
                    throws Exception {
        for (var kind : Kind.values())
            for (String rounding : List.of("0.5", "2"))
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
                    var adapter = f.session().currency(kind, id);
                    var loaded = adapter.load();
                    adapter.confirm(
                            adapter.preview(
                                    loaded, new CurrencyValues("USD", new BigDecimal(rounding))));
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
        long id = SupplierExchangeRateAdapterTest.document(f, credit);
        for (String code : List.of("USD", "GBP")) {
            try (var s = f.anchor.prepareStatement("SELECT code FROM currency WHERE code=?")) {
                s.setString(1, code);
                try (var r = s.executeQuery()) {
                    if (!r.next())
                        try (var insert =
                                f.anchor.prepareStatement(
                                        "INSERT INTO currency(code,exchange_rate) VALUES(?,999)")) {
                            insert.setString(1, code);
                            insert.executeUpdate();
                        }
                }
            }
        }
        f.anchor.commit();
        return id;
    }

    static String table(boolean credit) {
        return SupplierExchangeRateAdapterTest.table(credit);
    }

    static void decimal(String expected, BigDecimal actual) {
        SupplierExchangeRateAdapterTest.decimal(expected, actual);
    }

    static void assertNarrow(
            SupplierJournalGuiFixture f,
            boolean credit,
            long id,
            Map<String, List<String>> before,
            Map<String, String> oldHeader,
            CurrencyValues values)
            throws Exception {
        Map<String, String> expected = new LinkedHashMap<>(oldHeader);
        expected.put("CURRENCY_CODE", values.currency());
        expected.put(
                "CURRENCY_RATE",
                values.rate() == null ? null : values.rate().setScale(30).toPlainString());
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
