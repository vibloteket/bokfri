package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOrder;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ExchangeRateValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedSaleDocumentStore;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

class CustomerFinancialCurrencyTest {
    @Test
    void bothKindsPreviewExplicitUnitsAndSaveOnlyCodeRateWithoutConvertingPricesTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + table(kind)
                                    + " SET order_numbers='Fakturan har inga ordrar' WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                assertEquals(List.of("EUR", "SEK", "USD"), loaded.data().currencies());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.data().currencies().clear());
                var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
                var preserved = preserved(f, kind, id);
                f.anchor.rollback();
                var preview = editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2.5")));
                assertEquals("EUR", preview.beforeCurrency());
                assertEquals("USD", preview.afterCurrency());
                assertEquals(0, new BigDecimal("125").compareTo(preview.after()));
                assertEquals(0, new BigDecimal("312.5").compareTo(preview.afterAccounting()));
                assertEquals(
                        before, CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
                f.anchor.rollback();
                assertSame(editor.save(preview), editor.save(preview));
                assertEquals(preserved, preserved(f, kind, id));
                f.anchor.rollback();
                var saved = editor.load().data();
                assertEquals("USD", saved.currency());
                assertEquals("SEK", saved.baseCurrency());
                assertEquals(
                        0, BigDecimal.valueOf(100).compareTo(saved.lines().getFirst().unitPrice()));
                assertEquals(0, new BigDecimal("2.5").compareTo(saved.exchangeRate()));
                assertEquals(
                        777,
                        CustomerFinancialAccountingTest.document(f, kind, id)
                                .getStoredVoucher()
                                .getNumber());
                f.anchor.rollback();
                // A code-only change must not be elided merely because the explicit rate is
                // unchanged.
                var sameRate = editor.load();
                editor.save(
                        editor.preview(
                                sameRate, patch(sameRate, "EUR", sameRate.data().exchangeRate())));
                assertEquals("EUR", editor.load().data().currency());
                assertEquals(
                        0, new BigDecimal("2.5").compareTo(editor.load().data().exchangeRate()));
            }
    }

    @Test
    void newCodesRequireExplicitPositiveExactRatesAndSameCurrencyRequiresOneTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            for (var rate : java.util.Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1")))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> editor.preview(loaded, patch(loaded, "USD", rate)));
            for (var code : List.of("", " ", "usd", "ZZZ", "USD "))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> editor.preview(loaded, patch(loaded, code, BigDecimal.ONE)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ExchangeRateValues(new BigDecimal("1e-31"), "USD"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> editor.preview(loaded, patch(loaded, "SEK", new BigDecimal("2"))));
            editor.save(editor.preview(loaded, patch(loaded, "SEK", BigDecimal.ONE)));
            assertEquals(
                    0,
                    new BigDecimal("125").compareTo(editor.load().data().accountingEquivalent()));
        }
    }

    @Test
    void
            invoicesWithPaymentCreditOrderAmbiguousNumberOrUnresolvedOrderSnapshotsCannotChangeCurrencyTest()
                    throws Exception {
        for (String reference : List.of("payment", "credit", "order", "duplicate", "orders"))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                var editor = editor(f, Kind.INVOICE, id);
                int number = editor.load().data().number();
                reference(f, reference, number, id, 71);
                var loaded = editor.load();
                var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
                f.anchor.rollback();
                assertThrows(
                        SQLException.class,
                        () -> editor.preview(loaded, patch(loaded, "USD", BigDecimal.ONE)));
                assertEquals(
                        before, CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
            }
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.CREDIT);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE customer_credit_invoice SET crediting_invoice_number=1 WHERE id="
                                + id);
            }
            f.anchor.commit();
            var editor = editor(f, Kind.CREDIT, id);
            var loaded = editor.load();
            assertThrows(
                    SQLException.class,
                    () -> editor.preview(loaded, patch(loaded, "USD", BigDecimal.ONE)));
            // The previous explicit stored-rate-only workflow remains usable for linked
            // corrections.
            assertNotNull(editor.preview(loaded, patch(loaded, null, new BigDecimal("2"))));
        }
    }

    @Test
    void newReferencesAfterPreviewAreCheckedAgainBeforeAnyCommitTest() throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                var editor = editor(f, Kind.INVOICE, id);
                var loaded = editor.load();
                var preview = editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2")));
                reference(f, reference, loaded.data().number(), id, 71);
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertFalse(editor.isBlocked());
                assertEquals("EUR", editor.load().data().currency());
            }
    }

    @Test
    void foreignCompanyReferencesDoNotBlockOrChangeAlongsideLocalCurrencyEditsTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var other = new SSNewCompany();
            other.setId(72);
            other.setName("Foreign");
            new NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK)
                    .addCompany(f.anchor, other);
            f.anchor.commit();
            var editor = editor(f, Kind.INVOICE, id);
            int number = editor.load().data().number();
            for (String reference : List.of("payment", "credit", "order"))
                reference(f, reference, number, id, 72);
            var loaded = editor.load();
            var before = preserved(f, Kind.INVOICE, id);
            f.anchor.rollback();
            editor.save(editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2"))));
            assertEquals(before, preserved(f, Kind.INVOICE, id));
        }
    }

    @Test
    void missingContextNumberAndHistoricalCodesArePreservedUnlessExplicitlyRepairedTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE customer_invoice SET currency_code=NULL WHERE id=" + id);
            }
            f.anchor.commit();
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            editor.save(editor.preview(loaded, patch(loaded, null, loaded.data().exchangeRate())));
            assertNull(editor.load().data().currency());
            var missing = editor.load();
            editor.save(editor.preview(missing, patch(missing, "USD", BigDecimal.ONE)));
            assertEquals("USD", editor.load().data().currency());
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE company SET currency_code=NULL WHERE legacy_id=71");
            }
            f.anchor.commit();
            var base = editor.load();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> editor.preview(base, patch(base, "EUR", BigDecimal.ONE)));
        }
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE customer_invoice SET number=NULL WHERE id=" + id);
            }
            f.anchor.commit();
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            assertThrows(
                    SQLException.class,
                    () -> editor.preview(loaded, patch(loaded, "USD", BigDecimal.ONE)));
        }
    }

    @Test
    void codeCatalogueAndBaseChangesAreStaleButUnusedDefaultRatesAreIgnoredTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            var preview = editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2")));
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE currency SET exchange_rate=777 WHERE code='USD'");
            }
            f.anchor.commit();
            editor.save(preview);
            assertEquals(0, new BigDecimal("2").compareTo(editor.load().data().exchangeRate()));
            var fresh = editor.load();
            var stale = editor.preview(fresh, patch(fresh, "EUR", BigDecimal.ONE));
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("INSERT INTO currency(code) VALUES('GBP')");
            }
            f.anchor.commit();
            assertThrows(IllegalArgumentException.class, () -> editor.save(stale));
            var next = editor.load();
            var base = editor.preview(next, patch(next, "EUR", BigDecimal.ONE));
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE company SET currency_code='EUR' WHERE legacy_id=71");
            }
            f.anchor.commit();
            assertThrows(IllegalArgumentException.class, () -> editor.save(base));
        }
    }

    @Test
    void lateRowFailureRestoresCurrencyRateDatesAndAmountsThenRetriesTest() throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(kind)
                                    + "_row ADD CONSTRAINT currency_failure CHECK(unit_price<250)");
                }
                f.anchor.commit();
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                var values =
                        new FinancialValues(
                                LocalDate.of(2026, 11, 14),
                                null,
                                List.of(new LineAmounts(19, BigDecimal.ONE, new BigDecimal("300"))),
                                List.of(),
                                List.of(),
                                null,
                                new ExchangeRateValues(new BigDecimal("2"), "USD"));
                var preview = editor.preview(loaded, values);
                var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
                f.anchor.rollback();
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertEquals(
                        before, CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
                assertFalse(editor.isBlocked());
                f.anchor.rollback();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE " + table(kind) + "_row DROP CONSTRAINT currency_failure");
                }
                f.anchor.commit();
                assertEquals(0, preview.after().compareTo(editor.save(preview).total()));
                assertEquals("USD", editor.load().data().currency());
            }
    }

    @Test
    void directStoreRejectsInvalidForeignBookedAndReferencedChangesWithoutLosingOuterWorkTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var loaded = editor(f, kind, id).load();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE " + table(kind) + " SET ocr_number='outer' WHERE id=" + id);
                }
                var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
                var store = new NormalizedInvoiceStore(f.anchor);
                for (var values :
                        List.of(
                                patch(loaded, "ZZZ", BigDecimal.ONE),
                                patch(loaded, "USD", BigDecimal.ZERO),
                                patch(loaded, "SEK", new BigDecimal("2")))) {
                    assertThrows(SQLException.class, () -> update(store, kind, 71, id, values));
                    assertEquals(
                            before,
                            CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
                }
                assertThrows(
                        SQLException.class,
                        () -> update(store, kind, 999, id, patch(loaded, "USD", BigDecimal.ONE)));
                if (kind == Kind.INVOICE)
                    assertThrows(
                            SQLException.class,
                            () ->
                                    update(
                                            store,
                                            kind,
                                            71,
                                            f.invoice,
                                            patch(loaded, "USD", BigDecimal.ONE)));
                f.anchor.rollback();
                if (kind == Kind.INVOICE) reference(f, "payment", loaded.data().number(), id, 71);
                else {
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE customer_credit_invoice SET crediting_invoice_number=1"
                                        + " WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                }
                assertThrows(
                        SQLException.class,
                        () -> update(store, kind, 71, id, patch(loaded, "USD", BigDecimal.ONE)));
                f.anchor.rollback();
                assertEquals("EUR", editor(f, kind, id).load().data().currency());
            }
    }

    @Test
    void unknownCurrencyCommitBlocksBlindRetryRegardlessOfDurabilityTest() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                var editor =
                        new CustomerFinancialEditAdapter(
                                () -> {
                                    var c = f.open();
                                    return (Connection)
                                            Proxy.newProxyInstance(
                                                    Connection.class.getClassLoader(),
                                                    new Class<?>[] {Connection.class},
                                                    (p, m, a) -> {
                                                        if (m.getName().equals("commit")) {
                                                            if (durable) c.commit();
                                                            throw new SQLException(
                                                                    "Unknown currency commit");
                                                        }
                                                        try {
                                                            return m.invoke(c, a);
                                                        } catch (InvocationTargetException e) {
                                                            throw e.getCause();
                                                        }
                                                    });
                                },
                                f.gate,
                                71,
                                11,
                                Kind.INVOICE,
                                id,
                                CustomerCreditGuiFixture.CLOCK);
                var loaded = editor.load();
                var preview = editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2")));
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertTrue(editor.isBlocked());
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertEquals(
                        durable ? "USD" : "EUR",
                        editor(f, Kind.INVOICE, id).load().data().currency());
            }
    }

    @Test
    void separateInvoiceAndCreditPostingUsesExplicitNewCurrencyRateWithoutRepricingRowsTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                editor.save(editor.preview(loaded, patch(loaded, "USD", new BigDecimal("2"))));
                assertEquals(
                        777,
                        CustomerFinancialAccountingTest.document(f, kind, id)
                                .getStoredVoucher()
                                .getNumber());
                f.anchor.rollback();
                var journal =
                        new CustomerJournalAdapter(
                                f::open,
                                f.gate,
                                71,
                                11,
                                kind == Kind.INVOICE
                                        ? CustomerJournalAdapter.Kind.INVOICE
                                        : CustomerJournalAdapter.Kind.CREDIT,
                                CustomerCreditGuiFixture.CLOCK);
                var preview = journal.preview(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
                assertEquals(0, new BigDecimal("250").compareTo(preview.summary().debit()));
                assertEquals(0, new BigDecimal("250").compareTo(preview.summary().credit()));
                journal.post(preview);
                assertThrows(SQLException.class, editor::load);
            }
    }

    static long target(CustomerCreditGuiFixture f, Kind kind) throws Exception {
        long id = CustomerFinancialExchangeTest.target(f, kind);
        try (var sql = f.anchor.createStatement()) {
            sql.executeUpdate(
                    "UPDATE "
                            + table(kind)
                            + " SET order_numbers=NULL"
                            + (kind == Kind.CREDIT ? ",crediting_invoice_number=NULL" : "")
                            + " WHERE id="
                            + id);
        }
        f.anchor.commit();
        return id;
    }

    static CustomerFinancialEditAdapter editor(CustomerCreditGuiFixture f, Kind kind, long id) {
        return CustomerFinancialAccountingTest.editor(f, kind, id);
    }

    static FinancialValues patch(
            CustomerFinancialEditAdapter.Loaded loaded, String code, BigDecimal rate) {
        return new FinancialValues(
                loaded.data().values().date(),
                loaded.data().values().dueDate(),
                loaded.data().values().rows(),
                List.of(),
                List.of(),
                null,
                new ExchangeRateValues(rate, code));
    }

    static void reference(CustomerCreditGuiFixture f, String type, int number, long id, int company)
            throws Exception {
        switch (type) {
            case "payment" -> {
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "INSERT INTO inpayment(legacy_id,company_id,entered) SELECT"
                                    + " 901,id,FALSE FROM company WHERE legacy_id="
                                    + company);
                    sql.executeUpdate(
                            "INSERT INTO inpayment_row(inpayment_id,row_number,invoice_number)"
                                    + " SELECT id,0,"
                                    + number
                                    + " FROM inpayment WHERE legacy_id=901");
                }
                f.anchor.commit();
            }
            case "credit" -> {
                var credit = SSCreditInvoice.forPersistence();
                credit.setNumber(901);
                credit.setCreditingNr(number);
                new NormalizedInvoiceStore(f.anchor).addCreditInvoice(company, credit);
                f.anchor.commit();
            }
            case "order" -> {
                var order = SSOrder.forPersistence();
                order.setNumber(901);
                order.setInvoiceNr(number);
                new NormalizedSaleDocumentStore(f.anchor).addOrder(company, order);
                f.anchor.commit();
            }
            case "orders" -> {
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE customer_invoice SET order_numbers='historical order 5' WHERE"
                                    + " id="
                                    + id);
                }
                f.anchor.commit();
            }
            case "duplicate" -> {
                var store = new NormalizedInvoiceStore(f.anchor);
                store.addInvoice(company, store.findInvoice(company, id).orElseThrow().document());
                f.anchor.commit();
            }
            default -> throw new AssertionError(type);
        }
    }

    private static java.util.Map<String, List<String>> preserved(
            CustomerCreditGuiFixture f, Kind kind, long id) throws Exception {
        return CustomerFinancialEditAdapterTest.snapshot(
                f, kind, id, Set.of(), Set.of(), Set.of("CURRENCY_CODE", "CURRENCY_RATE"));
    }

    private static void update(
            NormalizedInvoiceStore store, Kind kind, int company, long id, FinancialValues values)
            throws Exception {
        if (kind == Kind.INVOICE) store.updateInvoiceFinancialValues(company, id, values);
        else store.updateCreditFinancialValues(company, id, values);
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "customer_invoice" : "customer_credit_invoice";
    }
}
