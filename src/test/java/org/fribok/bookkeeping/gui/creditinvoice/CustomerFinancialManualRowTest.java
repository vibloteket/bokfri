package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ManualLine;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.TaxValues;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

class CustomerFinancialManualRowTest {
    @Test
    void bothKindsPreviewInsertReadOnlyAndPreserveEveryExistingSqlValueAndKeyTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                var before = snapshot(f);
                f.anchor.rollback();
                var value = manual(20);
                var values = patch(loaded, List.of(value));
                assertThrows(UnsupportedOperationException.class, () -> values.addedRows().clear());
                var preview = editor.preview(loaded, values);
                assertEquals(0, new BigDecimal("60").compareTo(preview.after()));
                assertEquals(before, snapshot(f));
                f.anchor.rollback();
                var saved = editor.save(preview);
                assertSame(saved, editor.save(preview));
                assertEquals(
                        before,
                        CustomerFinancialEditAdapterTest.snapshot(
                                f, kind, id, Set.of(), Set.of(), Set.of(), Set.of(20)));
                f.anchor.rollback();
                var fresh = editor(f, kind, id).load().data();
                assertEquals(
                        List.of(19, 20), fresh.lines().stream().map(l -> l.rowNumber()).toList());
                var row = fresh.lines().get(1);
                assertEquals("Manual Å", row.description());
                assertEquals("P-B", row.project());
                assertEquals("U-1", row.resultUnit());
                assertEquals(3022, row.account());
                try (var sql = f.anchor.createStatement();
                        var r =
                                sql.executeQuery(
                                        "SELECT product_number,unit_name FROM "
                                                + table(kind)
                                                + "_row WHERE "
                                                + owner(kind)
                                                + "="
                                                + id
                                                + " AND row_number=20")) {
                    assertTrue(r.next());
                    assertNull(r.getString(1));
                    assertNull(r.getString(2));
                }
                assertEquals(
                        777,
                        CustomerFinancialAccountingTest.document(f, kind, id)
                                .getStoredVoucher()
                                .getNumber());
            }
    }

    @Test
    void invalidNewFieldsKeysAccountsRatesAndDimensionsFailWithoutWritesTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            var before = snapshot(f);
            f.anchor.rollback();
            for (var row :
                    List.of(
                            new ManualLine(
                                    20,
                                    "",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    "P-B",
                                    "U-1"),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ZERO,
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    "P-B",
                                    "U-1"),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    new BigDecimal("-1"),
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    "P-B",
                                    "U-1"),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    new BigDecimal("101"),
                                    3022,
                                    "TAXRATE_0",
                                    "P-B",
                                    "U-1"),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    null,
                                    "TAXRATE_0",
                                    null,
                                    null),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    1510,
                                    "TAXRATE_0",
                                    null,
                                    null),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3999,
                                    "TAXRATE_0",
                                    null,
                                    null),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    null,
                                    "P-B",
                                    "U-1"),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    null,
                                    null),
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    "P-closed",
                                    "U-1"),
                            manual(19),
                            manual(-1)))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> editor.preview(loaded, patch(loaded, List.of(row))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> editor.preview(loaded, patch(loaded, List.of(manual(20), manual(20)))));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    new BigDecimal("1e-31"),
                                    null,
                                    3010,
                                    "TAXRATE_0",
                                    null,
                                    null));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ManualLine(
                                    20,
                                    "x",
                                    BigDecimal.ONE,
                                    BigDecimal.TEN,
                                    null,
                                    3010,
                                    "25%",
                                    null,
                                    null));
            assertEquals(before, snapshot(f));
        }
    }

    @Test
    void coupledHeaderTaxAndReplacingFinalExistingRowStayExplicitAndNonemptyTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE customer_invoice SET tax_rate_1=NULL WHERE id=" + id);
            }
            f.anchor.commit();
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            var added =
                    new ManualLine(
                            20,
                            "Taxed",
                            BigDecimal.ONE,
                            new BigDecimal("20"),
                            null,
                            3022,
                            "TAXRATE_1",
                            "P-B",
                            "U-1");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> editor.preview(loaded, patch(loaded, List.of(added))));
            var values =
                    new FinancialValues(
                            loaded.data().values().date(),
                            loaded.data().values().dueDate(),
                            List.of(),
                            List.of(),
                            List.of(),
                            new TaxValues(false, new BigDecimal("25"), null, null),
                            null,
                            List.of(),
                            List.of(19),
                            List.of(added));
            assertEquals(
                    0,
                    new BigDecimal("25")
                            .compareTo(editor.save(editor.preview(loaded, values)).total()));
            assertEquals(
                    List.of(20),
                    editor.load().data().lines().stream().map(l -> l.rowNumber()).toList());
        }
    }

    @Test
    void sourceChangesAndKeyCapacityRejectStaleOrCollidingInsertionsTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var editor = editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            var preview = editor.preview(loaded, patch(loaded, List.of(manual(20))));
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE customer_invoice_row SET description='external' WHERE invoice_id="
                                + id);
            }
            f.anchor.commit();
            assertThrows(IllegalArgumentException.class, () -> editor.save(preview));
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE customer_invoice_row SET row_number=2147483647 WHERE invoice_id="
                                + id);
            }
            f.anchor.commit();
            var maximum = editor.load();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            editor.preview(
                                    maximum, patch(maximum, List.of(manual(Integer.MAX_VALUE)))));
        }
    }

    @Test
    void lateSecondInsertFailureRestoresEditsDeletionAndFirstInsertThenRetriesTest() throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = CustomerFinancialRowRemovalTest.target(f, kind);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(kind)
                                    + "_row ADD CONSTRAINT manual_failure CHECK(unit_price<250)");
                }
                f.anchor.commit();
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                var second =
                        new ManualLine(
                                21,
                                "Second",
                                BigDecimal.ONE,
                                new BigDecimal("300"),
                                null,
                                3022,
                                "TAXRATE_0",
                                "P-B",
                                "U-1");
                var values =
                        new FinancialValues(
                                LocalDate.of(2026, 11, 14),
                                null,
                                List.of(new LineAmounts(19, BigDecimal.ONE, new BigDecimal("45"))),
                                List.of(),
                                List.of(),
                                new TaxValues(false, new BigDecimal("30"), null, null),
                                null,
                                List.of(),
                                List.of(7),
                                List.of(manual(20), second));
                var preview = editor.preview(loaded, values);
                var before = snapshot(f);
                f.anchor.rollback();
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertFalse(editor.isBlocked());
                assertEquals(before, snapshot(f));
                f.anchor.rollback();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE " + table(kind) + "_row DROP CONSTRAINT manual_failure");
                }
                f.anchor.commit();
                assertEquals(0, new BigDecimal("365").compareTo(editor.save(preview).total()));
                assertEquals(
                        List.of(19, 20, 21),
                        editor.load().data().lines().stream().map(l -> l.rowNumber()).toList());
            }
    }

    @Test
    void localSavepointKeepsOuterWorkOnDuplicateCollisionForeignAndBookedInsertTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var loaded = editor(f, kind, id).load();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE " + table(kind) + " SET ocr_number='outer' WHERE id=" + id);
                }
                var before = snapshot(f);
                var store = new NormalizedInvoiceStore(f.anchor);
                for (var added :
                        List.of(List.of(manual(20), manual(20)), List.of(manual(20), manual(19)))) {
                    var values = patch(loaded, added);
                    if (added.get(1).rowNumber() == 20)
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> update(store, kind, 71, id, values));
                    else
                        assertThrows(SQLException.class, () -> update(store, kind, 71, id, values));
                    assertEquals(before, snapshot(f));
                    assertThrows(SQLException.class, () -> update(store, kind, 999, id, values));
                    if (kind == Kind.INVOICE)
                        assertThrows(
                                SQLException.class,
                                () -> update(store, kind, 71, f.invoice, values));
                }
                f.anchor.rollback();
            }
    }

    @Test
    void unknownInsertCommitBlocksBlindRetryRegardlessOfDurabilityTest() throws Exception {
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
                                                                    "Unknown manual insert");
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
                var preview = editor.preview(loaded, patch(loaded, List.of(manual(20))));
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertTrue(editor.isBlocked());
                assertThrows(SQLException.class, editor::load);
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertEquals(
                        durable ? List.of(19, 20) : List.of(19),
                        editor(f, Kind.INVOICE, id).load().data().lines().stream()
                                .map(l -> l.rowNumber())
                                .toList());
            }
    }

    @Test
    void separateInvoiceAndCreditJournalsRetainOldAndNewDimensionGroupsTest() throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var editor = editor(f, kind, id);
                var loaded = editor.load();
                editor.save(editor.preview(loaded, patch(loaded, List.of(manual(20)))));
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
                assertEquals(0, new BigDecimal("60").compareTo(preview.summary().debit()));
                assertEquals(0, new BigDecimal("60").compareTo(preview.summary().credit()));
                for (String project : List.of("P-Å", "P-B"))
                    assertTrue(
                            preview.summary().rows().stream()
                                    .anyMatch(
                                            r ->
                                                    Integer.valueOf(3022).equals(r.account())
                                                            && project.equals(r.project())
                                                            && "U-1".equals(r.resultUnit())));
                journal.post(preview);
                assertThrows(SQLException.class, editor::load);
            }
    }

    static long target(CustomerCreditGuiFixture f, Kind kind) throws Exception {
        long id = CustomerFinancialRowRemovalTest.target(f, kind);
        var e = editor(f, kind, id);
        var loaded = e.load();
        e.save(e.preview(loaded, CustomerFinancialRowRemovalTest.patch(loaded, List.of(7))));
        return id;
    }

    static ManualLine manual(int key) {
        return new ManualLine(
                key,
                "Manual Å",
                new BigDecimal("2"),
                BigDecimal.TEN,
                null,
                3022,
                "TAXRATE_0",
                "P-B",
                "U-1");
    }

    static FinancialValues patch(
            CustomerFinancialEditAdapter.Loaded loaded, List<ManualLine> added) {
        return new FinancialValues(
                loaded.data().values().date(),
                loaded.data().values().dueDate(),
                loaded.data().values().rows(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                added);
    }

    static CustomerFinancialEditAdapter editor(CustomerCreditGuiFixture f, Kind kind, long id) {
        return CustomerFinancialAccountingTest.editor(f, kind, id);
    }

    private static Map<String, List<String>> snapshot(CustomerCreditGuiFixture f) throws Exception {
        return CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "customer_invoice" : "customer_credit_invoice";
    }

    private static String owner(Kind kind) {
        return kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
    }

    private static void update(
            NormalizedInvoiceStore store, Kind kind, int company, long id, FinancialValues values)
            throws Exception {
        if (kind == Kind.INVOICE) store.updateInvoiceFinancialValues(company, id, values);
        else store.updateCreditFinancialValues(company, id, values);
    }
}
