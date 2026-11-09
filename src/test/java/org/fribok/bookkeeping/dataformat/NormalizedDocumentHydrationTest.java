package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.base.SSSale;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedDocumentHydrationTest {
    @Test
    void allSixDocumentKindsLoadWithoutLegacyJdbcOrInventedSnapshots() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:document_hydration_" + UUID.randomUUID(), "sa", "")) {
            connection.setAutoCommit(false);
            Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
            new SchemaMigrationRunner(clock).migrate(connection, NormalizedSchemaMigrations.load());
            SSNewCompany company = new SSNewCompany();
            company.setId(71);
            new NormalizedAccountingWriter(clock).addCompany(connection, company);

            NormalizedInvoiceStore invoices = new NormalizedInvoiceStore(connection);
            NormalizedSupplierInvoiceStore suppliers = new NormalizedSupplierInvoiceStore(connection);
            NormalizedInpaymentStore incoming = new NormalizedInpaymentStore(connection);
            NormalizedOutpaymentStore outgoing = new NormalizedOutpaymentStore(connection);
            try (BlockedLegacyState ignored = new BlockedLegacyState()) {
                SSInvoice invoice = SSInvoice.forPersistence();
                invoice.setNumber(1);
                SSCreditInvoice credit = SSCreditInvoice.forPersistence();
                credit.setNumber(2);
                SSSupplierInvoice supplier = SSSupplierInvoice.forPersistence();
                supplier.setNumber(3);
                SSSupplierCreditInvoice supplierCredit = SSSupplierCreditInvoice.forPersistence();
                supplierCredit.setNumber(4);
                SSInpayment inpayment = SSInpayment.forPersistence();
                inpayment.setNumber(5);
                SSOutpayment outpayment = SSOutpayment.forPersistence();
                outpayment.setNumber(6);
                invoices.addInvoice(71, invoice);
                invoices.addCreditInvoice(71, credit);
                suppliers.addSupplierInvoice(71, supplier);
                suppliers.addSupplierCreditInvoice(71, supplierCredit);
                incoming.addInpayment(71, inpayment);
                outgoing.addOutpayment(71, outpayment);
                connection.commit();

                SSInvoice restoredInvoice = invoices.getInvoices(71).get(0);
                SSCreditInvoice restoredCredit = invoices.getCreditInvoices(71).get(0);
                for (SSInvoice restored : List.of(restoredInvoice, restoredCredit)) {
                    assertSaleEmpty(restored);
                    assertThat(restored.getStoredType()).isNull();
                    assertThat(restored.getCurrencyRate()).isNull();
                    assertThat(restored.getLocalDueDate()).isNull();
                    assertThat(restored.getStoredVoucher()).isNull();
                }
                for (SSSupplierInvoice restored : List.of(suppliers.getSupplierInvoices(71).get(0),
                        suppliers.getSupplierCreditInvoices(71).get(0))) {
                    assertThat(restored.getLocalDate()).isNull();
                    assertThat(restored.getDefaultAccounts()).isEmpty();
                    assertThat(restored.getRows()).isEmpty();
                    assertThat(restored.getVoucher()).isNull();
                    assertThat(restored.getCorrection()).isNull();
                }
                SSInpayment restoredIn = incoming.getInpayments(71).get(0);
                assertThat(restoredIn.getLocalDate()).isNull();
                assertThat(restoredIn.getDefaultAccounts()).isEmpty();
                assertThat(restoredIn.getRows()).isEmpty();
                assertThat(restoredIn.getVoucher()).isNull();
                assertThat(restoredIn.getStoredDifference()).isNull();
                SSOutpayment restoredOut = outgoing.getOutpayments(71).get(0);
                assertThat(restoredOut.getLocalDate()).isNull();
                assertThat(restoredOut.getDefaultAccounts()).isEmpty();
                assertThat(restoredOut.getRows()).isEmpty();
                assertThat(restoredOut.getVoucher()).isNull();
                assertThat(restoredOut.getStoredDifference()).isNull();
                // A second load must not mutate stored absence into synthetic headers.
                assertThat(invoices.getInvoices(71).get(0).getStoredVoucher()).isNull();
                assertThat(incoming.getInpayments(71).get(0).getStoredDifference()).isNull();

                SSVoucher main = new SSVoucher(81, null);
                main.setDescription("Stored main");
                SSVoucher auxiliary = new SSVoucher(82, null);
                auxiliary.setDescription("Stored auxiliary");
                invoices.markInvoiceEntered(71, 1, main);
                invoices.markCreditInvoiceEntered(71, 2, main);
                suppliers.markSupplierInvoiceEntered(71, 3, main);
                suppliers.markSupplierCreditInvoiceEntered(71, 4, main);
                incoming.markEntered(71, 5, main, auxiliary);
                outgoing.markEntered(71, 6, main, auxiliary);
                connection.commit();
                assertSnapshot(invoices.getInvoices(71).get(0).getStoredVoucher(), 81);
                assertSnapshot(invoices.getCreditInvoices(71).get(0).getStoredVoucher(), 81);
                for (SSSupplierInvoice restored : List.of(suppliers.getSupplierInvoices(71).get(0),
                        suppliers.getSupplierCreditInvoices(71).get(0))) {
                    assertSnapshot(restored.getVoucher(), 81);
                    assertThat(restored.getCorrection()).isNull();
                }
                assertSnapshot(incoming.getInpayments(71).get(0).getVoucher(), 81);
                assertSnapshot(incoming.getInpayments(71).get(0).getStoredDifference(), 82);
                assertSnapshot(outgoing.getOutpayments(71).get(0).getVoucher(), 81);
                assertSnapshot(outgoing.getOutpayments(71).get(0).getStoredDifference(), 82);
            }
        }
    }

    @Test
    void legacyDefaultConstructorsStillCreateVoucherSnapshots() throws Exception {
        SSDB db = SSDB.getInstance();
        Map<Field, Object> saved = saveLegacyState(db);
        try {
            set(db, "iCurrentCompany", null);
            set(db, "iCurrentYear", null);
            assertThat(new SSInvoice().getVoucher()).isNotNull();
            assertThat(new SSCreditInvoice().getVoucher()).isNotNull();
            assertThat(new SSSupplierInvoice().getVoucher()).isNotNull();
            assertThat(new SSSupplierCreditInvoice().getVoucher()).isNotNull();
            SSInpayment incoming = new SSInpayment();
            assertThat(incoming.getVoucher()).isNotNull();
            assertThat(incoming.getDifference()).isNotNull();
            SSOutpayment outgoing = new SSOutpayment();
            assertThat(outgoing.getVoucher()).isNotNull();
            assertThat(outgoing.getDifference()).isNotNull();
            assertThat(new SSInvoice().getLocalDate()).isNotNull();
        } finally {
            restore(db, saved);
        }
    }

    @Test
    void copiesPreserveAbsentOptionalStateAndDeepCopyPresentSnapshots() throws Exception {
        SSDB db = SSDB.getInstance();
        Map<Field, Object> saved = saveLegacyState(db);
        try {
            set(db, "iCurrentCompany", null);
            set(db, "iCurrentYear", null);
            SSInvoice invoice = SSInvoice.forPersistence();
            SSInvoice invoiceCopy = new SSInvoice(invoice);
            assertSaleEmpty(invoiceCopy);
            assertThat(invoiceCopy.getStoredVoucher()).isNull();
            SSCreditInvoice credit = SSCreditInvoice.forPersistence();
            SSCreditInvoice creditCopy = SSCreditInvoice.forPersistence();
            creditCopy.copyFrom(credit);
            assertThat(creditCopy.getStoredVoucher()).isNull();
            assertThat(new SSSupplierInvoice(SSSupplierInvoice.forPersistence()).getVoucher()).isNull();
            assertThat(new SSSupplierInvoice(SSSupplierInvoice.forPersistence()).getCorrection()).isNull();
            assertThat(new SSSupplierCreditInvoice(SSSupplierInvoice.forPersistence()).getVoucher()).isNull();
            assertThat(new SSInpayment(SSInpayment.forPersistence()).getVoucher()).isNull();
            assertThat(new SSInpayment(SSInpayment.forPersistence()).getStoredDifference()).isNull();
            assertThat(new SSOutpayment(SSOutpayment.forPersistence()).getVoucher()).isNull();
            assertThat(new SSOutpayment(SSOutpayment.forPersistence()).getStoredDifference()).isNull();

            SSVoucher main = new SSVoucher(81, null);
            main.setDescription("Stored main");
            invoice.setVoucher(main);
            assertCopiedSnapshot(new SSInvoice(invoice).getStoredVoucher(), main);
            credit.setVoucher(main);
            creditCopy.copyFrom(credit);
            assertCopiedSnapshot(creditCopy.getStoredVoucher(), main);
            SSSupplierInvoice supplier = SSSupplierInvoice.forPersistence();
            supplier.setVoucher(main);
            supplier.setCorrection(main);
            SSSupplierInvoice supplierCopy = new SSSupplierInvoice(supplier);
            assertCopiedSnapshot(supplierCopy.getVoucher(), main);
            assertCopiedSnapshot(supplierCopy.getCorrection(), main);
            SSInpayment incoming = SSInpayment.forPersistence();
            incoming.setVoucher(main);
            incoming.setDifference(main);
            SSInpayment incomingCopy = new SSInpayment(incoming);
            assertCopiedSnapshot(incomingCopy.getVoucher(), main);
            assertCopiedSnapshot(incomingCopy.getStoredDifference(), main);
            SSOutpayment outgoing = SSOutpayment.forPersistence();
            outgoing.setVoucher(main);
            outgoing.setDifference(main);
            SSOutpayment outgoingCopy = new SSOutpayment(outgoing);
            assertCopiedSnapshot(outgoingCopy.getVoucher(), main);
            assertCopiedSnapshot(outgoingCopy.getStoredDifference(), main);
        } finally {
            restore(db, saved);
        }
    }

    private static void assertCopiedSnapshot(SSVoucher copy, SSVoucher source) {
        assertSnapshot(copy, 81);
        assertThat(copy).isNotSameAs(source);
        copy.setDescription("Changed copy");
        assertThat(source.getDescription()).isEqualTo("Stored main");
    }

    private static void assertSnapshot(SSVoucher voucher, int number) {
        assertThat(voucher).isNotNull();
        assertThat(voucher.getNumber()).isEqualTo(number);
        assertThat(voucher.getLocalDate()).isNull();
        assertThat(voucher.getDescription()).isEqualTo(number == 81 ? "Stored main" : "Stored auxiliary");
        assertThat(voucher.getRows()).isEmpty();
    }

    private static void assertSaleEmpty(SSSale sale) {
        assertThat(sale.getLocalDate()).isNull();
        assertThat(sale.getStoredTaxRates()).containsExactly(null, null, null);
        assertThat(sale.getDefaultAccounts()).isEmpty();
        assertThat(sale.getRows()).isEmpty();
        assertThat(sale.getInvoiceAddress()).isNull();
        assertThat(sale.getDeliveryAddress()).isNull();
        assertThat(sale.getCurrency()).isNull();
        assertThat(sale.getPaymentTerm()).isNull();
        assertThat(sale.getDeliveryTerm()).isNull();
        assertThat(sale.getDeliveryWay()).isNull();
    }

    private static Map<Field, Object> saveLegacyState(SSDB db) throws Exception {
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
            Field field = SSDB.class.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(db));
        }
        return saved;
    }

    private static void set(SSDB db, String name, Object value) throws Exception {
        Field field = SSDB.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(db, value);
    }

    private static void restore(SSDB db, Map<Field, Object> saved) throws Exception {
        for (var entry : saved.entrySet()) entry.getKey().set(db, entry.getValue());
    }

    private static final class BlockedLegacyState implements AutoCloseable {
        private final SSDB db = SSDB.getInstance();
        private final Map<Field, Object> saved;

        BlockedLegacyState() throws Exception {
            saved = saveLegacyState(db);
            SSNewCompany unrelated = new SSNewCompany();
            unrelated.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 9999);
            set(db, "iCurrentCompany", unrelated);
            set(db, "iCurrentYear", null);
            set(db, "iVouchers", null);
            set(db, "iConnection", Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }

        @Override
        public void close() throws Exception {
            restore(db, saved);
        }
    }
}
