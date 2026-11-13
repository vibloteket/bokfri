package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;

class CustomerFinancialEditAdapterTest {
    @Test
    void bothKindsPreviewReadOnlyAndSaveOnlyAllowedColumnsWithRealNoncontiguousRowKeysTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                var store = new NormalizedInvoiceStore(f.anchor);
                var document =
                        kind == Kind.INVOICE
                                ? store.findInvoice(71, id).orElseThrow().document()
                                : store.findCreditInvoice(71, id).orElseThrow().document();
                SSSaleRow extra = new SSSaleRow();
                extra.setDescription("untouched");
                extra.setQuantity(7);
                extra.setUnitprice(new BigDecimal("3"));
                document.getRows().add(extra);
                document.setVoucher(
                        new se.swedsoft.bookkeeping.data.SSVoucher(777, LocalDate.of(2026, 9, 1)));
                if (kind == Kind.INVOICE) store.updateInvoice(71, id, document);
                else
                    store.updateCreditInvoice(
                            71, id, (se.swedsoft.bookkeeping.data.SSCreditInvoice) document);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + table(kind)
                                    + " SET printed=TRUE,ocr_number='',reminder_count=3,interest_invoiced=TRUE"
                                    + " WHERE id="
                                    + id);
                    s.executeUpdate(
                            "UPDATE "
                                    + table(kind)
                                    + "_row SET row_number=7,tax_code=NULL WHERE "
                                    + owner(kind)
                                    + "="
                                    + id
                                    + " AND row_number=0");
                    s.executeUpdate(
                            "UPDATE "
                                    + table(kind)
                                    + "_row SET row_number=19 WHERE "
                                    + owner(kind)
                                    + "="
                                    + id
                                    + " AND row_number=1");
                }
                f.anchor.commit();
                if (kind == Kind.INVOICE) CustomerDocumentDeletionAdapterTest.order(f, 201, 5);
                AtomicInteger opens = new AtomicInteger();
                var adapter =
                        adapter(
                                f,
                                kind,
                                id,
                                () -> {
                                    opens.incrementAndGet();
                                    return f.open();
                                });
                var before = snapshot(f, null, -1, Set.of());
                var loaded = adapter.load();
                assertThrows(
                        UnsupportedOperationException.class, () -> loaded.data().lines().clear());
                assertEquals(
                        List.of(7, 19),
                        loaded.data().lines().stream().map(row -> row.rowNumber()).toList());
                var values = change(loaded, new BigDecimal("2"), new BigDecimal("12.5"));
                var preview = adapter.preview(loaded, values);
                assertEquals(before, snapshot(f, null, -1, Set.of()));
                assertEquals(0, new BigDecimal("46").compareTo(preview.after()));
                var preserved = snapshot(f, kind, id, Set.of(7));
                f.anchor.rollback();
                var saved = adapter.save(preview);
                assertSame(saved, adapter.save(preview));
                assertEquals(3, opens.get());
                assertEquals(kind, saved.kind());
                assertEquals(id, saved.id());
                assertEquals(0, new BigDecimal("46").compareTo(saved.total()));
                assertEquals(preserved, snapshot(f, kind, id, Set.of(7)));
                var updated =
                        kind == Kind.INVOICE
                                ? store.findInvoice(71, id).orElseThrow().document()
                                : store.findCreditInvoice(71, id).orElseThrow().document();
                assertEquals(777, updated.getStoredVoucher().getNumber());
                assertEquals(
                        0,
                        new BigDecimal("2").compareTo(updated.getRows().getFirst().getQuantity()));
                assertEquals(
                        0,
                        new BigDecimal("12.5")
                                .compareTo(updated.getRows().getFirst().getUnitprice()));
            }
    }

    @Test
    void absentDatesQuantitiesPricesTaxAndAccountsAreNotInventedTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "UPDATE customer_invoice SET due_date=NULL,currency_code=NULL WHERE id="
                                + id);
                s.executeUpdate(
                        "UPDATE customer_invoice_row SET"
                            + " quantity=NULL,tax_code=NULL,account_number=NULL WHERE invoice_id="
                                + id);
            }
            f.anchor.commit();
            var adapter = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = adapter.load();
            assertNull(loaded.data().currency());
            assertNull(loaded.data().lines().getFirst().quantity());
            var values = new FinancialValues(null, null, List.of(new LineAmounts(0, null, null)));
            var preserved = snapshot(f, Kind.INVOICE, id, Set.of(0));
            f.anchor.rollback();
            var preview = adapter.preview(loaded, values);
            assertEquals(0, BigDecimal.ZERO.compareTo(preview.after()));
            adapter.save(preview);
            assertEquals(preserved, snapshot(f, Kind.INVOICE, id, Set.of(0)));
            f.anchor.rollback();
            var fresh = adapter.load();
            assertNull(fresh.data().values().date());
            assertNull(fresh.data().lines().getFirst().unitPrice());
        }
    }

    @Test
    void staleMetadataOtherRowsAndCompanyRoundingNeverOverwriteFreshDataTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var adapter = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = adapter.load();
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE customer_invoice SET ocr_number='new' WHERE id=" + id);
            }
            f.anchor.commit();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter.preview(loaded, change(loaded, BigDecimal.ONE, BigDecimal.TEN)));
            var fresh = adapter.load();
            var preview = adapter.preview(fresh, change(fresh, BigDecimal.ONE, BigDecimal.TEN));
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "UPDATE customer_invoice_row SET description='changed' WHERE invoice_id="
                                + id);
            }
            f.anchor.commit();
            var before = snapshot(f, null, -1, Set.of());
            f.anchor.rollback();
            assertThrows(IllegalArgumentException.class, () -> adapter.save(preview));
            assertEquals(before, snapshot(f, null, -1, Set.of()));
            f.anchor.rollback();
            var another = adapter.load();
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE company SET rounding_off=FALSE WHERE legacy_id=71");
            }
            f.anchor.commit();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            adapter.preview(
                                    another, change(another, BigDecimal.ONE, BigDecimal.TEN)));
        }
    }

    @Test
    void precisionDatesAndCompleteStoredRowKeySetAreValidatedBeforeAnyWriteTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var adapter = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = adapter.load();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new LineAmounts(0, new BigDecimal("1E-31"), BigDecimal.ONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new LineAmounts(0, BigDecimal.ONE, new BigDecimal("1E70")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new FinancialValues(LocalDate.of(0, 1, 1), null, List.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter.preview(loaded, new FinancialValues(null, null, List.of())));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            adapter.preview(
                                    loaded,
                                    new FinancialValues(
                                            null,
                                            null,
                                            List.of(
                                                    new LineAmounts(
                                                            99, BigDecimal.ONE, BigDecimal.ONE)))));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            adapter.preview(
                                    loaded,
                                    new FinancialValues(
                                            null,
                                            null,
                                            List.of(
                                                    new LineAmounts(
                                                            0, BigDecimal.ONE, BigDecimal.ONE),
                                                    new LineAmounts(
                                                            0, BigDecimal.ONE, BigDecimal.ONE)))));
            var preview =
                    adapter.preview(
                            loaded,
                            change(loaded, new BigDecimal("1.25"), new BigDecimal("-2.40")));
            adapter.save(preview);
            assertEquals(0, new BigDecimal("-3").compareTo(preview.after()));
        }
    }

    @Test
    void bookedForeignClosedWrongYearAndLegacySourcesNeverFallBackOrWriteTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            assertThrows(
                    SQLException.class, () -> adapter(f, Kind.INVOICE, f.invoice, f::open).load());
            long id = target(f, Kind.INVOICE);
            var adapter = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = adapter.load();
            var other = adapter(f, Kind.INVOICE, id, f::open);
            assertThrows(
                    SQLException.class,
                    () -> other.preview(loaded, change(loaded, BigDecimal.ONE, BigDecimal.ONE)));
            var preview = adapter.preview(loaded, change(loaded, BigDecimal.ONE, BigDecimal.ONE));
            assertThrows(SQLException.class, () -> other.save(preview));
            var session =
                    new CustomerDocumentGuiSession(
                            f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var leased = session.financialEdit(Kind.INVOICE, id);
            var lease = leased.load();
            session.close();
            assertThrows(
                    SQLException.class,
                    () -> leased.preview(lease, change(lease, BigDecimal.ONE, BigDecimal.ONE)));
            assertThrows(SQLException.class, () -> session.financialEdit(Kind.INVOICE, id));
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE customer_invoice SET entered=TRUE WHERE id=" + id);
            }
            f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter.save(preview));
            assertThrows(
                    SQLException.class,
                    () ->
                            new CustomerFinancialEditAdapter(
                                            f::open,
                                            f.gate,
                                            71,
                                            999,
                                            Kind.INVOICE,
                                            id,
                                            CustomerCreditGuiFixture.CLOCK)
                                    .load());
            DataFormatManager.recordVersion(f.anchor, 2);
            f.anchor.commit();
            assertThrows(SQLException.class, adapter::load);
            assertEquals(2, DataFormatManager.detect(f.anchor));
        }
    }

    @Test
    void failureAfterHeaderAndFirstRowWriteRollsBackEverySqlTableThenRetriesTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var store = new NormalizedInvoiceStore(f.anchor);
            var source = store.findInvoice(71, id).orElseThrow().document();
            SSSaleRow extra = new SSSaleRow();
            extra.setQuantity(1);
            extra.setUnitprice(new BigDecimal("100"));
            source.getRows().add(extra);
            store.updateInvoice(71, id, source);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate(
                        "ALTER TABLE customer_invoice_row ADD CONSTRAINT financial_fail"
                            + " CHECK(unit_price<250)");
            }
            f.anchor.commit();
            var adapter = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = adapter.load();
            var values =
                    new FinancialValues(
                            LocalDate.of(2026, 11, 13),
                            null,
                            List.of(
                                    new LineAmounts(0, new BigDecimal("2"), new BigDecimal("200")),
                                    new LineAmounts(
                                            1, new BigDecimal("2"), new BigDecimal("300"))));
            var preview = adapter.preview(loaded, values);
            var before = snapshot(f, null, -1, Set.of());
            f.anchor.rollback();
            assertThrows(SQLException.class, () -> adapter.save(preview));
            assertFalse(adapter.isBlocked());
            assertEquals(before, snapshot(f, null, -1, Set.of()));
            f.anchor.rollback();
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("ALTER TABLE customer_invoice_row DROP CONSTRAINT financial_fail");
            }
            f.anchor.commit();
            assertEquals(0, new BigDecimal("1000").compareTo(adapter.save(preview).total()));
        }
    }

    @Test
    void unknownCommitBlocksBlindRetryBeforeOrAfterDurabilityTest() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                var adapter =
                        adapter(
                                f,
                                Kind.INVOICE,
                                id,
                                () -> {
                                    Connection c = f.open();
                                    return wrap(
                                            c,
                                            (m, a) -> {
                                                if (m.getName().equals("commit")) {
                                                    if (durable) c.commit();
                                                    throw new SQLException(
                                                            "Unknown financial commit");
                                                }
                                                return invoke(c, m, a);
                                            });
                                });
                var loaded = adapter.load();
                var preview =
                        adapter.preview(
                                loaded, change(loaded, new BigDecimal("2"), new BigDecimal("25")));
                assertThrows(SQLException.class, () -> adapter.save(preview));
                assertTrue(adapter.isBlocked());
                assertThrows(SQLException.class, adapter::load);
                assertThrows(SQLException.class, () -> adapter.save(preview));
                var quantity =
                        new NormalizedInvoiceStore(f.anchor)
                                .findInvoice(71, id)
                                .orElseThrow()
                                .document()
                                .getRows()
                                .getFirst()
                                .getQuantity();
                assertEquals(
                        0, (durable ? new BigDecimal("2") : BigDecimal.ONE).compareTo(quantity));
            }
    }

    @Test
    void confirmedCloseFailureRemainsSuccessfulAndReplayDoesNotOverwriteLaterWorkTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            AtomicInteger opens = new AtomicInteger();
            var adapter =
                    adapter(
                            f,
                            Kind.INVOICE,
                            id,
                            () -> {
                                int n = opens.incrementAndGet();
                                Connection c = f.open();
                                return wrap(
                                        c,
                                        (m, a) -> {
                                            Object result = invoke(c, m, a);
                                            if (m.getName().equals("close") && n == 3)
                                                throw new SQLException("Committed close failure");
                                            return result;
                                        });
                            });
            var loaded = adapter.load();
            var preview =
                    adapter.preview(
                            loaded, change(loaded, new BigDecimal("2"), new BigDecimal("25")));
            var saved = adapter.save(preview);
            assertSame(saved, adapter.save(preview));
            assertEquals(3, opens.get());
            assertFalse(adapter.isBlocked());
            var other = adapter(f, Kind.INVOICE, id, f::open);
            var latest = other.load();
            other.save(
                    other.preview(
                            latest, change(latest, new BigDecimal("2"), new BigDecimal("30"))));
            assertSame(saved, adapter.save(preview));
            assertEquals(3, opens.get());
            assertEquals(
                    0,
                    new BigDecimal("30")
                            .compareTo(
                                    new NormalizedInvoiceStore(f.anchor)
                                            .findInvoice(71, id)
                                            .orElseThrow()
                                            .document()
                                            .getRows()
                                            .getFirst()
                                            .getUnitprice()));
        }
    }

    @Test
    void separateJournalRegeneratesNewAmountsRatherThanUsingPreservedOldVoucherSnapshotTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            var store = new NormalizedInvoiceStore(f.anchor);
            var document = store.findInvoice(71, id).orElseThrow().document();
            document.setVoucher(
                    new se.swedsoft.bookkeeping.data.SSVoucher(777, document.getLocalDate()));
            store.updateInvoice(71, id, document);
            f.anchor.commit();
            var editor = adapter(f, Kind.INVOICE, id, f::open);
            var loaded = editor.load();
            editor.save(
                    editor.preview(
                            loaded, change(loaded, new BigDecimal("2"), new BigDecimal("75"))));
            assertEquals(
                    777,
                    store.findInvoice(71, id)
                            .orElseThrow()
                            .document()
                            .getStoredVoucher()
                            .getNumber());
            f.anchor.rollback();
            var journal =
                    new CustomerJournalAdapter(
                            f::open,
                            f.gate,
                            71,
                            11,
                            CustomerJournalAdapter.Kind.INVOICE,
                            CustomerCreditGuiFixture.CLOCK);
            var preview =
                    journal.preview(CustomerJournalAdapterTest.FROM, CustomerJournalAdapterTest.TO);
            assertEquals(0, new BigDecimal("150").compareTo(preview.summary().debit()));
            journal.post(preview);
            assertTrue(store.findInvoice(71, id).orElseThrow().document().isEntered());
            assertThrows(SQLException.class, editor::load);
        }
    }

    static long target(CustomerCreditGuiFixture f, Kind kind) throws Exception {
        if (kind == Kind.INVOICE) return CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
        var creator = f.adapter();
        return creator.save(creator.preview(null, BigDecimal.TEN)).id();
    }

    static FinancialValues change(
            CustomerFinancialEditAdapter.Loaded loaded, BigDecimal quantity, BigDecimal price) {
        int first = loaded.data().lines().getFirst().rowNumber();
        return new FinancialValues(
                LocalDate.of(2026, 11, 12),
                null,
                loaded.data().lines().stream()
                        .map(
                                row ->
                                        new LineAmounts(
                                                row.rowNumber(),
                                                row.rowNumber() == first
                                                        ? quantity
                                                        : row.quantity(),
                                                row.rowNumber() == first ? price : row.unitPrice()))
                        .toList());
    }

    private static CustomerFinancialEditAdapter adapter(
            CustomerCreditGuiFixture f,
            Kind kind,
            long id,
            CustomerCreditDialogAdapter.Connections connections) {
        return new CustomerFinancialEditAdapter(
                connections, f.gate, 71, 11, kind, id, CustomerCreditGuiFixture.CLOCK);
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "customer_invoice" : "customer_credit_invoice";
    }

    private static String owner(Kind kind) {
        return kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
    }

    static Map<String, List<String>> snapshot(
            CustomerCreditGuiFixture f, Kind kind, long id, Set<Integer> keys) throws Exception {
        Map<String, List<String>> result = new TreeMap<>();
        String target = kind == null ? "" : table(kind).toUpperCase(Locale.ROOT),
                owner = kind == null ? "" : owner(kind).toUpperCase(Locale.ROOT);
        try (var tables =
                f.anchor.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                List<String> rows = new ArrayList<>();
                try (var s = f.anchor.createStatement();
                        var values = s.executeQuery("SELECT * FROM " + name)) {
                    var m = values.getMetaData();
                    while (values.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= m.getColumnCount(); i++) {
                            String column = m.getColumnName(i);
                            if (name.equals(target)
                                    && values.getLong("ID") == id
                                    && Set.of("INVOICE_DATE", "DUE_DATE").contains(column))
                                continue;
                            if (name.equals(target + "_ROW")
                                    && values.getLong(owner) == id
                                    && keys.contains(values.getInt("ROW_NUMBER"))
                                    && Set.of("QUANTITY", "UNIT_PRICE").contains(column)) continue;
                            String value = values.getString(i);
                            row.append(column)
                                    .append(':')
                                    .append(value == null ? "NULL" : value.length() + ":" + value)
                                    .append(';');
                        }
                        rows.add(row.toString());
                    }
                }
                Collections.sort(rows);
                result.put(name, rows);
            }
        }
        return result;
    }

    private interface Call {
        Object call(Method method, Object[] args) throws Throwable;
    }

    private static Connection wrap(Connection c, Call call) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (p, m, a) -> call.call(m, a));
    }

    private static Object invoke(Object c, Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(c, a);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
