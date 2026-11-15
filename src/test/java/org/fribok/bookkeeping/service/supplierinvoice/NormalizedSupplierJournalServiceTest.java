package org.fribok.bookkeeping.service.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.dataformat.SemanticFingerprint;
import org.fribok.bookkeeping.dataformat.SupplierCreditInvoiceStagingConverter;
import org.fribok.bookkeeping.dataformat.SupplierInvoiceStagingConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
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

class NormalizedSupplierJournalServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1),
            TO = LocalDate.of(2026, 10, 31);
    @TempDir Path temporary;

    @Test
    void invoicePostingIsBalancedScopedAndCallerOwnedWithoutLegacyJdbc() throws Exception {
        success(false);
    }

    @Test
    void creditPostingIsBalancedScopedAndCallerOwnedWithoutLegacyJdbc() throws Exception {
        success(true);
    }

    private void success(boolean credit) throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedSupplierInvoiceStore(c);
            long first = add(store, credit, 71, document(credit, 1, "100"));
            long second = add(store, credit, 71, document(credit, 2, "45"));
            add(store, credit, 72, document(credit, 1, "999"));
            SSSupplierInvoice outside = document(credit, 3, "888");
            outside.setLocalDate(TO.plusDays(1));
            add(store, credit, 71, outside);
            c.commit();
            try (LegacyGuard ignored = new LegacyGuard()) {
                var service = new NormalizedSupplierJournalService(c, CLOCK);
                var plan = plan(service, credit);
                assertEquals(0, count(c, "voucher"));
                assertFalse(stored(store, credit, first).isEntered());
                var posted = service.post(plan);
                assertEquals(List.of(first, second), posted.documentIds());
                assertEquals(1, posted.journalNumber());
                assertEquals(1, posted.voucherNumber());
                assertEquals(1, counter(c, credit));
                assertTrue(stored(store, credit, first).isEntered());
                assertTrue(stored(store, credit, second).isEntered());
                assertFalse(
                        (credit
                                        ? store.listSupplierCreditInvoices(72)
                                        : store.listSupplierInvoices(72))
                                .get(0)
                                .document()
                                .isEntered());
                assertFalse(
                        (credit
                                        ? store.listSupplierCreditInvoices(71)
                                        : store.listSupplierInvoices(71))
                                .get(2)
                                .document()
                                .isEntered());
                var accounting = accounting(c);
                SSVoucher ledger = accounting.getVouchers().get(0);
                assertEquals(TO, ledger.getLocalDate());
                assertEquals(
                        0,
                        new BigDecimal("145")
                                .compareTo(
                                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(
                                                ledger, accounting.getAccounts())));
                assertEquals(
                        0,
                        new BigDecimal("145")
                                .compareTo(
                                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                                                .getCreditSum(ledger, accounting.getAccounts())));
                assertEquals(
                        LocalDate.of(2026, 11, 11),
                        stored(store, credit, first).getVoucher().getLocalDate());
                assertThrows(IllegalArgumentException.class, () -> service.post(plan));
                c.rollback();
                assertEquals(0, count(c, "voucher"));
                assertEquals(0, counter(c, credit));
                assertFalse(stored(store, credit, first).isEntered());
                service.post(plan(service, credit));
                c.commit();
            }
        }
    }

    @Test
    void lateDocumentLedgerAndCounterFailuresRollBackWholeJournalAndAllowRetry() throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            for (String failure : new String[] {"document", "ledger", "counter"}) {
                try (Connection c = database()) {
                    initialize(c);
                    var store = new NormalizedSupplierInvoiceStore(c);
                    long first = add(store, credit, 71, document(credit, 1, "100"));
                    long second = add(store, credit, 71, document(credit, 2, "45"));
                    c.commit();
                    String family = credit ? "supplier_credit_invoice" : "supplier_invoice";
                    String table =
                            failure.equals("document")
                                    ? family + "_voucher_row"
                                    : failure.equals("ledger")
                                            ? "voucher_row"
                                            : "company_auto_increment";
                    String condition =
                            failure.equals("document")
                                    ? (credit
                                                    ? "supplier_credit_invoice_id"
                                                    : "supplier_invoice_id")
                                            + "<>"
                                            + second
                                    : failure.equals("ledger")
                                            ? "credit IS NULL"
                                            : "counter_value<1";
                    try (Statement s = c.createStatement()) {
                        s.execute(
                                "ALTER TABLE "
                                        + table
                                        + " ADD CONSTRAINT fail_post CHECK("
                                        + condition
                                        + ")");
                    }
                    var outer = c.setSavepoint();
                    // Earlier uncommitted work in the same caller transaction must survive failure.
                    add(store, credit, 72, document(credit, 50, "20"));
                    var before = fingerprint(c, credit);
                    var service = new NormalizedSupplierJournalService(c, CLOCK);
                    var plan = plan(service, credit);
                    assertThrows(
                            SQLException.class, () -> service.post(plan), failure + "/" + credit);
                    assertEquals(before, fingerprint(c, credit));
                    assertFalse(stored(store, credit, first).isEntered());
                    assertFalse(stored(store, credit, second).isEntered());
                    assertEquals(0, count(c, "voucher"));
                    assertEquals(0, counter(c, credit));
                    assertEquals(
                            1,
                            credit
                                    ? store.listSupplierCreditInvoices(72).size()
                                    : store.listSupplierInvoices(72).size());
                    c.rollback(outer);
                    assertTrue(
                            credit
                                    ? store.listSupplierCreditInvoices(72).isEmpty()
                                    : store.listSupplierInvoices(72).isEmpty());
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE " + table + " DROP CONSTRAINT fail_post");
                    }
                    // Failed posting did not mutate the token or presentation snapshots.
                    assertEquals(1, service.post(plan).journalNumber());
                    c.commit();
                    assertEquals(1, count(c, "voucher"));
                    assertEquals(1, counter(c, credit));
                }
            }
        }
    }

    @Test
    void staleFinanceAndSelectionRefusedButEarlierCallerEditsRetained() throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedSupplierInvoiceStore(c);
                long id = add(store, credit, 71, document(credit, 1, "100"));
                c.commit();
                var service = new NormalizedSupplierJournalService(c, CLOCK);
                var old = plan(service, credit);
                update(store, credit, id, document(credit, 1, "150"));
                var before = fingerprint(c, credit);
                assertThrows(IllegalArgumentException.class, () -> service.post(old));
                assertEquals(before, fingerprint(c, credit));
                assertEquals(0, count(c, "voucher"));
                var current = plan(service, credit);
                add(store, credit, 71, document(credit, 2, "10"));
                assertThrows(IllegalArgumentException.class, () -> service.post(current));
                service.post(plan(service, credit));
                assertEquals(1, count(c, "voucher"));
            }
        }
    }

    @Test
    void staleCounterAndLedgerNumbersRefusedBeforeAnyDocumentChanges() throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            for (boolean changeCounter : new boolean[] {false, true}) {
                try (Connection c = database()) {
                    initialize(c);
                    var store = new NormalizedSupplierInvoiceStore(c);
                    long id = add(store, credit, 71, document(credit, 1, "100"));
                    var service = new NormalizedSupplierJournalService(c, CLOCK);
                    var plan = plan(service, credit);
                    if (changeCounter)
                        new NormalizedRegisterStore(c)
                                .bumpAutoIncrement(
                                        71,
                                        credit
                                                ? "suppliercreditinvoicejournal"
                                                : "supplierinvoicejournal");
                    else
                        new NormalizedAccountingWriter(CLOCK)
                                .addVoucher(c, 11, new SSVoucher(1, TO));
                    assertThrows(IllegalArgumentException.class, () -> service.post(plan));
                    assertFalse(stored(store, credit, id).isEntered());
                    var posted = service.post(plan(service, credit));
                    assertEquals(changeCounter ? 2 : 1, posted.journalNumber());
                    assertEquals(changeCounter ? 1 : 2, posted.voucherNumber());
                }
            }
        }
    }

    @Test
    void mutablePreviewCannotReplacePostedLedgerOrSnapshotsAndMetadataChangesAreRetained()
            throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedSupplierInvoiceStore(c);
                long id = add(store, credit, 71, document(credit, 1, "100"));
                var service = new NormalizedSupplierJournalService(c, CLOCK);
                var plan = plan(service, credit);
                SSVoucher preview =
                        credit ? plan.creditPlan().voucher() : plan.invoicePlan().voucher();
                preview.setNumber(999);
                preview.getRows().clear();
                SSSupplierInvoice detached =
                        credit
                                ? plan.creditPlan().invoices().get(0)
                                : plan.invoicePlan().invoices().get(0);
                detached.setEntered(true);
                detached.getRows().clear();
                SSSupplierInvoice fresh = document(credit, 1, "100");
                fresh.setSupplierName("Fresh");
                update(store, credit, id, fresh);
                service.post(plan);
                assertEquals("Fresh", stored(store, credit, id).getSupplierName());
                assertFalse(accounting(c).getVouchers().get(0).getRows().isEmpty());
                assertEquals(1, accounting(c).getVouchers().get(0).getNumber());
            }
        }
    }

    @Test
    void missingCompanyWrongYearEmptyPeriodInvalidNumbersAndOverflowFailClosed() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var service = new NormalizedSupplierJournalService(c, CLOCK);
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(999, 11, FROM, TO));
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(71, 12, FROM, TO));
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(71, 11, TO, FROM));
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            var store = new NormalizedSupplierInvoiceStore(c);
            long id = store.addSupplierInvoice(71, document(false, null, "100"));
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            store.updateSupplierInvoice(71, id, document(false, 1, "100"));
            long duplicate = store.addSupplierInvoice(71, document(false, 2, "200"));
            try (Statement statement = c.createStatement()) {
                statement.executeUpdate(
                        "UPDATE supplier_invoice SET number=1 WHERE id=" + duplicate);
            }
            assertThrows(
                    IllegalArgumentException.class, () -> service.planInvoices(71, 11, FROM, TO));
            store.deleteSupplierInvoice(71, duplicate);
            var plan = plan(service, false);
            c.setAutoCommit(true);
            assertThrows(SQLException.class, () -> service.post(plan));
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT INTO company_auto_increment(company_id,counter_name,counter_value)"
                            + " SELECT id,'supplierinvoicejournal',2147483647 FROM company WHERE"
                            + " legacy_id=71");
            }
            assertThrows(IllegalArgumentException.class, () -> plan(service, false));
            assertFalse(stored(store, false, id).isEntered());
            assertEquals(0, count(c, "voucher"));
        }
    }

    @Test
    void postingSurvivesTwoReopensWithoutLegacyTables() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("posting");
        long invoice, credit;
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            initialize(c);
            var store = new NormalizedSupplierInvoiceStore(c);
            invoice = store.addSupplierInvoice(71, document(false, 1, "100"));
            credit =
                    store.addSupplierCreditInvoice(
                            71, (SSSupplierCreditInvoice) document(true, 2, "25"));
            var service = new NormalizedSupplierJournalService(c, CLOCK);
            service.post(plan(service, false));
            service.post(plan(service, true));
            c.commit();
            try (Statement s = c.createStatement()) {
                s.execute("SHUTDOWN");
            }
        }
        for (int reopen = 0; reopen < 2; reopen++) {
            try (Connection c = DriverManager.getConnection(url, "SA", "")) {
                c.setAutoCommit(false);
                var store = new NormalizedSupplierInvoiceStore(c);
                assertTrue(
                        store.findSupplierInvoice(71, invoice)
                                .orElseThrow()
                                .document()
                                .isEntered());
                assertTrue(
                        store.findSupplierCreditInvoice(71, credit)
                                .orElseThrow()
                                .document()
                                .isEntered());
                assertEquals(2, count(c, "voucher"));
                assertEquals(1, counter(c, false));
                assertEquals(1, counter(c, true));
                try (Statement s = c.createStatement()) {
                    s.execute("SHUTDOWN");
                }
            }
        }
    }

    @Test
    void dimensionsAreExplicitAndPerDocumentImbalancesCannotCancelAcrossJournal() throws Exception {
        try (Connection c = database()) {
            initialize(c);
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT INTO project(company_id,number,concluded) SELECT id,'P',false FROM"
                            + " company");
                s.executeUpdate(
                        "INSERT INTO result_unit(company_id,number) SELECT id,'R' FROM company");
            }
            var store = new NormalizedSupplierInvoiceStore(c);
            SSSupplierInvoice invoice = document(false, 1, "100");
            invoice.getRows().get(0).setProjectNr("P");
            invoice.getRows().get(0).setResultUnitNr("R");
            long id = store.addSupplierInvoice(71, invoice);
            var service = new NormalizedSupplierJournalService(c, CLOCK);
            try (LegacyGuard ignored = new LegacyGuard()) {
                service.post(plan(service, false));
            }
            assertTrue(
                    accounting(c).getVouchers().get(0).getRows().stream()
                            .anyMatch(
                                    row ->
                                            "P".equals(row.getProjectNr())
                                                    && "R".equals(row.getResultUnitNr())));
        }
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedSupplierInvoiceStore(c);
            SSSupplierInvoice positive = document(false, 1, "100"),
                    negative = document(false, 2, "-100");
            positive.getDefaultAccounts().clear();
            negative.getDefaultAccounts().clear();
            store.addSupplierInvoice(71, positive);
            store.addSupplierInvoice(71, negative);
            var before = fingerprint(c, false);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> plan(new NormalizedSupplierJournalService(c, CLOCK), false));
            assertEquals(before, fingerprint(c, false));
            assertEquals(0, count(c, "voucher"));
        }
    }

    @Test
    void manualCorrectionsArePostedAndPreservedAndStaleCorrectionsAreRejected() throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedSupplierInvoiceStore(c);
                SSSupplierInvoice invoice = document(credit, 1, "100");
                SSVoucher correction = new SSVoucher(77, FROM.minusDays(2));
                correction.setDescription("Keep correction");
                var debit = new se.swedsoft.bookkeeping.data.SSVoucherRow();
                debit.setAccountNr(4010);
                debit.setDebet(new BigDecimal("10"));
                debit.setLocalEditedDate(
                        java.time.LocalDateTime.parse("2026-10-01T12:13:14.123456789"));
                debit.setEditedSignature("audit");
                debit.setAdded(true);
                var creditRow = new se.swedsoft.bookkeeping.data.SSVoucherRow();
                creditRow.setAccountNr(2440);
                creditRow.setCredit(new BigDecimal("10"));
                correction.getRows().add(debit);
                correction.getRows().add(creditRow);
                invoice.setCorrection(correction);
                long id = add(store, credit, 71, invoice);
                var service = new NormalizedSupplierJournalService(c, CLOCK);
                var old = plan(service, credit);
                var changed = stored(store, credit, id);
                changed.getCorrection().getRows().get(0).setDebet(new BigDecimal("20"));
                changed.getCorrection().getRows().get(1).setCredit(new BigDecimal("20"));
                update(store, credit, id, changed);
                var before = fingerprint(c, credit);
                assertThrows(IllegalArgumentException.class, () -> service.post(old));
                assertEquals(before, fingerprint(c, credit));
                service.post(plan(service, credit));
                var retained = stored(store, credit, id).getCorrection();
                assertEquals(77, retained.getNumber());
                assertEquals("Keep correction", retained.getDescription());
                assertEquals(
                        debit.getLocalEditedDate(), retained.getRows().get(0).getLocalEditedDate());
                assertEquals("audit", retained.getRows().get(0).getEditedSignature());
                assertTrue(retained.getRows().get(0).isAdded());
                var ledger = accounting(c).getVouchers().get(0);
                // Credits reverse the original 100, but the manual correction remains a +20 debit.
                assertEquals(
                        0,
                        new BigDecimal(credit ? "80" : "120")
                                .compareTo(
                                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(
                                                ledger, accounting(c).getAccounts())));
            }
        }
    }

    @Test
    void undatedAndAlreadyBookedDocumentsStayOutsideSelection() throws Exception {
        for (boolean credit : new boolean[] {false, true}) {
            try (Connection c = database()) {
                initialize(c);
                var store = new NormalizedSupplierInvoiceStore(c);
                long selected = add(store, credit, 71, document(credit, 1, "100"));
                var undated = document(credit, 2, "15");
                undated.setLocalDate(null);
                long excluded = add(store, credit, 71, undated);
                var booked = document(credit, 3, "20");
                booked.setEntered(true);
                add(store, credit, 71, booked);
                var service = new NormalizedSupplierJournalService(c, CLOCK);
                assertEquals(List.of(selected), plan(service, credit).documentIds());
                service.post(plan(service, credit));
                assertFalse(stored(store, credit, excluded).isEntered());
                assertThrows(
                        NormalizedSupplierJournalService.EmptyJournalException.class,
                        () -> plan(service, credit));
            }
        }
    }

    @Test
    void stableIdentityBookingRejectsWrongCompanyAndAlreadyEnteredWithoutChangingSnapshots()
            throws Exception {
        try (Connection c = database()) {
            initialize(c);
            var store = new NormalizedSupplierInvoiceStore(c);
            long id = store.addSupplierInvoice(71, document(false, 1, "100"));
            assertThrows(
                    SQLException.class,
                    () -> store.markSupplierInvoiceEnteredById(72, id, new SSVoucher(0, FROM)));
            assertFalse(stored(store, false, id).isEntered());
            var service = new NormalizedSupplierJournalService(c, CLOCK);
            service.post(plan(service, false));
            var before = fingerprint(c, false);
            assertThrows(
                    SQLException.class,
                    () -> store.markSupplierInvoiceEnteredById(71, id, new SSVoucher(9, TO)));
            assertEquals(before, fingerprint(c, false));
        }
    }

    @Test
    void newSupplierDefaultsAreExplicitAndNeverBackfillStoredDocuments() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.getDefaultAccounts().put(SSDefaultAccount.SupplierDebt, 2440);
        try (LegacyGuard ignored = new LegacyGuard()) {
            var invoice = SupplierInvoiceFactory.create(company, FROM);
            assertEquals(FROM, invoice.getLocalDate());
            assertEquals(FROM, invoice.getLocalDueDate());
            assertEquals(2440, invoice.getDefaultAccounts().get(SSDefaultAccount.SupplierDebt));
            assertEquals(BigDecimal.ONE, invoice.getCurrencyRate());
            assertTrue(invoice.isStockInfluencing());
            assertFalse(invoice.isEntered());
            assertNull(invoice.getVoucher());
            assertNull(invoice.getCorrection());
            assertNull(invoice.getNumber());
            invoice.getDefaultAccounts().clear();
            assertEquals(2440, company.getDefaultAccounts().get(SSDefaultAccount.SupplierDebt));
            assertTrue(SSSupplierInvoice.forPersistence().getDefaultAccounts().isEmpty());
        }
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:hsqldb:mem:posting_" + System.nanoTime(), "SA", "");
    }

    private static void initialize(Connection c) throws Exception {
        c.setAutoCommit(false);
        new SchemaMigrationRunner(CLOCK).migrate(c, NormalizedSchemaMigrations.load());
        var writer = new NormalizedAccountingWriter(CLOCK);
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany();
            company.setId(id);
            company.setName("Company" + id);
            company.setRoundingOff(false);
            writer.addCompany(c, company);
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setId(id == 71 ? 11 : 12);
            year.setLocalFrom(LocalDate.of(2026, 1, 1));
            year.setLocalTo(LocalDate.of(2026, 12, 31));
            SSAccountPlan accounts = new SSAccountPlan();
            accounts.setAccounts(
                    new java.util.ArrayList<>(List.of(new SSAccount(2440), new SSAccount(4010))));
            year.setAccountPlan(accounts);
            writer.addAccountingYear(c, id, year);
        }
        c.commit();
    }

    private static SSSupplierInvoice document(boolean credit, Integer number, String amount) {
        SSSupplierInvoice invoice =
                credit
                        ? SSSupplierCreditInvoice.forPersistence()
                        : SSSupplierInvoice.forPersistence();
        invoice.setNumber(number);
        invoice.setLocalDate(FROM);
        invoice.setTaxSum(BigDecimal.ZERO);
        invoice.setRoundingSum(BigDecimal.ZERO);
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.getDefaultAccounts().put(SSDefaultAccount.SupplierDebt, 2440);
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal(amount));
        row.setAccountNr(4010);
        invoice.getRows().add(row);
        return invoice;
    }

    private static long add(
            NormalizedSupplierInvoiceStore s,
            boolean credit,
            int company,
            SSSupplierInvoice invoice)
            throws Exception {
        return credit
                ? s.addSupplierCreditInvoice(company, (SSSupplierCreditInvoice) invoice)
                : s.addSupplierInvoice(company, invoice);
    }

    private static void update(
            NormalizedSupplierInvoiceStore s, boolean credit, long id, SSSupplierInvoice invoice)
            throws Exception {
        if (credit) s.updateSupplierCreditInvoice(71, id, (SSSupplierCreditInvoice) invoice);
        else s.updateSupplierInvoice(71, id, invoice);
    }

    private static SSSupplierInvoice stored(
            NormalizedSupplierInvoiceStore s, boolean credit, long id) throws Exception {
        return credit
                ? s.findSupplierCreditInvoice(71, id).orElseThrow().document()
                : s.findSupplierInvoice(71, id).orElseThrow().document();
    }

    private static NormalizedSupplierJournalService.Plan plan(
            NormalizedSupplierJournalService s, boolean credit) throws SQLException {
        return credit ? s.planCredits(71, 11, FROM, TO) : s.planInvoices(71, 11, FROM, TO);
    }

    private static int counter(Connection c, boolean credit) throws SQLException {
        return new NormalizedRegisterStore(c)
                .autoIncrementValue(
                        71, credit ? "suppliercreditinvoicejournal" : "supplierinvoicejournal");
    }

    private static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static SemanticFingerprint fingerprint(Connection c, boolean credit)
            throws SQLException {
        return credit
                ? new SupplierCreditInvoiceStagingConverter().fingerprintNormalized(c)
                : new SupplierInvoiceStagingConverter().fingerprintNormalized(c);
    }

    private static NormalizedAccountingStore accounting(Connection c) throws Exception {
        var store = new NormalizedAccountingStore(c, CLOCK);
        var company =
                store.getCompanies().stream()
                        .filter(v -> v.getId() == 71)
                        .findFirst()
                        .orElseThrow();
        store.setCurrentCompany(company);
        store.setCurrentYear(store.getYears().get(0));
        return store;
    }

    private static final class LegacyGuard implements AutoCloseable {
        private final Map<Field, Object> saved = new HashMap<>();

        LegacyGuard() throws Exception {
            for (String name :
                    List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field f = SSDB.class.getDeclaredField(name);
                f.setAccessible(true);
                saved.put(f, f.get(SSDB.getInstance()));
                f.set(SSDB.getInstance(), null);
            }
            Field f = SSDB.class.getDeclaredField("iConnection");
            f.setAccessible(true);
            f.set(
                    SSDB.getInstance(),
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                throw new AssertionError(
                                        "Unexpected legacy JDBC: " + method.getName());
                            }));
        }

        @Override
        public void close() throws Exception {
            for (var entry : saved.entrySet())
                entry.getKey().set(SSDB.getInstance(), entry.getValue());
        }
    }
}
