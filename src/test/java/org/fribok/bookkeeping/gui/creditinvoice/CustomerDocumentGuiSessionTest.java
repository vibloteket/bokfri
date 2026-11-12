package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.CustomerInvoiceStagingConverter;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomerDocumentGuiSessionTest {
    @Test void sourcesUseExplicitCompanyYearAndStableIdsWithoutWritesOrImplicitDefaults() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            addOtherSelection(f);
            var store = new NormalizedInvoiceStore(f.anchor);
            store.addInvoice(71, source(2, false));
            SSInvoice cash = source(3, true); cash.setType(SSInvoiceType.CASH); store.addInvoice(71, cash);
            store.addInvoice(71, source(4, true)); store.addInvoice(71, source(4, false));
            store.addInvoice(71, source(null, true)); f.anchor.commit();
            AtomicInteger opened = new AtomicInteger(), commits = new AtomicInteger(), closed = new AtomicInteger();
            var session = session(f, () -> {
                opened.incrementAndGet(); return proxy(f.open(), (connection, method) -> {
                    if (method.equals("commit")) commits.incrementAndGet();
                    if (method.equals("close")) closed.incrementAndGet();
                });
            });
            assertEquals(0, opened.get());
            var before = new CustomerInvoiceStagingConverter().fingerprintNormalized(f.anchor);
            var sources = session.sources();
            assertEquals(71, sources.companyId()); assertEquals(11, sources.yearId());
            assertEquals("GUI company", sources.companyName()); assertEquals(1, sources.invoices().size());
            assertEquals(f.invoice, sources.invoices().get(0).id());
            assertEquals(1, sources.invoices().get(0).number());
            assertEquals(before, new CustomerInvoiceStagingConverter().fingerprintNormalized(f.anchor));
            assertEquals(0, commits.get()); assertEquals(1, opened.get()); assertEquals(1, closed.get());
        }
    }

    @Test void wrongSelectionAndLegacyOrIncompleteFormatNeverFallBackOrMigrate() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            assertThrows(SQLException.class, () -> new CustomerDocumentGuiSession(
                    f::open, f.gate, 71, 999, CustomerCreditGuiFixture.CLOCK).sources());
            assertThrows(SQLException.class, () -> new CustomerDocumentGuiSession(
                    f::open, f.gate, 999, 11, CustomerCreditGuiFixture.CLOCK).sources());
            DataFormatManager.recordVersion(f.anchor, 2);
            assertThrows(SQLException.class, () -> session(f, f::open).sources());
            assertEquals(2, DataFormatManager.detect(f.anchor));
            DataFormatManager.recordVersion(f.anchor, 3);
            try (var s = f.anchor.createStatement()) { s.executeUpdate("DELETE FROM bokfri_schema_history"); }
            f.anchor.commit();
            assertThrows(SQLException.class, () -> session(f, f::open).sources());
            try (var s = f.anchor.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM bokfri_schema_history")) {
                r.next(); assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test void closingSelectionInvalidatesAnExistingPreviewWithoutRetargetingIt() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var session = session(f, f::open); var adapter = session.credit(f.invoice);
            var preview = adapter.preview(null, new BigDecimal("25"));
            session.close();
            assertThrows(SQLException.class, () -> adapter.save(preview));
            assertThrows(SQLException.class, session::sources);
            assertThrows(SQLException.class, () -> session.credit(f.invoice));
            assertEquals(0, f.count());
            var replacement = session(f, f::open);
            var fresh = replacement.credit(f.invoice); fresh.save(fresh.preview(null, BigDecimal.ONE));
        }
    }

    @Test void closingPendingSourceLoadDoesNotWaitOnGateAndClosesItsLease() throws Exception {
        try (var f = new CustomerCreditGuiFixture(); var executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            var session = session(f, () -> {
                Connection connection = proxy(f.open(), (c, name) -> { if (name.equals("close")) closed.incrementAndGet(); });
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test wait timed out"); }
                catch (InterruptedException interrupted) { throw new SQLException(interrupted); }
                return connection;
            });
            var pending = executor.submit(() -> assertThrows(SQLException.class, session::sources));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try { session.close(); assertFalse(session.isActive()); }
            finally { release.countDown(); }
            pending.get(10, TimeUnit.SECONDS); assertEquals(1, closed.get()); assertEquals(0, f.count());
        }
    }

    @Test void sourcePresentationCannotAuthorizeAForeignCompanyInvoice() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long other = addOtherSelection(f);
            var session = session(f, f::open);
            var forged = new CustomerDocumentGuiSession.Source(other, 1, 1, "forged", LocalDate.now());
            assertThrows(IllegalArgumentException.class, () -> session.credit(forged.id()).preview(null, BigDecimal.ONE));
            assertEquals(0, f.count());
        }
    }

    @Test void closingSessionDoesNotShutDownTheDatabaseOrAcquireAnotherConnection() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicInteger opened = new AtomicInteger();
            var session = session(f, () -> { opened.incrementAndGet(); return f.open(); });
            session.close(); session.close(); assertEquals(0, opened.get());
            try (Connection c = f.open()) {
                assertTrue(new NormalizedInvoiceStore(c).findInvoice(71, f.invoice).isPresent());
            }
        }
    }

    @Test void alreadyAcceptedWriteCompletesOnlyInOriginalContextDuringReplacement() throws Exception {
        try (var f = new CustomerCreditGuiFixture(); var executor = Executors.newSingleThreadExecutor()) {
            long other = addOtherSelection(f);
            CountDownLatch enteredCommit = new CountDownLatch(1), release = new CountDownLatch(1);
            var original = session(f, () -> proxy(f.open(), (connection, name) -> {
                if (name.equals("commit")) {
                    enteredCommit.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test wait timed out"); }
                    catch (InterruptedException interrupted) { throw new SQLException(interrupted); }
                }
            }));
            var adapter = original.credit(f.invoice); var preview = adapter.preview(null, new BigDecimal("25"));
            var write = executor.submit(() -> adapter.save(preview));
            assertTrue(enteredCommit.await(10, TimeUnit.SECONDS));
            try { original.close(); }
            finally { release.countDown(); }
            assertEquals(1, write.get(10, TimeUnit.SECONDS).number());
            assertEquals(1, f.count());
            assertTrue(new NormalizedInvoiceStore(f.anchor).listCreditInvoices(72).isEmpty());
            var replacement = new CustomerDocumentGuiSession(f::open, f.gate, 72, 12, CustomerCreditGuiFixture.CLOCK);
            assertEquals(other, replacement.sources().invoices().get(0).id());
        }
    }

    static long addOtherSelection(CustomerCreditGuiFixture f) throws Exception {
        var writer = new NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK);
        SSNewCompany company = new SSNewCompany(); company.setId(72); company.setName("Other company");
        company.setRoundingOff(true); writer.addCompany(f.anchor, company);
        SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(12);
        year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
        SSAccountPlan plan = new SSAccountPlan();
        plan.setAccounts(new ArrayList<>(List.of(new SSAccount(1510), new SSAccount(3010))));
        year.setAccountPlan(plan); writer.addAccountingYear(f.anchor, 72, year);
        long id = new NormalizedInvoiceStore(f.anchor).addInvoice(72, source(1, true)); f.anchor.commit(); return id;
    }
    static SSInvoice source(Integer number, boolean entered) {
        SSInvoice invoice = SSInvoice.forPersistence(); invoice.setNumber(number); invoice.setEntered(entered);
        invoice.setLocalDate(LocalDate.of(2026, 10, 1)); invoice.setCurrencyRate(BigDecimal.ONE); invoice.setTaxFree(true);
        invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        SSSaleRow row = new SSSaleRow(); row.setAccountNr(3010); row.setUnitprice(new BigDecimal("100"));
        row.setQuantity(BigDecimal.ONE); invoice.getRows().add(row); return invoice;
    }
    private static CustomerDocumentGuiSession session(CustomerCreditGuiFixture f, CustomerCreditDialogAdapter.Connections c) {
        return new CustomerDocumentGuiSession(c, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
    }
    @FunctionalInterface private interface Before { void call(Connection connection, String name) throws SQLException; }
    private static Connection proxy(Connection c, Before before) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    before.call(c, method.getName());
                    try { return method.invoke(c, args); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
    }
}
