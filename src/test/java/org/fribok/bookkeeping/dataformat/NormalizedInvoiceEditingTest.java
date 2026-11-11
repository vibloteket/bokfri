package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedInvoiceEditingTest {
    @TempDir Path temporary;

    @Test void invoiceMigrationResavePreservesCompleteFingerprint() throws Exception { migrationParity(false); }
    @Test void creditMigrationResavePreservesCompleteFingerprint() throws Exception { migrationParity(true); }

    private void migrationParity(boolean credit) throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            SSInvoice source = full(credit, 101);
            source.setCurrencyRate(new BigDecimal("1.23456"));
            String table = credit ? "tbl_creditinvoice" : "tbl_invoice";
            String column = credit ? "creditinvoice" : "invoice";
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE " + table + " (id INTEGER, companyid INTEGER, number INTEGER, "
                        + column + " OTHER)");
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " VALUES(91,71,101,?)")) {
                s.setObject(1, source);
                s.executeUpdate();
            }
            SemanticFingerprint expected = credit
                    ? new CustomerCreditInvoiceStagingConverter().convert(c, c).sourceFingerprint()
                    : new CustomerInvoiceStagingConverter().convert(c, c).sourceFingerprint();
            try (Statement s = c.createStatement()) { s.execute("DROP TABLE " + table); }
            try (LegacyGuard ignored = new LegacyGuard()) {
                NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
                if (credit) {
                    var stored = store.listCreditInvoices(71).get(0);
                    assertEquals(91, stored.legacyId());
                    store.updateCreditInvoice(71, stored.id(), stored.document());
                } else {
                    var stored = store.listInvoices(71).get(0);
                    assertEquals(91, stored.legacyId());
                    store.updateInvoice(71, stored.id(), stored.document());
                }
                assertEquals(java.util.List.of(), expected.differences(fingerprint(c, credit)));
                assertTrue(credit ? store.listCreditInvoices(72).isEmpty() : store.listInvoices(72).isEmpty());
            }
        }
    }

    @Test void invoiceCrudStableIdentityAndDetachedNullSnapshots() throws Exception { crud(false); }
    @Test void creditCrudStableIdentityAndDetachedNullSnapshots() throws Exception { crud(true); }

    private void crud(boolean credit) throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            try (LegacyGuard ignored = new LegacyGuard()) {
                NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
                long first = add(store, credit, 71, full(credit, null));
                long second = add(store, credit, 71, full(credit, 102));
                long other = add(store, credit, 72, full(credit, 102));
                var old = stored(store, credit, 71, first);
                SSInvoice replacement = full(credit, 102); // historical duplicates remain readable
                // New renumbers must not create duplicate identities. Null is still a distinct absence.
                assertThrows(SQLException.class, () -> update(store, credit, 71, first, replacement));
                SSInvoice empty = empty(credit);
                update(store, credit, 71, first, empty);
                var fresh = stored(store, credit, 71, first);
                assertEquals(old.legacyId(), fresh.legacyId());
                assertNull(fresh.document().getNumber());
                assertNull(fresh.document().getInvoiceAddress());
                assertNull(fresh.document().getDeliveryAddress());
                assertNull(fresh.document().getStoredVoucher());
                assertNull(fresh.document().getStoredType());
                assertNull(fresh.document().getCurrencyRate());
                assertEquals(java.util.Arrays.asList(null, null, null), fresh.document().getStoredTaxRates());
                assertTrue(fresh.document().getRows().isEmpty());
                assertTrue(fresh.document().getDefaultAccounts().isEmpty());
                fresh.document().setCustomerName("not saved");
                assertNull(stored(store, credit, 71, first).document().getCustomerName());
                assertThrows(SQLException.class, () -> update(store, credit, 72, first, empty));
                assertThrows(SQLException.class, () -> delete(store, credit, 72, first));
                assertEquals("Customer", stored(store, credit, 72, other).document().getCustomerName());
                delete(store, credit, 71, first);
                assertThrows(SQLException.class, () -> delete(store, credit, 71, first));
                assertEquals(1, credit ? store.listCreditInvoices(71).size() : store.listInvoices(71).size());
                assertEquals(second, credit ? store.listCreditInvoices(71).get(0).id()
                        : store.listInvoices(71).get(0).id());
                delete(store, credit, 71, second);
            }
        }
    }

    @Test void invoiceInsertUpdateDeleteLateFailuresPreserveOuterWorkAndRetry() throws Exception { failures(false); }
    @Test void creditInsertUpdateDeleteLateFailuresPreserveOuterWorkAndRetry() throws Exception { failures(true); }

    private void failures(boolean credit) throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            long id = add(store, credit, 71, full(credit, 1));
            c.commit();
            String table = credit ? "customer_credit_invoice" : "customer_invoice";
            try (Statement statement = c.createStatement()) {
                statement.execute("ALTER TABLE " + table
                        + "_row ADD CONSTRAINT fail_row CHECK(description <> 'FAIL')");
                statement.execute("CREATE TABLE hold_document (document_id BIGINT REFERENCES " + table + "(id))");
            }
            // All test DDL precedes the caller transaction: DDL commits in HSQLDB.
            var outer = c.setSavepoint();
            add(store, credit, 72, full(credit, 2));
            try (Statement statement = c.createStatement()) {
                statement.executeUpdate("INSERT INTO hold_document VALUES(" + id + ")");
            }
            SemanticFingerprint before = fingerprint(c, credit);
            SSInvoice bad = full(credit, 3);
            bad.getRows().get(1).setDescription("FAIL");
            assertThrows(SQLException.class, () -> add(store, credit, 71, bad));
            assertEquals(before, fingerprint(c, credit));
            assertThrows(SQLException.class, () -> update(store, credit, 71, id, bad));
            assertEquals(before, fingerprint(c, credit));
            // Restrictive dependent table fails after owned children have been deleted.
            assertThrows(SQLException.class, () -> delete(store, credit, 71, id));
            assertEquals(before, fingerprint(c, credit));
            // Caller can still roll back to its savepoint after repeated failed operations.
            c.rollback(outer);
            assertTrue(credit ? store.listCreditInvoices(72).isEmpty() : store.listInvoices(72).isEmpty());
            bad.getRows().get(1).setDescription("fixed");
            update(store, credit, 71, id, bad);
            assertEquals(3, stored(store, credit, 71, id).document().getNumber());
            delete(store, credit, 71, id);
            assertTrue(credit ? store.listCreditInvoices(71).isEmpty() : store.listInvoices(71).isEmpty());
            c.rollback();
            assertEquals(1, stored(store, credit, 71, id).document().getNumber());
            assertTrue(credit ? store.listCreditInvoices(72).isEmpty() : store.listInvoices(72).isEmpty());
        }
    }

    @Test void invoiceReferencesProtectRenumberAndDeleteWithoutCrossCompanyFalsePositives() throws Exception {
        for (String ref : new String[]{"payment", "credit", "order"}) {
            try (Connection c = memory()) {
                initialize(c);
                NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
                long id = store.addInvoice(71, full(false, 10));
                store.addInvoice(72, full(false, 10));
                long other = store.listInvoices(72).get(0).id();
                reference(c, ref, 71, 10);
                SSInvoice changed = full(false, 20);
                var before = fingerprint(c, false);
                assertThrows(SQLException.class, () -> store.updateInvoice(71, id, changed), ref);
                assertThrows(SQLException.class, () -> store.deleteInvoice(71, id), ref);
                assertEquals(before, fingerprint(c, false));
                // Editing a snapshot without renumbering preserves the scalar references.
                changed.setNumber(10);
                changed.setCustomerName("edited");
                store.updateInvoice(71, id, changed);
                assertEquals("edited", store.findInvoice(71, id).orElseThrow().document().getCustomerName());
                store.deleteInvoice(72, other);
                assertEquals(1, store.listInvoices(71).size());
            }
        }
    }

    @Test void bookedDocumentsRequireExplicitPostingWorkflowAndLedgerRemainsUnchanged() throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            for (boolean credit : new boolean[]{false, true}) {
                SSInvoice booked = full(credit, 10);
                booked.setEntered(true);
                long id = add(store, credit, 71, booked);
                var before = fingerprint(c, credit);
                assertThrows(SQLException.class, () -> update(store, credit, 71, id, full(credit, 10)));
                assertThrows(SQLException.class, () -> delete(store, credit, 71, id));
                assertEquals(before, fingerprint(c, credit));
                long unbooked = add(store, credit, 71, full(credit, 20));
                assertThrows(SQLException.class, () -> update(store, credit, 71, unbooked, booked));
            }
            try (Statement s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM voucher")) {
                r.next(); assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test void bookingSnapshotFailureIsAtomicAndAmbiguousNumbersNeverMassUpdate() throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            for (boolean credit : new boolean[]{false, true}) {
                long id = add(store, credit, 71, full(credit, 1));
                String table = credit ? "customer_credit_invoice" : "customer_invoice";
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE " + table + "_voucher_row ADD CONSTRAINT fail_voucher_"
                            + credit + " CHECK(edited_signature <> 'FAIL')");
                }
                var before = fingerprint(c, credit);
                SSVoucher bad = voucher();
                bad.getRows().get(0).setEditedSignature("FAIL");
                assertThrows(SQLException.class, () -> mark(store, credit, 1, bad));
                assertEquals(before, fingerprint(c, credit));
                assertFalse(stored(store, credit, 71, id).document().isEntered());
                long duplicate = add(store, credit, 71, full(credit, 1));
                before = fingerprint(c, credit);
                assertThrows(SQLException.class, () -> mark(store, credit, 1, voucher()));
                assertEquals(before, fingerprint(c, credit));
                // Stable identities can resolve an unreferenced historical duplicate.
                delete(store, credit, 71, duplicate);
                mark(store, credit, 1, voucher());
                assertTrue(stored(store, credit, 71, id).document().isEntered());
            }
        }
    }

    @Test void autoCommitRejectedAndUnsupportedGraphsNotSilentlyFlattened() throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            for (boolean credit : new boolean[]{false, true}) {
                long id = add(store, credit, 71, full(credit, 1));
                SSInvoice linked = full(credit, 2);
                linked.getStoredVoucher().setCorrects(voucher());
                var before = fingerprint(c, credit);
                assertThrows(SQLException.class, () -> add(store, credit, 71, linked));
                assertThrows(SQLException.class, () -> update(store, credit, 71, id, linked));
                assertThrows(SQLException.class, () -> mark(store, credit, 1, linked.getStoredVoucher()));
                assertEquals(before, fingerprint(c, credit));
                c.setAutoCommit(true);
                assertThrows(SQLException.class, () -> add(store, credit, 71, full(credit, 3)));
                assertThrows(SQLException.class, () -> update(store, credit, 71, id, full(credit, 3)));
                assertThrows(SQLException.class, () -> delete(store, credit, 71, id));
                assertThrows(SQLException.class, () -> mark(store, credit, 1, voucher()));
                c.setAutoCommit(false);
                assertEquals(before, fingerprint(c, credit));
            }
            assertThrows(SQLException.class, () -> store.addInvoice(71, SSCreditInvoice.forPersistence()));
        }
    }

    @Test void committedCrudSurvivesTwoReopensAndCallerRollbackStaysUncommitted() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("catalog").toAbsolutePath();
        long invoice, credit;
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            invoice = store.addInvoice(71, full(false, 1));
            credit = store.addCreditInvoice(71, (SSCreditInvoice) full(true, 2));
            c.commit();
            store.updateInvoice(71, invoice, full(false, 3));
            c.rollback();
            assertEquals(1, store.findInvoice(71, invoice).orElseThrow().document().getNumber());
            store.updateInvoice(71, invoice, full(false, 4));
            store.updateCreditInvoice(71, credit, (SSCreditInvoice) full(true, 5));
            c.commit();
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
        try (Connection c = DriverManager.getConnection(url, "SA", ""); LegacyGuard ignored = new LegacyGuard()) {
            c.setAutoCommit(false);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            assertEquals(4, store.findInvoice(71, invoice).orElseThrow().document().getNumber());
            assertEquals(5, store.findCreditInvoice(71, credit).orElseThrow().document().getNumber());
            store.deleteInvoice(71, invoice);
            store.deleteCreditInvoice(71, credit);
            c.commit();
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
        try (Connection c = DriverManager.getConnection(url, "SA", "")) {
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            assertTrue(store.listInvoices(71).isEmpty());
            assertTrue(store.listCreditInvoices(71).isEmpty());
            try (Statement s = c.createStatement()) { s.execute("SHUTDOWN"); }
        }
    }

    @Test void danglingReferencesCannotBeSilentlyReattachedByInsertOrRenumber() throws Exception {
        for (String ref : new String[]{"payment", "credit", "order"}) {
            try (Connection c = memory()) {
                initialize(c);
                NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
                long id = store.addInvoice(71, full(false, 10));
                reference(c, ref, 71, 20); // historical reference with no surviving target
                var before = fingerprint(c, false);
                assertThrows(SQLException.class, () -> store.addInvoice(71, full(false, 20)), ref);
                assertThrows(SQLException.class, () -> store.updateInvoice(71, id, full(false, 20)), ref);
                assertEquals(before, fingerprint(c, false));
                store.addInvoice(72, full(false, 20));
            }
        }
    }

    @Test void missingCompanyRuntimeFailureAndLegacyIdExhaustionLeaveNoPartialGraphsOrCounterChanges()
            throws Exception {
        try (Connection c = memory()) {
            initialize(c);
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(c);
            for (boolean credit : new boolean[]{false, true}) {
                String table = credit ? "customer_credit_invoice" : "customer_invoice";
                String counter = credit ? "creditinvoice" : "invoice";
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate("INSERT INTO company_auto_increment(company_id,counter_name,counter_value)"
                            + " SELECT id,'" + counter + "',999 FROM company WHERE legacy_id=71");
                }
                var before = fingerprint(c, credit);
                assertThrows(SQLException.class, () -> add(store, credit, 999, full(credit, 10)));
                assertEquals(before, fingerprint(c, credit));
                SSInvoice broken = full(credit, 10);
                broken.getRows().add(null);
                assertThrows(NullPointerException.class, () -> add(store, credit, 71, broken));
                assertEquals(before, fingerprint(c, credit));
                long id = add(store, credit, 71, full(credit, 10));
                var saved = fingerprint(c, credit);
                assertThrows(NullPointerException.class, () -> update(store, credit, 71, id, broken));
                assertEquals(saved, fingerprint(c, credit));
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate("UPDATE " + table + " SET legacy_id=2147483647 WHERE id=" + id);
                }
                saved = fingerprint(c, credit);
                assertThrows(SQLException.class, () -> add(store, credit, 71, full(credit, 11)));
                assertEquals(saved, fingerprint(c, credit));
                delete(store, credit, 71, id);
                assertEquals(1000, credit ? store.nextCreditInvoiceNumber(71) : store.nextInvoiceNumber(71));
            }
        }
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:invoice_edit_" + System.nanoTime(), "SA", "");
    }

    private static void initialize(Connection c) throws Exception {
        c.setAutoCommit(false);
        new SchemaMigrationRunner(java.time.Clock.systemUTC()).migrate(c, NormalizedSchemaMigrations.load());
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO company(legacy_id,name) VALUES(71,'One'),(72,'Two')");
            s.executeUpdate("INSERT INTO project(company_id,number,concluded) SELECT id,'P',false FROM company");
            s.executeUpdate("INSERT INTO result_unit(company_id,number) SELECT id,'R' FROM company");
        }
        c.setAutoCommit(false);
    }

    private static SSInvoice empty(boolean credit) {
        SSInvoice result = credit ? SSCreditInvoice.forPersistence() : SSInvoice.forPersistence();
        result.setStoredTaxRates(null, null, null);
        return result;
    }

    private static SSInvoice full(boolean credit, Integer number) {
        SSInvoice result = empty(credit);
        result.setNumber(number);
        if (credit) ((SSCreditInvoice) result).setCreditingNr(88);
        result.setLocalDate(LocalDate.of(2026, 10, 1));
        result.setLocalDueDate(LocalDate.of(2026, 11, 1));
        result.setCustomerNr("C1"); result.setCustomerName("Customer");
        result.setOurContactPerson("Us"); result.setYourContactPerson("Them");
        result.setDelayInterest(new BigDecimal("7.25"));
        SSAddress address = new SSAddress(); address.setName("Snapshot");
        address.setAddress1("Street"); address.setAddress2("Floor"); address.setZipCode("12345");
        address.setCity("Town"); address.setCountry("SE");
        result.setInvoiceAddress(address); result.setDeliveryAddress(new SSAddress(address));
        result.getDefaultAccounts().put(SSDefaultAccount.Sales, null);
        result.setStoredTaxRates(new BigDecimal("25"), null, new BigDecimal("6"));
        result.setTaxFree(true); result.setEuSaleCommodity(true); result.setEuSaleYhirdPartCommodity(true);
        result.setPrinted(true); result.setText("Text"); result.setType(SSInvoiceType.NORMAL);
        result.setYourOrderNumber("external"); result.setOCRNumber("123");
        result.setNumRemainders(3); result.setInterestInvoiced(true);
        result.setStockInfluencing(true); result.setOrderNumbers("9, 10");
        for (int i = 0; i < 2; i++) {
            SSSaleRow row = new SSSaleRow(); row.setDescription(i == 0 ? "Line" : null);
            row.setQuantity(new BigDecimal("2.125")); row.setUnitprice(new BigDecimal("3.456"));
            row.setDiscount(new BigDecimal("1.2")); row.setTaxCode(null); row.setAccountNr(3010);
            row.setProjectNr("P"); row.setResultUnitNr("R"); row.setProductNr("historical");
            result.getRows().add(row);
        }
        result.setVoucher(voucher());
        return result;
    }

    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher(44, LocalDate.of(2026, 10, 1));
        voucher.setDescription("Stored voucher");
        SSVoucherRow row = new SSVoucherRow(); row.setAccountNr(3010);
        row.setProjectNr("P"); row.setResultUnitNr("R"); row.setDebet(new BigDecimal("20.25"));
        row.setLocalEditedDate(LocalDateTime.of(2026, 10, 25, 2, 30));
        row.setEditedSignature("sig"); row.setCrossed(true); row.setAdded(true);
        voucher.getRows().add(row);
        return voucher;
    }

    private static long add(NormalizedInvoiceStore store, boolean credit, int company, SSInvoice value)
            throws SQLException {
        return credit ? store.addCreditInvoice(company, (SSCreditInvoice) value) : store.addInvoice(company, value);
    }
    private static void update(NormalizedInvoiceStore store, boolean credit, int company, long id, SSInvoice value)
            throws SQLException {
        if (credit) store.updateCreditInvoice(company, id, (SSCreditInvoice) value);
        else store.updateInvoice(company, id, value);
    }
    private static void delete(NormalizedInvoiceStore store, boolean credit, int company, long id) throws SQLException {
        if (credit) store.deleteCreditInvoice(company, id); else store.deleteInvoice(company, id);
    }
    private static void mark(NormalizedInvoiceStore store, boolean credit, int number, SSVoucher voucher)
            throws SQLException {
        if (credit) store.markCreditInvoiceEntered(71, number, voucher);
        else store.markInvoiceEntered(71, number, voucher);
    }
    private static NormalizedInvoiceStore.Stored<? extends SSInvoice> stored(NormalizedInvoiceStore store,
            boolean credit, int company, long id) throws SQLException {
        return credit ? store.findCreditInvoice(company, id).orElseThrow()
                : store.findInvoice(company, id).orElseThrow();
    }
    private static SemanticFingerprint fingerprint(Connection c, boolean credit)
            throws SQLException {
        return credit ? new CustomerCreditInvoiceStagingConverter().fingerprintNormalized(c)
                : new CustomerInvoiceStagingConverter().fingerprintNormalized(c);
    }

    private static void reference(Connection c, String ref, int company, int number) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (ref.equals("credit")) {
                SSCreditInvoice credit = SSCreditInvoice.forPersistence();
                credit.setNumber(90); credit.setCreditingNr(number);
                new NormalizedInvoiceStore(c).addCreditInvoice(company, credit);
            } else if (ref.equals("order")) {
                se.swedsoft.bookkeeping.data.SSOrder order = se.swedsoft.bookkeeping.data.SSOrder.forPersistence();
                order.setNumber(90); order.setInvoiceNr(number);
                new NormalizedSaleDocumentStore(c).addOrder(company, order);
            } else {
                s.executeUpdate("INSERT INTO inpayment(legacy_id,company_id,number,entered) SELECT 901,id,90,false FROM company"
                        + " WHERE legacy_id=" + company);
                s.executeUpdate("INSERT INTO inpayment_row(inpayment_id,row_number,invoice_number,value)"
                        + " SELECT id,0," + number + ",0 FROM inpayment WHERE legacy_id=901");
            }
        }
    }

    private static final class LegacyGuard implements AutoCloseable {
        private final Map<Field, Object> saved = new HashMap<>();
        LegacyGuard() throws Exception {
            for (String name : new String[]{"iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers"}) {
                Field field = SSDB.class.getDeclaredField(name); field.setAccessible(true);
                saved.put(field, field.get(SSDB.getInstance()));
            }
            for (Field field : saved.keySet()) field.set(SSDB.getInstance(), null);
            Field field = SSDB.class.getDeclaredField("iConnection"); field.setAccessible(true);
            field.set(SSDB.getInstance(), Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        throw new AssertionError("Legacy JDBC accessed: " + method.getName());
                    }));
        }
        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(SSDB.getInstance(), entry.getValue());
        }
    }
}
