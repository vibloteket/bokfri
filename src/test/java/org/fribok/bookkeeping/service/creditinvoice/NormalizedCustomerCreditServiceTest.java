package org.fribok.bookkeeping.service.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerJournalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedCustomerCreditServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T23:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 12);
    @TempDir Path temporary;

    @Test void fullCreditPreservesOriginalLedgerAndAllOtherSqlAndUsesExplicitClock() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long original = store.addInvoice(71, original(1, true));
            store.addInvoice(72, original(1, true));
            var before = snapshot(c, null);
            try (LegacyGuard ignored = new LegacyGuard()) {
                var service = new NormalizedCustomerCreditService(c, CLOCK);
                var plan = service.plan(71, 11, original, null, null);
                assertEquals(DATE, plan.credit().getLocalDate());
                assertEquals(0, plan.remainingBalance().compareTo(new BigDecimal("100")));
                assertEquals(before, snapshot(c, null));
                var created = service.create(plan);
                assertEquals(before, snapshot(c, created.id()));
                assertFalse(created.credit().isEntered()); assertFalse(created.credit().isPrinted());
                assertNull(created.credit().getStoredVoucher()); assertEquals(1, created.credit().getCreditingNr());
                assertEquals(1, created.credit().getNumber());
                assertNull(store.findInvoice(71, original).orElseThrow().document().getStoredType());
                assertEquals(1, store.findInvoice(71, original).orElseThrow().document().getStoredVoucher().getNumber());
                assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, original, DATE, null));
                c.rollback();
            }
        }
    }

    @Test void omittedAmountCreditsRemainingBalanceAfterPaymentsAndAllExistingCredits() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long original = store.addInvoice(71, original(1, true));
            payment(c, 71, 1, "25");
            payment(c, 72, 1, "999"); // unrelated company cannot reduce available balance
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var first = service.create(service.plan(71, 11, original, DATE, new BigDecimal("20")));
            assertMoney("20", total(first.credit()));
            var remaining = service.plan(71, 11, original, DATE, null);
            assertMoney("55", remaining.remainingBalance());
            var second = service.create(remaining);
            assertMoney("55", total(second.credit()));
            assertEquals(2, second.credit().getNumber());
            assertFalse(first.credit().isEntered()); // even unbooked credits reserve the balance
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, original, DATE, BigDecimal.ONE));
        }
    }

    @Test void stalePaymentReservationNumberAndSourceChangesNeverOverwriteEarlierCallerWork() throws Exception {
        for (String change : List.of("payment", "credit", "source", "number")) {
            try (Connection c = database()) {
                initialize(c, true);
                var store = new NormalizedInvoiceStore(c);
                long original = store.addInvoice(71, original(1, true));
                long another = store.addInvoice(71, original(2, true)); c.commit();
                var service = new NormalizedCustomerCreditService(c, CLOCK);
                var plan = service.plan(71, 11, original, DATE, new BigDecimal("50"));
                var outer = c.setSavepoint();
                if (change.equals("payment")) payment(c, 71, 1, "10");
                else if (change.equals("source")) {
                    var expected = store.findInvoiceMetadata(71, original).orElseThrow();
                    store.updateInvoiceMetadata(71, original, expected,
                            new NormalizedInvoiceStore.Metadata(true, "changed", 1, false));
                } else service.create(service.plan(71, 11, change.equals("credit") ? original : another,
                        DATE, new BigDecimal("10")));
                var before = snapshot(c, null);
                assertThrows(IllegalArgumentException.class, () -> service.create(plan), change);
                assertEquals(before, snapshot(c, null));
                c.rollback(outer);
                assertMoney("50", total(service.create(plan).credit()));
            }
        }
    }

    @Test void previewMutationCannotChangeCreatedCreditOrAliasOriginalRowsAndSnapshots() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, original(1, true));
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var plan = service.plan(71, 11, id, DATE, new BigDecimal("25"));
            plan.credit().setCreditingNr(999); plan.credit().setNumber(999); plan.credit().setEntered(true);
            plan.credit().getRows().clear(); plan.credit().getDefaultAccounts().clear();
            var before = snapshot(c, null);
            var created = service.create(plan);
            assertMoney("25", total(created.credit()));
            assertEquals(1, created.credit().getCreditingNr()); assertEquals(1, created.credit().getNumber());
            assertFalse(created.credit().isEntered()); assertEquals(before, snapshot(c, created.id()));
        }
    }

    @Test void lateCreditRowFailureRollsBackNumberAndWholeGraphAndLeavesCallerSavepointUsable() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            SSInvoice source = original(1, true);
            SSSaleRow second = new SSSaleRow(); second.setDescription("second"); source.getRows().add(second);
            long id = store.addInvoice(71, source); c.commit();
            try (Statement s = c.createStatement()) {
                s.execute("ALTER TABLE customer_credit_invoice_row ADD CONSTRAINT fail_credit CHECK(row_number<>1)");
            }
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var plan = service.plan(71, 11, id, DATE, new BigDecimal("25"));
            var outer = c.setSavepoint(); store.addInvoice(72, original(2, true));
            var before = snapshot(c, null);
            assertThrows(SQLException.class, () -> service.create(plan));
            assertEquals(before, snapshot(c, null)); assertTrue(store.listCreditInvoices(71).isEmpty());
            c.rollback(outer); assertTrue(store.listInvoices(72).isEmpty());
            try (Statement s = c.createStatement()) {
                s.execute("ALTER TABLE customer_credit_invoice_row DROP CONSTRAINT fail_credit");
            }
            var created = service.create(plan);
            assertEquals(1, created.credit().getNumber()); assertEquals(2, created.credit().getRows().size());
            c.rollback(); assertTrue(store.listCreditInvoices(71).isEmpty());
            assertEquals(1, service.create(plan).credit().getNumber());
        }
    }

    @Test void invalidSourcesAndAmountsAreRejectedWithoutWritingOrGuessingIdentity() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, original(1, true));
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var before = snapshot(c, null);
            for (BigDecimal amount : List.of(BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("101"),
                    new BigDecimal("0.001"))) {
                assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, id, DATE, amount));
            }
            assertThrows(IllegalArgumentException.class, () -> service.plan(72, 12, id, DATE, null));
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 12, id, DATE, null));
            assertEquals(before, snapshot(c, null));
            long unbooked = store.addInvoice(71, original(2, false));
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, unbooked, DATE, null));
            SSInvoice cash = original(3, true); cash.setType(SSInvoiceType.CASH);
            long cashId = store.addInvoice(71, cash);
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, cashId, DATE, null));
            store.addInvoice(71, original(1, true));
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, id, DATE, null));
            long missing = store.addInvoice(71, original(null, true));
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, missing, DATE, null));
        }
    }

    @Test void numberingHonorsConfiguredFloorWithoutBumpingItAndOverflowFailsClosed() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, original(1, true));
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO company_auto_increment(company_id,counter_name,counter_value)"
                        + " SELECT id,'creditinvoice',99 FROM company WHERE legacy_id=71");
            }
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            assertEquals(100, service.create(service.plan(71, 11, id, DATE, new BigDecimal("20"))).credit().getNumber());
            assertEquals(99, new NormalizedRegisterStore(c).autoIncrementValue(71, "creditinvoice"));
            assertEquals(101, service.plan(71, 11, id, DATE, new BigDecimal("20")).credit().getNumber());
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE company_auto_increment SET counter_value=2147483647 WHERE counter_name='creditinvoice'");
            }
            var before = snapshot(c, null);
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, id, DATE, null));
            assertEquals(before, snapshot(c, null));
        }
    }

    @Test void unrelatedCompanyCreditsDoNotInvalidateTheSourcePlan() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long first = store.addInvoice(71, original(1, true)), second = store.addInvoice(72, original(1, true));
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var plan = service.plan(71, 11, first, DATE, null);
            service.create(service.plan(72, 12, second, DATE, new BigDecimal("20")));
            assertEquals(1, service.create(plan).credit().getNumber());
        }
    }

    @Test void roundingAndMissingAccountsFailClosedRatherThanOverCreditOrBookIncompleteRows() throws Exception {
        try (Connection c = database()) {
            initialize(c, false);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, original(1, true));
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, id, DATE, new BigDecimal("25.50")));
            assertMoney("25", total(service.create(service.plan(71, 11, id, DATE, new BigDecimal("25"))).credit()));
            SSInvoice invalid = original(2, true); invalid.getDefaultAccounts().clear();
            long broken = store.addInvoice(71, invalid);
            var before = snapshot(c, null);
            assertThrows(IllegalArgumentException.class, () -> service.plan(71, 11, broken, DATE, null));
            assertEquals(before, snapshot(c, null));
        }
    }

    @Test void creationRequiresCallerTransactionAndCanBeRolledBackWithoutConsumingNumber() throws Exception {
        try (Connection c = database()) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            long id = store.addInvoice(71, original(1, true)); c.commit();
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            var plan = service.plan(71, 11, id, DATE, new BigDecimal("25"));
            c.setAutoCommit(true);
            var before = snapshot(c, null);
            assertThrows(SQLException.class, () -> service.create(plan));
            assertEquals(before, snapshot(c, null)); c.setAutoCommit(false);
            assertEquals(1, service.create(plan).credit().getNumber()); c.rollback();
            assertEquals(1, service.create(plan).credit().getNumber());
        }
    }

    @Test void createdCreditCanBeExplicitlyPostedAndReopenedTwiceWithoutChangingOriginalSnapshot() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("credits"); long invoice, credit;
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            initialize(c, true);
            var store = new NormalizedInvoiceStore(c);
            invoice = store.addInvoice(71, original(1, true));
            var service = new NormalizedCustomerCreditService(c, CLOCK);
            credit = service.create(service.plan(71, 11, invoice, DATE, null)).id();
            assertFalse(store.findCreditInvoice(71, credit).orElseThrow().document().isEntered());
            var journal = new NormalizedCustomerJournalService(c, CLOCK);
            journal.post(journal.planCredits(71, 11, DATE, DATE)); c.commit();
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(url, "SA", "")) {
                c.setAutoCommit(false); var store = new NormalizedInvoiceStore(c);
                assertTrue(store.findCreditInvoice(71, credit).orElseThrow().document().isEntered());
                assertEquals(1, store.findInvoice(71, invoice).orElseThrow().document().getStoredVoucher().getNumber());
                assertThrows(IllegalArgumentException.class,
                        () -> new NormalizedCustomerCreditService(c, CLOCK).plan(71, 11, invoice, DATE, null));
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM voucher")) {
                    r.next(); assertEquals(2, r.getInt(1));
                }
                try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
            }
        }
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:credit_workflow_" + System.nanoTime(), "SA", "");
    }
    private static void initialize(Connection c, boolean rounding) throws Exception {
        c.setAutoCommit(false); new SchemaMigrationRunner(CLOCK).migrate(c, NormalizedSchemaMigrations.load());
        var writer = new NormalizedAccountingWriter(CLOCK);
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany(); company.setId(id); company.setName("Company" + id);
            company.setRoundingOff(rounding); writer.addCompany(c, company);
            SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(id == 71 ? 11 : 12);
            year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
            SSAccountPlan accounts = new SSAccountPlan();
            accounts.setAccounts(new ArrayList<>(List.of(new SSAccount(1510), new SSAccount(3010))));
            year.setAccountPlan(accounts); writer.addAccountingYear(c, id, year);
        }
        new NormalizedAccountingWriter(CLOCK).addVoucher(c, 11, voucher()); c.commit();
    }
    private static SSInvoice original(Integer number, boolean entered) {
        SSInvoice invoice = SSInvoice.forPersistence(); invoice.setNumber(number); invoice.setLocalDate(DATE.minusDays(1));
        invoice.setTaxFree(true); invoice.setCurrencyRate(BigDecimal.ONE); invoice.setEntered(entered);
        invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        invoice.getDefaultAccounts().put(SSDefaultAccount.Sales, null);
        SSSaleRow row = new SSSaleRow(); row.setDescription("Source"); row.setUnitprice(new BigDecimal("100"));
        row.setQuantity(BigDecimal.ONE); row.setAccountNr(3010); invoice.getRows().add(row);
        invoice.setVoucher(voucher()); return invoice;
    }
    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher(1, DATE.minusDays(1));
        SSVoucherRow debit = new SSVoucherRow(); debit.setAccountNr(1510); debit.setDebet(new BigDecimal("100"));
        SSVoucherRow credit = new SSVoucherRow(); credit.setAccountNr(3010); credit.setCredit(new BigDecimal("100"));
        voucher.getRows().add(debit); voucher.getRows().add(credit); return voucher;
    }
    private static BigDecimal total(SSCreditInvoice credit) {
        return se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(credit, true);
    }
    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.setScale(2, java.math.RoundingMode.HALF_UP)));
    }
    private static void payment(Connection c, int company, int number, String amount) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO inpayment(legacy_id,company_id,number,entered) SELECT " + company
                    + ",id,1,false FROM company WHERE legacy_id=" + company);
            s.executeUpdate("INSERT INTO inpayment_row(inpayment_id,row_number,invoice_number,value) SELECT id,0,"
                    + number + "," + amount + " FROM inpayment WHERE legacy_id=" + company);
        }
    }
    private static Map<String, List<String>> snapshot(Connection c, Long excludedCredit) throws SQLException {
        List<String> tables = new ArrayList<>(); Map<String, List<String>> result = new TreeMap<>();
        try (ResultSet r = c.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (r.next()) tables.add(r.getString("TABLE_NAME"));
        }
        for (String table : tables) {
            String filter = excludedCredit == null ? "" : table.equals("CUSTOMER_CREDIT_INVOICE")
                    ? " WHERE id<>" + excludedCredit : table.startsWith("CUSTOMER_CREDIT_INVOICE_")
                    ? " WHERE credit_invoice_id<>" + excludedCredit : "";
            List<String> rows = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM \"" + table + "\"" + filter)) {
                while (r.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        String value = r.getString(i);
                        row.append(r.getMetaData().getColumnName(i)).append(':');
                        if (value == null) row.append("NULL;");
                        else row.append(value.length()).append(':').append(value).append(';');
                    }
                    rows.add(row.toString());
                }
            }
            rows.sort(String::compareTo); result.put(table, rows);
        }
        assertFalse(result.isEmpty()); return result;
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
