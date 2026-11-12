package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.*;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSOrder;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerDocumentDeletionAdapterTest {
    @Test void invoicePreviewIsReadOnlyAndConfirmedDeleteOnlyRemovesItsGraphAndListedOrderLinks() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5), other = unbooked(f, 6), linked = order(f, 201, 5); order(f, 202, 6);
            AtomicInteger opens = new AtomicInteger();
            var adapter = adapter(f, Kind.INVOICE, id, () -> { opens.incrementAndGet(); return f.open(); });
            var before = snapshot(f.anchor, null, -1, List.of()); var preview = adapter.preview();
            assertEquals(before, snapshot(f.anchor, null, -1, List.of()));
            assertEquals(id, preview.plan().id()); assertEquals(5, preview.plan().number());
            assertEquals(List.of(linked), preview.plan().detachedOrderIds());
            var preserved = snapshot(f.anchor, Kind.INVOICE, id, List.of(linked)); f.anchor.rollback();
            var saved = adapter.delete(preview); assertEquals(List.of(linked), saved.detachedOrderIds());
            assertSame(saved, adapter.delete(preview)); assertEquals(2, opens.get());
            assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).isEmpty());
            assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, other).isPresent());
            assertEquals(preserved, snapshot(f.anchor, Kind.INVOICE, id, List.of(linked)));
            assertNull(new NormalizedSaleDocumentStore(f.anchor).findOrder(71, linked).orElseThrow().document().getInvoiceNr());
        }
    }
    @Test void creditDeletionLeavesOriginalInvoiceAndEveryOtherSqlRowUntouched() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var creator = f.adapter(); long id = creator.save(creator.preview(null, java.math.BigDecimal.TEN)).id();
            var adapter = adapter(f, Kind.CREDIT, id, f::open); var preview = adapter.preview();
            var preserved = snapshot(f.anchor, Kind.CREDIT, id, List.of()); f.anchor.rollback();
            adapter.delete(preview);
            assertEquals(preserved, snapshot(f.anchor, Kind.CREDIT, id, List.of()));
            assertTrue(new NormalizedInvoiceStore(f.anchor).findCreditInvoice(71, id).isEmpty());
            assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, f.invoice).orElseThrow().document().isEntered());
        }
    }
    @Test void bookedMissingForeignAndClosedSelectionCannotDelete() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            assertThrows(SQLException.class, () -> adapter(f, Kind.INVOICE, f.invoice, f::open).preview());
            assertThrows(SQLException.class, () -> adapter(f, Kind.INVOICE, -1, f::open).preview());
            assertThrows(SQLException.class, () -> new CustomerDocumentDeletionAdapter(f::open, f.gate, 72, 11, Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK).preview());
            long id = unbooked(f, 5);
            var session = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var adapter = session.deletion(Kind.INVOICE, id); var preview = adapter.preview(); session.close();
            assertThrows(SQLException.class, adapter::preview); assertThrows(SQLException.class, () -> adapter.delete(preview));
            assertThrows(SQLException.class, () -> session.deletion(Kind.INVOICE, id));
        }
    }
    @Test void changedSourceOrOrderSetAndForeignPreviewAreRejectedWithoutWrites() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5); var a = adapter(f, Kind.INVOICE, id, f::open); var b = adapter(f, Kind.INVOICE, id, f::open);
            var preview = a.preview(); assertThrows(SQLException.class, () -> b.delete(preview));
            var store = new NormalizedInvoiceStore(f.anchor); var changed = store.findInvoice(71, id).orElseThrow().document();
            changed.getRows().getFirst().setUnitprice(new java.math.BigDecimal("101")); store.updateInvoice(71, id, changed); f.anchor.commit();
            var before = snapshot(f.anchor, null, -1, List.of()); f.anchor.rollback();
            assertThrows(SQLException.class, () -> a.delete(preview)); assertEquals(before, snapshot(f.anchor, null, -1, List.of())); f.anchor.rollback();
            var fresh = a.preview(); order(f, 201, 5);
            assertThrows(SQLException.class, () -> a.delete(fresh));
            assertTrue(store.findInvoice(71, id).isPresent());
        }
    }
    @Test void referencesAddedAfterConfirmationAndAmbiguousLinkedNumbersProtectInvoices() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5); var adapter = adapter(f, Kind.INVOICE, id, f::open); var preview = adapter.preview();
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("INSERT INTO inpayment(legacy_id,company_id,entered) SELECT 1,id,FALSE FROM company WHERE legacy_id=71");
                s.executeUpdate("INSERT INTO inpayment_row(inpayment_id,row_number,invoice_number,value) SELECT id,0,5,10 FROM inpayment");
            } f.anchor.commit();
            var before = snapshot(f.anchor, null, -1, List.of()); f.anchor.rollback();
            assertThrows(SQLException.class, () -> adapter.delete(preview)); assertThrows(SQLException.class, adapter::preview);
            assertEquals(before, snapshot(f.anchor, null, -1, List.of())); f.anchor.rollback();
            long duplicate = unbooked(f, 6); unbooked(f, 6); order(f, 201, 6);
            assertThrows(SQLException.class, () -> adapter(f, Kind.INVOICE, duplicate, f::open).preview());
        }
    }
    @Test void creditReferencesAndBookedCreditsAreRefusedWithoutTouchingOtherKinds() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5);
            var credit = se.swedsoft.bookkeeping.data.SSCreditInvoice.forPersistence(); credit.setNumber(1); credit.setCreditingNr(5);
            long creditId = new NormalizedInvoiceStore(f.anchor).addCreditInvoice(71, credit); f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter(f, Kind.INVOICE, id, f::open).preview());
            try (var s = f.anchor.createStatement()) { s.executeUpdate("UPDATE customer_credit_invoice SET entered=TRUE WHERE id=" + creditId); } f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter(f, Kind.CREDIT, creditId, f::open).preview());
        }
    }
    @Test void lateDeleteFailureRestoresDocumentAndOrderLinksThenAllowsRetry() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5), linked = order(f, 201, 5); AtomicBoolean fail = new AtomicBoolean(true);
            var adapter = adapter(f, Kind.INVOICE, id, () -> {
                Connection c = f.open(); return proxy(c, Connection.class, (method, args) -> {
                    if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("DELETE FROM customer_invoice WHERE")) {
                        PreparedStatement s = (PreparedStatement) invoke(c, method, args);
                        return proxy(s, PreparedStatement.class, (m, a) -> {
                            Object result = invoke(s, m, a);
                            if (m.getName().equals("executeUpdate") && fail.getAndSet(false)) throw new SQLException("Injected late delete failure"); return result;
                        });
                    }
                    return invoke(c, method, args);
                });
            });
            var preview = adapter.preview(); var before = snapshot(f.anchor, null, -1, List.of()); f.anchor.rollback();
            assertThrows(SQLException.class, () -> adapter.delete(preview)); assertFalse(adapter.isBlocked());
            assertEquals(before, snapshot(f.anchor, null, -1, List.of())); f.anchor.rollback();
            assertEquals(List.of(linked), adapter.delete(preview).detachedOrderIds());
        }
    }
    @Test void unknownCommitBlocksRetryWhetherDeletionWasDurableOrNot() throws Exception {
        for (boolean durable : List.of(false, true)) try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5);
            var adapter = adapter(f, Kind.INVOICE, id, () -> {
                Connection c = f.open(); return proxy(c, Connection.class, (m, a) -> {
                    if (m.getName().equals("commit")) { if (durable) c.commit(); throw new SQLException("Unknown deletion commit"); }
                    return invoke(c, m, a);
                });
            });
            var preview = adapter.preview(); assertThrows(SQLException.class, () -> adapter.delete(preview));
            assertTrue(adapter.isBlocked()); assertThrows(SQLException.class, adapter::preview); assertThrows(SQLException.class, () -> adapter.delete(preview));
            assertEquals(durable, new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).isEmpty());
        }
    }
    @Test void confirmedCloseFailureRemainsSuccessfulAndFormatOrYearErrorsNeverMigrate() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = unbooked(f, 5); AtomicInteger opens = new AtomicInteger();
            var adapter = adapter(f, Kind.INVOICE, id, () -> {
                int open = opens.incrementAndGet(); Connection c = f.open(); return proxy(c, Connection.class, (m, a) -> {
                    Object result = invoke(c, m, a); if (m.getName().equals("close") && open == 2) throw new SQLException("Committed close failure"); return result;
                });
            });
            var preview = adapter.preview(); var saved = adapter.delete(preview); assertSame(saved, adapter.delete(preview)); assertEquals(2, opens.get());
            assertThrows(SQLException.class, () -> new CustomerDocumentDeletionAdapter(f::open, f.gate, 71, 999, Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK).preview());
            DataFormatManager.recordVersion(f.anchor, 2); f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter(f, Kind.INVOICE, f.invoice, f::open).preview()); assertEquals(2, DataFormatManager.detect(f.anchor));
        }
    }
    @Test void nullAndUnreferencedDuplicateNumbersStillDeleteOnlyTheConfirmedSqlIdentity() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long missing = unbooked(f, null), first = unbooked(f, 6), second = unbooked(f, 6);
            for (long id : List.of(missing, first)) {
                var adapter = adapter(f, Kind.INVOICE, id, f::open); adapter.delete(adapter.preview());
            }
            var store = new NormalizedInvoiceStore(f.anchor);
            assertTrue(store.findInvoice(71, missing).isEmpty()); assertTrue(store.findInvoice(71, first).isEmpty());
            assertTrue(store.findInvoice(71, second).isPresent());
        }
    }

    static long unbooked(CustomerCreditGuiFixture f, Integer number) throws Exception {
        var store = new NormalizedInvoiceStore(f.anchor); var document = store.findInvoice(71, f.invoice).orElseThrow().document();
        document.setNumber(number); document.setEntered(false); document.setVoucher(null);
        long id = store.addInvoice(71, document); f.anchor.commit(); return id;
    }
    static long order(CustomerCreditGuiFixture f, int number, Integer invoice) throws Exception {
        var order = SSOrder.forPersistence(); order.setNumber(number); order.setInvoiceNr(invoice);
        long id = new NormalizedSaleDocumentStore(f.anchor).addOrder(71, order); f.anchor.commit(); return id;
    }
    private static CustomerDocumentDeletionAdapter adapter(CustomerCreditGuiFixture f, Kind kind, long id, CustomerCreditDialogAdapter.Connections connections) {
        return new CustomerDocumentDeletionAdapter(connections, f.gate, 71, 11, kind, id, CustomerCreditGuiFixture.CLOCK);
    }
    static Map<String, List<String>> snapshot(Connection c, Kind removedKind, long removedId, List<Long> orders) throws Exception {
        Map<String, List<String>> tables = new TreeMap<>();
        String prefix = removedKind == Kind.INVOICE ? "CUSTOMER_INVOICE" : "CUSTOMER_CREDIT_INVOICE";
        String owner = removedKind == Kind.INVOICE ? "INVOICE_ID" : "CREDIT_INVOICE_ID";
        try (var rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME"); List<String> values = new ArrayList<>();
                try (var statement = c.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
                    var metadata = rows.getMetaData();
                    while (rows.next()) {
                        if (removedKind != null && (table.equals(prefix) || List.of("_ADDRESS", "_DEFAULT_ACCOUNT", "_ROW", "_VOUCHER", "_VOUCHER_ROW").stream().anyMatch(s -> table.equals(prefix + s)))
                                && rows.getLong(table.equals(prefix) ? "ID" : owner) == removedId) continue;
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String name = metadata.getColumnName(i);
                            if (table.equals("CUSTOMER_ORDER") && name.equals("INVOICE_NUMBER") && orders.contains(rows.getLong("ID"))) continue;
                            String value = rows.getString(i); row.append(name).append(':').append(value == null ? "NULL" : value.length() + ":" + value).append(';');
                        }
                        values.add(row.toString());
                    }
                }
                Collections.sort(values); tables.put(table, values);
            }
        }
        assertFalse(tables.isEmpty()); return tables;
    }
    private interface Call { Object run(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(T original, Class<T> type, Call call) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> call.run(m, a))); }
    private static Object invoke(Object original, Method m, Object[] args) throws Throwable { try { return m.invoke(original, args); } catch (InvocationTargetException error) { throw error.getCause(); } }
}
