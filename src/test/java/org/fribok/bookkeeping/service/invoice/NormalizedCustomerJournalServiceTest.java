package org.fribok.bookkeeping.service.invoice;

import org.fribok.bookkeeping.dataformat.CustomerCreditInvoiceStagingConverter;
import org.fribok.bookkeeping.dataformat.CustomerInvoiceStagingConverter;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.dataformat.SemanticFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedCustomerJournalServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1), TO = LocalDate.of(2026, 10, 31);
    @TempDir Path temporary;

    @Test void invoicePostingIsBalancedScopedAndCallerOwnedWithoutLegacyJdbc() throws Exception { success(false); }
    @Test void creditPostingIsBalancedScopedAndCallerOwnedWithoutLegacyJdbc() throws Exception { success(true); }

    private void success(boolean credit) throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c);
            long first = add(store, credit, 71, document(credit, 1, "100"));
            long second = add(store, credit, 71, document(credit, 2, "45"));
            add(store, credit, 72, document(credit, 1, "999"));
            SSInvoice outside = document(credit, 3, "888"); outside.setLocalDate(TO.plusDays(1));
            add(store, credit, 71, outside);
            c.commit();
            try (LegacyGuard ignored = new LegacyGuard()) {
                var service = new NormalizedCustomerJournalService(c, CLOCK);
                var plan = plan(service, credit);
                assertEquals(0, count(c, "voucher"));
                assertFalse(stored(store, credit, first).isEntered());
                var posted = service.post(plan);
                assertEquals(List.of(first, second), posted.documentIds());
                assertEquals(1, posted.journalNumber()); assertEquals(1, posted.voucherNumber());
                assertEquals(1, counter(c, credit));
                assertTrue(stored(store, credit, first).isEntered());
                assertTrue(stored(store, credit, second).isEntered());
                assertFalse((credit ? store.listCreditInvoices(72) : store.listInvoices(72)).get(0).document().isEntered());
                assertFalse((credit ? store.listCreditInvoices(71) : store.listInvoices(71)).get(2).document().isEntered());
                var accounting = accounting(c);
                SSVoucher ledger = accounting.getVouchers().get(0);
                assertEquals(TO, ledger.getLocalDate());
                assertEquals(0, new BigDecimal("145").compareTo(
                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(ledger, accounting.getAccounts())));
                assertEquals(0, new BigDecimal("145").compareTo(
                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(ledger, accounting.getAccounts())));
                assertEquals(LocalDate.of(2026, 11, 11), stored(store, credit, first).getStoredVoucher().getLocalDate());
                assertThrows(IllegalArgumentException.class, () -> service.post(plan));
                c.rollback();
                assertEquals(0, count(c, "voucher")); assertEquals(0, counter(c, credit));
                assertFalse(stored(store, credit, first).isEntered());
                service.post(plan(service, credit)); c.commit();
            }
        }
    }

    @Test void lateDocumentLedgerAndCounterFailuresRollBackWholeJournalAndAllowRetry() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            for (String failure : new String[]{"document", "ledger", "counter"}) {
                try (Connection c = database()) {
                    initialize(c);
                    var store = new NormalizedInvoiceStore(c);
                    long first = add(store, credit, 71, document(credit, 1, "100"));
                    long second = add(store, credit, 71, document(credit, 2, "45"));
                    c.commit();
                    String family = credit ? "customer_credit_invoice" : "customer_invoice";
                    String table = failure.equals("document") ? family + "_voucher_row"
                            : failure.equals("ledger") ? "voucher_row" : "company_auto_increment";
                    String condition = failure.equals("document")
                            ? (credit ? "credit_invoice_id" : "invoice_id") + "<>" + second
                            : failure.equals("ledger") ? "credit IS NULL" : "counter_value<1";
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE " + table + " ADD CONSTRAINT fail_post CHECK(" + condition + ")");
                    }
                    var outer = c.setSavepoint();
                    // Earlier uncommitted work in the same caller transaction must survive failure.
                    add(store, credit, 72, document(credit, 50, "20"));
                    var before = fingerprint(c, credit);
                    var service = new NormalizedCustomerJournalService(c, CLOCK);
                    var plan = plan(service, credit);
                    assertThrows(SQLException.class, () -> service.post(plan), failure + "/" + credit);
                    assertEquals(before, fingerprint(c, credit));
                    assertFalse(stored(store, credit, first).isEntered());
                    assertFalse(stored(store, credit, second).isEntered());
                    assertEquals(0, count(c, "voucher")); assertEquals(0, counter(c, credit));
                    assertEquals(1, credit ? store.listCreditInvoices(72).size() : store.listInvoices(72).size());
                    c.rollback(outer);
                    assertTrue(credit ? store.listCreditInvoices(72).isEmpty() : store.listInvoices(72).isEmpty());
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE " + table + " DROP CONSTRAINT fail_post");
                    }
                    // Failed posting did not mutate the token or presentation snapshots.
                    assertEquals(1, service.post(plan).journalNumber());
                    c.commit();
                    assertEquals(1, count(c, "voucher")); assertEquals(1, counter(c, credit));
                }
            }
        }
    }

    @Test void staleFinanceAndSelectionRefusedButEarlierCallerEditsRetained() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = add(store, credit, 71, document(credit, 1, "100")); c.commit();
                var service = new NormalizedCustomerJournalService(c, CLOCK);
                var old = plan(service, credit);
                update(store, credit, id, document(credit, 1, "150"));
                var before = fingerprint(c, credit);
                assertThrows(IllegalArgumentException.class, () -> service.post(old));
                assertEquals(before, fingerprint(c, credit)); assertEquals(0, count(c, "voucher"));
                var current = plan(service, credit);
                add(store, credit, 71, document(credit, 2, "10"));
                assertThrows(IllegalArgumentException.class, () -> service.post(current));
                service.post(plan(service, credit));
                assertEquals(1, count(c, "voucher"));
            }
        }
    }

    @Test void staleCounterAndLedgerNumbersRefusedBeforeAnyDocumentChanges() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            for (boolean changeCounter : new boolean[]{false, true}) {
                try (Connection c = database()) {
                    initialize(c);
                    var store = new NormalizedInvoiceStore(c);
                    long id = add(store, credit, 71, document(credit, 1, "100"));
                    var service = new NormalizedCustomerJournalService(c, CLOCK);
                    var plan = plan(service, credit);
                    if (changeCounter) new NormalizedRegisterStore(c).bumpAutoIncrement(71,
                            credit ? "creditinvoicejournal" : "invoicejournal");
                    else new NormalizedAccountingWriter(CLOCK).addVoucher(c, 11, new SSVoucher(1, TO));
                    assertThrows(IllegalArgumentException.class, () -> service.post(plan));
                    assertFalse(stored(store, credit, id).isEntered());
                    var posted = service.post(plan(service, credit));
                    assertEquals(changeCounter ? 2 : 1, posted.journalNumber());
                    assertEquals(changeCounter ? 1 : 2, posted.voucherNumber());
                }
            }
        }
    }

    @Test void mutablePreviewCannotReplacePostedLedgerOrSnapshotsAndMetadataChangesAreRetained() throws Exception {
        for (boolean credit : new boolean[]{false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedInvoiceStore(c);
                long id = add(store, credit, 71, document(credit, 1, "100"));
                var service = new NormalizedCustomerJournalService(c, CLOCK);
                var plan = plan(service, credit);
                SSVoucher preview = credit ? plan.creditPlan().voucher() : plan.invoicePlan().voucher();
                preview.setNumber(999); preview.getRows().clear();
                SSInvoice detached = credit ? plan.creditPlan().invoices().get(0) : plan.invoicePlan().invoices().get(0);
                detached.setEntered(true); detached.getRows().clear();
                SSInvoice fresh = document(credit, 1, "100"); fresh.setNumRemainders(7); fresh.setPrinted(true);
                update(store, credit, id, fresh);
                service.post(plan);
                assertEquals(7, stored(store, credit, id).getNumReminders());
                assertTrue(stored(store, credit, id).isPrinted());
                assertFalse(accounting(c).getVouchers().get(0).getRows().isEmpty());
                assertEquals(1, accounting(c).getVouchers().get(0).getNumber());
            }
        }
    }

    @Test void missingCompanyWrongYearEmptyPeriodInvalidNumbersAndOverflowFailClosed() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var service = new NormalizedCustomerJournalService(c, CLOCK);
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(999, 11, FROM, TO));
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(71, 12, FROM, TO));
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(71, 11, TO, FROM));
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, document(false, null, "100"));
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            store.updateInvoice(71, id, document(false, 1, "100"));
            long duplicate = store.addInvoice(71, document(false, 1, "200"));
            assertThrows(IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            store.deleteInvoice(71, duplicate);
            var plan = plan(service, false);
            c.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.post(plan));
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO company_auto_increment(company_id,counter_name,counter_value)"
                        + " SELECT id,'invoicejournal',2147483647 FROM company WHERE legacy_id=71");
            }
            assertThrows(IllegalArgumentException.class, () -> plan(service, false));
            assertFalse(stored(store, false, id).isEntered());
            assertEquals(0, count(c, "voucher"));
        }
    }

    @Test void postingSurvivesTwoReopensWithoutLegacyTables() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("posting");
        long invoice, credit;
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c);
            invoice = store.addInvoice(71, document(false, 1, "100"));
            credit = store.addCreditInvoice(71, (SSCreditInvoice) document(true, 2, "25"));
            var service = new NormalizedCustomerJournalService(c, CLOCK);
            service.post(plan(service, false)); service.post(plan(service, true)); c.commit();
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
        for (int reopen = 0; reopen < 2; reopen++) {
            try (Connection c = DriverManager.getConnection(url, "SA", "")) {
                c.setAutoCommit(false);
                var store = new NormalizedInvoiceStore(c);
                assertTrue(store.findInvoice(71, invoice).orElseThrow().document().isEntered());
                assertTrue(store.findCreditInvoice(71, credit).orElseThrow().document().isEntered());
                assertEquals(2, count(c, "voucher")); assertEquals(1, counter(c, false)); assertEquals(1, counter(c, true));
                try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
            }
        }
    }

    @Test void dimensionsAreExplicitAndPerDocumentImbalancesCannotCancelAcrossJournal() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO project(company_id,number,concluded) SELECT id,'P',false FROM company");
                s.executeUpdate("INSERT INTO result_unit(company_id,number) SELECT id,'R' FROM company");
            }
            var store = new NormalizedInvoiceStore(c);
            SSInvoice invoice = document(false, 1, "100");
            invoice.getRows().get(0).setProjectNr("P"); invoice.getRows().get(0).setResultUnitNr("R");
            long id = store.addInvoice(71, invoice);
            var service = new NormalizedCustomerJournalService(c, CLOCK);
            try (LegacyGuard ignored = new LegacyGuard()) {
                service.post(plan(service, false));
            }
            assertNull(store.findInvoice(71, id).orElseThrow().document().getStoredType());
            assertTrue(accounting(c).getVouchers().get(0).getRows().stream()
                    .anyMatch(row -> "P".equals(row.getProjectNr()) && "R".equals(row.getResultUnitNr())));
        }
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedInvoiceStore(c);
            SSInvoice positive = document(false, 1, "100"), negative = document(false, 2, "-100");
            positive.getDefaultAccounts().clear(); negative.getDefaultAccounts().clear();
            store.addInvoice(71, positive); store.addInvoice(71, negative);
            var before = fingerprint(c, false);
            assertThrows(IllegalArgumentException.class,
                    () -> plan(new NormalizedCustomerJournalService(c, CLOCK), false));
            assertEquals(before, fingerprint(c, false));
            assertEquals(0, count(c, "voucher"));
        }
    }

    @Test void explicitNewInvoiceDefaultsAvoidLegacySingletonAndDoNotBackfillStoredSnapshots() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        company.setTaxrate1(new BigDecimal("17"));
        try (LegacyGuard ignored = new LegacyGuard()) {
            SSInvoice invoice = CustomerInvoiceFactory.create(company, FROM);
            assertEquals(FROM, invoice.getLocalDate());
            assertEquals(new BigDecimal("17"), invoice.getStoredTaxRates().get(0));
            assertEquals(1510, invoice.getDefaultAccounts().get(SSDefaultAccount.CustomerClaim));
            assertNull(invoice.getStoredVoucher());
            assertNull(invoice.getNumber()); assertFalse(invoice.isEntered());
            invoice.getDefaultAccounts().clear();
            assertEquals(1510, company.getDefaultAccounts().get(SSDefaultAccount.CustomerClaim));
            SSInvoice stored = SSInvoice.forPersistence();
            assertTrue(stored.getDefaultAccounts().isEmpty());
            assertEquals(java.util.Arrays.asList(null, null, null), stored.getStoredTaxRates());
        }
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:posting_" + System.nanoTime(), "SA", "");
    }
    private static void initialize(Connection c) throws Exception {
        c.setAutoCommit(false);
        new SchemaMigrationRunner(CLOCK).migrate(c, NormalizedSchemaMigrations.load());
        var writer = new NormalizedAccountingWriter(CLOCK);
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany(); company.setId(id); company.setName("Company" + id);
            company.setRoundingOff(false); writer.addCompany(c, company);
            SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(id == 71 ? 11 : 12);
            year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
            SSAccountPlan accounts = new SSAccountPlan();
            accounts.setAccounts(new java.util.ArrayList<>(List.of(new SSAccount(1510), new SSAccount(3010))));
            year.setAccountPlan(accounts); writer.addAccountingYear(c, id, year);
        }
        c.commit();
    }
    private static SSInvoice document(boolean credit, Integer number, String amount) {
        SSInvoice invoice = credit ? SSCreditInvoice.forPersistence() : SSInvoice.forPersistence();
        invoice.setNumber(number); invoice.setLocalDate(FROM); invoice.setTaxFree(true);
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        SSSaleRow row = new SSSaleRow(); row.setQuantity(BigDecimal.ONE); row.setUnitprice(new BigDecimal(amount));
        row.setAccountNr(3010); row.setDiscount(BigDecimal.ZERO); invoice.getRows().add(row);
        return invoice;
    }
    private static long add(NormalizedInvoiceStore s, boolean credit, int company, SSInvoice invoice) throws Exception {
        return credit ? s.addCreditInvoice(company, (SSCreditInvoice) invoice) : s.addInvoice(company, invoice);
    }
    private static void update(NormalizedInvoiceStore s, boolean credit, long id, SSInvoice invoice) throws Exception {
        if (credit) s.updateCreditInvoice(71, id, (SSCreditInvoice) invoice); else s.updateInvoice(71, id, invoice);
    }
    private static SSInvoice stored(NormalizedInvoiceStore s, boolean credit, long id) throws Exception {
        return credit ? s.findCreditInvoice(71, id).orElseThrow().document() : s.findInvoice(71, id).orElseThrow().document();
    }
    private static NormalizedCustomerJournalService.Plan plan(NormalizedCustomerJournalService s, boolean credit)
            throws SQLException { return credit ? s.planCredits(71, 11, FROM, TO) : s.planInvoices(71, 11, FROM, TO); }
    private static int counter(Connection c, boolean credit) throws SQLException {
        return new NormalizedRegisterStore(c).autoIncrementValue(71, credit ? "creditinvoicejournal" : "invoicejournal");
    }
    private static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next(); return r.getInt(1);
        }
    }
    private static SemanticFingerprint fingerprint(Connection c, boolean credit) throws SQLException {
        return credit ? new CustomerCreditInvoiceStagingConverter().fingerprintNormalized(c)
                : new CustomerInvoiceStagingConverter().fingerprintNormalized(c);
    }
    private static NormalizedAccountingStore accounting(Connection c) throws Exception {
        var store = new NormalizedAccountingStore(c, CLOCK);
        var company = store.getCompanies().stream().filter(v -> v.getId() == 71).findFirst().orElseThrow();
        store.setCurrentCompany(company); store.setCurrentYear(store.getYears().get(0)); return store;
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
