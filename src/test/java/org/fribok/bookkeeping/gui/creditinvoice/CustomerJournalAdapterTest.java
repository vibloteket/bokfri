package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CustomerJournalAdapterTest {
    static final LocalDate FROM = LocalDate.of(2026, 1, 1), TO = LocalDate.of(2026, 12, 31);
    @Test void immutableReadOnlyInvoicePreviewShowsActualAllocatedNumbersAndPostsExactlyOnce() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); AtomicInteger opens = new AtomicInteger();
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, () -> { opens.incrementAndGet(); return f.open(); });
            var before = snapshot(f); var preview = adapter.preview(FROM, TO); assertEquals(before, snapshot(f)); f.anchor.rollback();
            var s = preview.summary(); assertEquals(71, s.companyId()); assertEquals(11, s.yearId());
            assertEquals(1, s.journalNumber()); assertEquals(1, s.voucherNumber()); assertEquals(TO, s.voucherDate());
            assertEquals(List.of(id), s.documents().stream().map(CustomerJournalAdapter.Document::id).toList());
            assertEquals(0, new java.math.BigDecimal("100").compareTo(s.debit())); assertEquals(0, s.debit().compareTo(s.credit()));
            assertThrows(UnsupportedOperationException.class, () -> s.documents().clear()); assertThrows(UnsupportedOperationException.class, () -> s.rows().clear());
            var saved = adapter.post(preview); assertSame(saved, adapter.post(preview)); assertEquals(2, opens.get());
            assertEquals(1, saved.voucherNumber()); assertEquals(List.of(id), saved.documentIds()); assertEquals(1, voucherCount(f));
            assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).orElseThrow().document().isEntered());
            assertEquals(1, new NormalizedRegisterStore(f.anchor).autoIncrementValue(71, "invoicejournal"));
            f.anchor.rollback(); assertThrows(IllegalArgumentException.class, () -> adapter.preview(FROM, TO));
        }
    }
    @Test void creditPostingLeavesOriginalInvoiceTablesUnchanged() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var creator = f.adapter(); long id = creator.save(creator.preview(null, java.math.BigDecimal.TEN)).id();
            var before = snapshot(f); f.anchor.rollback();
            var adapter = adapter(f, CustomerJournalAdapter.Kind.CREDIT, f::open); var preview = adapter.preview(FROM, TO);
            assertEquals(id, preview.summary().documents().getFirst().id()); adapter.post(preview);
            var after = snapshot(f);
            for (String table : before.keySet()) if (table.startsWith("CUSTOMER_INVOICE")) assertEquals(before.get(table), after.get(table), table);
            assertTrue(new NormalizedInvoiceStore(f.anchor).findCreditInvoice(71, id).orElseThrow().document().isEntered());
            assertEquals(1, voucherCount(f));
        }
    }
    @Test void staleFinancialValuesAndChangedDocumentSetRejectWholeJournalWithoutWrites() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open);
            var preview = adapter.preview(FROM, TO); var store = new NormalizedInvoiceStore(f.anchor);
            var changed = store.findInvoice(71, id).orElseThrow().document(); changed.getRows().getFirst().setUnitprice(new java.math.BigDecimal("101"));
            store.updateInvoice(71, id, changed); f.anchor.commit(); var before = snapshot(f); f.anchor.rollback();
            assertThrows(IllegalArgumentException.class, () -> adapter.post(preview)); assertEquals(before, snapshot(f)); f.anchor.rollback();
            var fresh = adapter.preview(FROM, TO); CustomerDocumentDeletionAdapterTest.unbooked(f, 6);
            assertThrows(IllegalArgumentException.class, () -> adapter.post(fresh)); assertEquals(0, voucherCount(f));
        }
    }
    @Test void changedJournalCounterRejectsOldAllocatedNumbers() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            CustomerDocumentDeletionAdapterTest.unbooked(f, 5); var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open);
            var preview = adapter.preview(FROM, TO); new NormalizedRegisterStore(f.anchor).bumpAutoIncrement(71, "invoicejournal"); f.anchor.commit();
            var before = snapshot(f); f.anchor.rollback(); assertThrows(IllegalArgumentException.class, () -> adapter.post(preview));
            assertEquals(before, snapshot(f)); f.anchor.rollback(); assertEquals(2, adapter.preview(FROM, TO).summary().journalNumber());
        }
    }
    @Test void lateCounterFailureRollsBackEnteredFlagsSnapshotsLedgerAndNumberingThenRetries() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            try (var s = f.anchor.createStatement()) { s.executeUpdate("ALTER TABLE company_auto_increment ADD CONSTRAINT gui_counter_failure CHECK(counter_value<1)"); } f.anchor.commit();
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open); var preview = adapter.preview(FROM, TO);
            var before = snapshot(f); f.anchor.rollback(); assertThrows(SQLException.class, () -> adapter.post(preview));
            assertFalse(adapter.isBlocked()); assertEquals(before, snapshot(f)); f.anchor.rollback();
            try (var s = f.anchor.createStatement()) { s.executeUpdate("ALTER TABLE company_auto_increment DROP CONSTRAINT gui_counter_failure"); } f.anchor.commit();
            adapter.post(preview); assertEquals(1, voucherCount(f));
            assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).orElseThrow().document().isEntered());
        }
    }
    @Test void unknownCommitBlocksBlindRetryOnBothSidesOfDurability() throws Exception {
        for (boolean durable : List.of(false, true)) try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, () -> {
                Connection c = f.open(); return wrap(c, (m, a) -> {
                    if (m.getName().equals("commit")) { if (durable) c.commit(); throw new SQLException("Unknown journal commit"); }
                    return invoke(c, m, a);
                });
            });
            var preview = adapter.preview(FROM, TO); assertThrows(SQLException.class, () -> adapter.post(preview));
            assertTrue(adapter.isBlocked()); assertThrows(SQLException.class, () -> adapter.post(preview)); assertThrows(SQLException.class, () -> adapter.preview(FROM, TO));
            assertEquals(durable ? 1 : 0, voucherCount(f)); assertEquals(durable, new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).orElseThrow().document().isEntered());
        }
    }
    @Test void postCommitCloseFailureStillConfirmsReplayWithoutAnotherWrite() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            CustomerDocumentDeletionAdapterTest.unbooked(f, 5); AtomicInteger opens = new AtomicInteger();
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, () -> {
                int number = opens.incrementAndGet(); Connection c = f.open(); return wrap(c, (m, a) -> {
                    Object result = invoke(c, m, a); if (m.getName().equals("close") && number == 2) throw new SQLException("Committed close failure"); return result;
                });
            });
            var preview = adapter.preview(FROM, TO); var saved = adapter.post(preview); assertSame(saved, adapter.post(preview));
            assertEquals(2, opens.get()); assertFalse(adapter.isBlocked()); assertEquals(1, voucherCount(f));
        }
    }
    @Test void invalidEmptyForeignClosedAndUnsupportedContextsNeverWriteOrMigrate() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open);
            assertThrows(IllegalArgumentException.class, () -> adapter.preview(TO, FROM)); assertThrows(IllegalArgumentException.class, () -> adapter.preview(FROM, TO));
            CustomerDocumentDeletionAdapterTest.unbooked(f, 5); var preview = adapter.preview(FROM, TO);
            assertThrows(SQLException.class, () -> adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open).post(preview));
            var session = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var leased = session.journal(CustomerJournalAdapter.Kind.INVOICE); var loaded = leased.preview(FROM, TO); session.close();
            assertThrows(SQLException.class, () -> leased.post(loaded)); assertThrows(SQLException.class, () -> session.journal(CustomerJournalAdapter.Kind.INVOICE));
            assertThrows(SQLException.class, () -> new CustomerJournalAdapter(f::open, f.gate, 71, 999, CustomerJournalAdapter.Kind.INVOICE, CustomerCreditGuiFixture.CLOCK).preview(FROM, TO));
            DataFormatManager.recordVersion(f.anchor, 2); f.anchor.commit(); assertThrows(SQLException.class, () -> adapter.preview(FROM, TO)); assertEquals(2, DataFormatManager.detect(f.anchor));
            DataFormatManager.recordVersion(f.anchor, 3);
            try (var s = f.anchor.createStatement()) { s.executeUpdate("DELETE FROM bokfri_schema_history"); } f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter.preview(FROM, TO));
        }
    }
    @Test void dimensionCodesRemainStringsAndArePreservedIntoThePostedVoucher() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("INSERT INTO project(company_id,number,concluded) SELECT id,'007',FALSE FROM company");
                s.executeUpdate("INSERT INTO result_unit(company_id,number) SELECT id,'R-01' FROM company");
            }
            var store = new NormalizedInvoiceStore(f.anchor); var invoice = store.findInvoice(71, id).orElseThrow().document();
            invoice.getRows().getFirst().setProjectNr("007"); invoice.getRows().getFirst().setResultUnitNr("R-01"); store.updateInvoice(71, id, invoice); f.anchor.commit();
            var adapter = adapter(f, CustomerJournalAdapter.Kind.INVOICE, f::open); var preview = adapter.preview(FROM, TO);
            assertTrue(preview.summary().rows().stream().anyMatch(row -> "007".equals(row.project()) && "R-01".equals(row.resultUnit())));
            adapter.post(preview);
            assertTrue(accounting(f).getVouchers().getFirst().getRows().stream().anyMatch(row -> "007".equals(row.getProjectNr()) && "R-01".equals(row.getResultUnitNr())));
        }
    }
    private static CustomerJournalAdapter adapter(CustomerCreditGuiFixture f, CustomerJournalAdapter.Kind kind, CustomerCreditDialogAdapter.Connections connections) {
        return new CustomerJournalAdapter(connections, f.gate, 71, 11, kind, CustomerCreditGuiFixture.CLOCK);
    }
    static int voucherCount(CustomerCreditGuiFixture f) throws Exception { return accounting(f).getVouchers().size(); }
    private static NormalizedAccountingStore accounting(CustomerCreditGuiFixture f) throws Exception {
        var a = new NormalizedAccountingStore(f.anchor, CustomerCreditGuiFixture.CLOCK);
        a.setCurrentCompany(a.getCompanies().stream().filter(c -> c.getId() == 71).findFirst().orElseThrow());
        a.setCurrentYear(a.getYears().stream().filter(y -> y.getId() == 11).findFirst().orElseThrow()); return a;
    }
    private static java.util.Map<String, List<String>> snapshot(CustomerCreditGuiFixture f) throws Exception { return CustomerDocumentDeletionAdapterTest.snapshot(f.anchor, null, -1, List.of()); }
    private interface Call { Object call(Method method, Object[] args) throws Throwable; }
    private static Connection wrap(Connection c, Call call) { return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> call.call(m, a)); }
    private static Object invoke(Object target, Method m, Object[] args) throws Throwable { try { return m.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); } }
}
