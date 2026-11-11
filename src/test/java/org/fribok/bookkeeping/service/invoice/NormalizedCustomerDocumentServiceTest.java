package org.fribok.bookkeeping.service.invoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.dataformat.NormalizedSaleDocumentStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedCustomerDocumentServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    @TempDir Path temporary;

    @Test void bookedInvoiceAndCreditMetadataNeverRewriteFinancialDataOrOtherCompanies() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = add(store, credit, 71, invoice(credit, 1, true));
                add(store, credit, 72, invoice(credit, 1, true));
                var before = snapshot(c, true, null, false);
                Metadata expected = metadata(store, credit, 71, id);
                Metadata next = new Metadata(true, "12345", 4, true);
                try (LegacyGuard ignored = new LegacyGuard()) {
                    update(new NormalizedCustomerDocumentService(c), credit, 71, id, expected, next);
                }
                assertEquals(next, metadata(store, credit, 71, id));
                assertEquals(before, snapshot(c, true, null, false));
                SSInvoice saved = credit ? store.findCreditInvoice(71, id).orElseThrow().document()
                        : store.findInvoice(71, id).orElseThrow().document();
                assertTrue(saved.isEntered()); assertEquals(1, saved.getStoredVoucher().getNumber());
                assertNull(saved.getStoredType());
                assertEquals(new Metadata(false, null, 0, false), credit
                        ? store.findCreditInvoiceMetadata(72, store.listCreditInvoices(72).get(0).id()).orElseThrow()
                        : store.findInvoiceMetadata(72, store.listInvoices(72).get(0).id()).orElseThrow());
            }
        }
    }

    @Test void staleMetadataAndNullVersusEmptyOcrAreComparedWithoutLostUpdates() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = add(store, credit, 71, invoice(credit, 1, true));
                var service = new NormalizedCustomerDocumentService(c);
                Metadata old = metadata(store, credit, 71, id), empty = new Metadata(false, "", 0, false);
                update(service, credit, 71, id, old, empty);
                var before = snapshot(c, false, null, false);
                assertThrows(SQLException.class, () -> update(service, credit, 71, id, old, old));
                assertEquals(before, snapshot(c, false, null, false));
                update(service, credit, 71, id, empty, old);
                assertEquals(old, metadata(store, credit, 71, id));
                update(service, credit, 71, id, old, old); // matching no-op still succeeds
                assertThrows(IllegalArgumentException.class,
                        () -> update(service, credit, 71, id, old, new Metadata(false, null, -1, false)));
            }
        }
    }

    @Test void reminderRecordsExactlyOnceAndGuardsOverflowWithoutChangingBooking() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, invoice(false, 1, true));
            var service = new NormalizedCustomerDocumentService(c);
            Metadata old = store.findInvoiceMetadata(71, id).orElseThrow();
            Metadata next = service.recordInvoiceReminder(71, id, old);
            assertEquals(1, next.reminderCount());
            assertThrows(SQLException.class, () -> service.recordInvoiceReminder(71, id, old));
            Metadata maximum = new Metadata(true, "ocr", Integer.MAX_VALUE, false);
            service.updateInvoiceMetadata(71, id, next, maximum);
            var before = snapshot(c, false, null, false);
            assertThrows(IllegalArgumentException.class, () -> service.recordInvoiceReminder(71, id, maximum));
            assertEquals(before, snapshot(c, false, null, false));
        }
    }

    @Test void wrongScopeMissingIdentityAndAutocommitWritesAreRefused() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c);
            var service = new NormalizedCustomerDocumentService(c);
            for (boolean credit : new boolean[]{false, true}) {
                long id = add(store, credit, 71, invoice(credit, 1, true));
                Metadata old = metadata(store, credit, 71, id), next = new Metadata(true, null, 0, false);
                var before = snapshot(c, false, null, false);
                assertThrows(SQLException.class, () -> update(service, credit, 72, id, old, next));
                assertThrows(SQLException.class, () -> update(service, credit, 71, Long.MAX_VALUE, old, next));
                c.setAutoCommit(true);
                assertThrows(SQLException.class, () -> update(service, credit, 71, id, old, next));
                assertThrows(SQLException.class, () -> service.deleteInvoiceWithOrderDetachment(71, id));
                assertThrows(SQLException.class, () -> new NormalizedSaleDocumentStore(c).detachOrdersFromInvoice(71, 1));
                c.setAutoCommit(false);
                assertEquals(before, snapshot(c, false, null, false));
            }
        }
    }

    @Test void metadataLateFailurePreservesPriorCallerWorkAndSavepointsForRetry() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = add(store, credit, 71, invoice(credit, 1, true)); c.commit();
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE " + (credit ? "customer_credit_invoice" : "customer_invoice")
                            + " ADD CONSTRAINT fail_metadata CHECK(reminder_count<5)");
                }
                var outer = c.setSavepoint();
                add(store, credit, 72, invoice(credit, 2, false));
                var before = snapshot(c, false, null, false);
                Metadata old = metadata(store, credit, 71, id);
                var service = new NormalizedCustomerDocumentService(c);
                assertThrows(SQLException.class,
                        () -> update(service, credit, 71, id, old, new Metadata(true, "new", 6, true)));
                assertEquals(before, snapshot(c, false, null, false));
                c.rollback(outer);
                assertTrue(credit ? store.listCreditInvoices(72).isEmpty() : store.listInvoices(72).isEmpty());
                update(service, credit, 71, id, old, new Metadata(true, "new", 2, true));
                c.rollback();
                assertEquals(old, metadata(store, credit, 71, id));
            }
        }
    }

    @Test void invoiceDeletionDetachesOnlyScopedOrderLinksAndIsCallerRollbackable() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var invoices = new NormalizedInvoiceStore(c); var orders = new NormalizedSaleDocumentStore(c);
            long id = invoices.addInvoice(71, invoice(false, 1, false));
            invoices.addInvoice(72, invoice(false, 1, false));
            long first = orders.addOrder(71, order(10, 1)), second = orders.addOrder(71, order(null, 1));
            long unlinked = orders.addOrder(71, order(12, null)), other = orders.addOrder(72, order(10, 1));
            c.commit();
            var before = snapshot(c, false, id, true);
            try (LegacyGuard ignored = new LegacyGuard()) {
                var result = new NormalizedCustomerDocumentService(c).deleteInvoiceWithOrderDetachment(71, id);
                assertEquals(List.of(first, second), result.detachedOrderIds());
                assertEquals(id, result.id());
                assertTrue(invoices.findInvoice(71, id).isEmpty());
                assertNull(orders.findOrder(71, first).orElseThrow().document().getInvoiceNr());
                assertNull(orders.findOrder(71, unlinked).orElseThrow().document().getInvoiceNr());
                assertEquals(1, orders.findOrder(72, other).orElseThrow().document().getInvoiceNr());
                assertEquals(before, snapshot(c, false, id, true));
                c.rollback();
                assertTrue(invoices.findInvoice(71, id).isPresent());
                assertEquals(1, orders.findOrder(71, first).orElseThrow().document().getInvoiceNr());
            }
        }
    }

    @Test void bookedPaymentAndCreditGuardsRestoreOrderLinksWithoutClearingFinancialReferences() throws Exception {
        for (String guard : List.of("booked", "payment", "credit")) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = store.addInvoice(71, invoice(false, 1, guard.equals("booked")));
                new NormalizedSaleDocumentStore(c).addOrder(71, order(10, 1));
                if (guard.equals("credit")) {
                    SSCreditInvoice credit = (SSCreditInvoice) invoice(true, 20, false); credit.setCreditingNr(1);
                    store.addCreditInvoice(71, credit);
                } else if (guard.equals("payment")) {
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("INSERT INTO inpayment(legacy_id,company_id,number,entered)"
                                + " SELECT 99,id,99,false FROM company WHERE legacy_id=71");
                        s.executeUpdate("INSERT INTO inpayment_row(inpayment_id,row_number,invoice_number,value)"
                                + " SELECT id,0,1,0 FROM inpayment WHERE legacy_id=99");
                    }
                }
                var before = snapshot(c, false, null, false);
                assertThrows(SQLException.class,
                        () -> new NormalizedCustomerDocumentService(c).deleteInvoiceWithOrderDetachment(71, id));
                assertEquals(before, snapshot(c, false, null, false));
            }
        }
    }

    @Test void lateDetachmentAndInvoiceDeleteFailuresRestoreEntireWorkflowAndAllowRetry() throws Exception {
        for (boolean failOrder : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = store.addInvoice(71, invoice(false, 1, false));
                long order = new NormalizedSaleDocumentStore(c).addOrder(71, order(10, 1)); c.commit();
                try (Statement s = c.createStatement()) {
                    if (failOrder) s.execute("ALTER TABLE customer_order ADD CONSTRAINT fail_detach CHECK(id<>"
                            + order + " OR invoice_number IS NOT NULL)");
                    else {
                        s.execute("CREATE TABLE hold_invoice (invoice_id BIGINT REFERENCES customer_invoice(id))");
                        s.executeUpdate("INSERT INTO hold_invoice VALUES(" + id + ")"); c.commit();
                    }
                }
                var outer = c.setSavepoint();
                store.addInvoice(72, invoice(false, 2, false));
                var before = snapshot(c, false, null, false);
                var service = new NormalizedCustomerDocumentService(c);
                assertThrows(SQLException.class, () -> service.deleteInvoiceWithOrderDetachment(71, id));
                assertEquals(before, snapshot(c, false, null, false));
                c.rollback(outer);
                assertTrue(store.listInvoices(72).isEmpty());
                try (Statement s = c.createStatement()) {
                    if (failOrder) s.execute("ALTER TABLE customer_order DROP CONSTRAINT fail_detach");
                    else s.executeUpdate("DELETE FROM hold_invoice");
                }
                service.deleteInvoiceWithOrderDetachment(71, id);
                assertTrue(store.findInvoice(71, id).isEmpty());
                c.rollback();
                assertTrue(store.findInvoice(71, id).isPresent());
            }
        }
    }

    @Test void historicalNullAndDuplicateNumbersNeverGuessOrderOwnership() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c); var orders = new NormalizedSaleDocumentStore(c);
            var service = new NormalizedCustomerDocumentService(c);
            long absent = store.addInvoice(71, invoice(false, null, false));
            long unlinked = orders.addOrder(71, order(10, null));
            assertTrue(service.deleteInvoiceWithOrderDetachment(71, absent).detachedOrderIds().isEmpty());
            assertNull(orders.findOrder(71, unlinked).orElseThrow().document().getInvoiceNr());
            long first = store.addInvoice(71, invoice(false, 1, false));
            long second = store.addInvoice(71, invoice(false, 1, false));
            orders.addOrder(71, order(10, 1));
            var before = snapshot(c, false, null, false);
            assertThrows(SQLException.class, () -> service.deleteInvoiceWithOrderDetachment(71, first));
            assertThrows(SQLException.class, () -> service.deleteInvoiceWithOrderDetachment(72, first));
            assertEquals(before, snapshot(c, false, null, false));
            orders.detachOrdersFromInvoice(71, 1);
            service.deleteInvoiceWithOrderDetachment(71, first);
            assertTrue(store.findInvoice(71, second).isPresent());
        }
    }

    @Test void metadataChangesInteroperateWithPostingAndSurviveTwoReopensAlongsideDeletion() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("documents");
        long invoice, credit, removed, order;
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c); var service = new NormalizedCustomerDocumentService(c);
            invoice = store.addInvoice(71, invoice(false, 1, false));
            credit = store.addCreditInvoice(71, (SSCreditInvoice) invoice(true, 10, true));
            var journals = new NormalizedCustomerJournalService(c, CLOCK);
            var plan = journals.planInvoices(71, 11, DATE, DATE);
            service.recordInvoiceReminder(71, invoice, store.findInvoiceMetadata(71, invoice).orElseThrow());
            journals.post(plan);
            service.updateCreditMetadata(71, credit, store.findCreditInvoiceMetadata(71, credit).orElseThrow(),
                    new Metadata(true, "OCR", 2, true));
            removed = store.addInvoice(71, invoice(false, 2, false));
            order = new NormalizedSaleDocumentStore(c).addOrder(71, order(10, 2));
            service.deleteInvoiceWithOrderDetachment(71, removed); c.commit();
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(url, "SA", "")) {
                var store = new NormalizedInvoiceStore(c);
                assertTrue(store.findInvoice(71, invoice).orElseThrow().document().isEntered());
                assertEquals(1, store.findInvoiceMetadata(71, invoice).orElseThrow().reminderCount());
                assertEquals(new Metadata(true, "OCR", 2, true), store.findCreditInvoiceMetadata(71, credit).orElseThrow());
                assertTrue(store.findInvoice(71, removed).isEmpty());
                assertNull(new NormalizedSaleDocumentStore(c).findOrder(71, order).orElseThrow().document().getInvoiceNr());
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM voucher")) {
                    r.next(); assertEquals(2, r.getInt(1));
                }
                try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
            }
        }
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:customer_doc_" + System.nanoTime(), "SA", "");
    }
    private static void initialize(Connection c) throws Exception {
        c.setAutoCommit(false);
        new SchemaMigrationRunner(CLOCK).migrate(c, NormalizedSchemaMigrations.load());
        var writer = new NormalizedAccountingWriter(CLOCK);
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany(); company.setId(id); company.setName("Company" + id);
            writer.addCompany(c, company);
            SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(id == 71 ? 11 : 12);
            year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
            SSAccountPlan accounts = new SSAccountPlan();
            accounts.setAccounts(new ArrayList<>(List.of(new SSAccount(1510), new SSAccount(3010))));
            year.setAccountPlan(accounts); writer.addAccountingYear(c, id, year);
        }
        writer.addVoucher(c, 11, voucher()); c.commit();
    }
    private static SSInvoice invoice(boolean credit, Integer number, boolean booked) {
        SSInvoice invoice = credit ? SSCreditInvoice.forPersistence() : SSInvoice.forPersistence();
        invoice.setNumber(number); invoice.setLocalDate(DATE); invoice.setTaxFree(true);
        invoice.setCurrencyRate(BigDecimal.ONE); invoice.setEntered(booked);
        invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        invoice.getDefaultAccounts().put(SSDefaultAccount.Sales, null);
        invoice.getRows().add(row()); invoice.setVoucher(voucher()); return invoice;
    }
    private static SSOrder order(Integer number, Integer invoice) {
        SSOrder order = SSOrder.forPersistence(); order.setNumber(number); order.setInvoiceNr(invoice);
        order.setCustomerName("Snapshot"); order.setPurchaseOrderNr(77); order.getRows().add(row()); return order;
    }
    private static SSSaleRow row() {
        SSSaleRow row = new SSSaleRow(); row.setDescription("Line"); row.setQuantity(BigDecimal.ONE);
        row.setUnitprice(new BigDecimal("100")); row.setAccountNr(3010); return row;
    }
    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher(1, DATE); voucher.setDescription("Existing ledger/snapshot");
        SSVoucherRow debit = new SSVoucherRow(); debit.setAccountNr(1510); debit.setDebet(new BigDecimal("100"));
        SSVoucherRow credit = new SSVoucherRow(); credit.setAccountNr(3010); credit.setCredit(new BigDecimal("100"));
        voucher.getRows().add(debit); voucher.getRows().add(credit); return voucher;
    }
    private static long add(NormalizedInvoiceStore s, boolean credit, int company, SSInvoice invoice) throws SQLException {
        return credit ? s.addCreditInvoice(company, (SSCreditInvoice) invoice) : s.addInvoice(company, invoice);
    }
    private static Metadata metadata(NormalizedInvoiceStore s, boolean credit, int company, long id) throws SQLException {
        return (credit ? s.findCreditInvoiceMetadata(company, id) : s.findInvoiceMetadata(company, id)).orElseThrow();
    }
    private static void update(NormalizedCustomerDocumentService s, boolean credit, int company, long id,
            Metadata expected, Metadata replacement) throws SQLException {
        if (credit) s.updateCreditMetadata(company, id, expected, replacement);
        else s.updateInvoiceMetadata(company, id, expected, replacement);
    }

    /** Snapshots every PUBLIC table, excluding only explicitly allowed metadata/link/deleted graph changes. */
    private static Map<String, List<String>> snapshot(Connection c, boolean ignoreMetadata, Long deletedInvoice,
            boolean ignoreOrderLinks) throws SQLException {
        Map<String, List<String>> snapshot = new TreeMap<>(); List<String> tables = new ArrayList<>();
        try (ResultSet r = c.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (r.next()) tables.add(r.getString("TABLE_NAME"));
        }
        Set<String> metadata = Set.of("PRINTED", "OCR_NUMBER", "REMINDER_COUNT", "INTEREST_INVOICED");
        for (String table : tables) {
            String filter = "";
            if (deletedInvoice != null && table.equals("CUSTOMER_INVOICE")) filter = " WHERE id<>" + deletedInvoice;
            else if (deletedInvoice != null && table.startsWith("CUSTOMER_INVOICE_"))
                filter = " WHERE invoice_id<>" + deletedInvoice;
            List<String> rows = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM \"" + table + "\"" + filter)) {
                while (r.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        String column = r.getMetaData().getColumnName(i);
                        if (ignoreMetadata && (table.equals("CUSTOMER_INVOICE") || table.equals("CUSTOMER_CREDIT_INVOICE"))
                                && metadata.contains(column)) continue;
                        if (ignoreOrderLinks && table.equals("CUSTOMER_ORDER") && column.equals("INVOICE_NUMBER")) continue;
                        Object value = r.getObject(i);
                        row.append(column).append(':');
                        if (value == null) row.append("NULL;");
                        else { String text = value.toString(); row.append(value.getClass().getName()).append(':')
                                .append(text.length()).append(':').append(text).append(';'); }
                    }
                    rows.add(row.toString());
                }
            }
            rows.sort(String::compareTo); snapshot.put(table, rows);
        }
        assertFalse(snapshot.isEmpty()); return snapshot;
    }
    private static final class LegacyGuard implements AutoCloseable {
        private final Map<Field, Object> saved = new HashMap<>();
        LegacyGuard() throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field f = SSDB.class.getDeclaredField(name); f.setAccessible(true);
                saved.put(f, f.get(SSDB.getInstance())); f.set(SSDB.getInstance(), null);
            }
            Field f = SSDB.class.getDeclaredField("iConnection"); f.setAccessible(true);
            f.set(SSDB.getInstance(), Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }
        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(SSDB.getInstance(), entry.getValue());
        }
    }
}
