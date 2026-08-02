package org.fribok.bookkeeping.service.reference;

import org.fribok.bookkeeping.service.customer.CustomerService;
import org.fribok.bookkeeping.service.invoice.InvoiceService;
import org.fribok.bookkeeping.service.product.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration coverage for read-only CLI-facing application services. */
@Tag("integration")
class ReferenceServicesIntegrationTest {
    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @BeforeEach
    void resetCaches() {
        SSDBTestFixture.resetCaches();
    }

    @AfterEach
    void assertNoBackgroundErrors() {
        SSDBTestFixture.drainUncaughtExceptions();
    }

    @Test
    void customerServiceListsAndFindsCustomers() {
        CustomerService service = new CustomerService(SSDB.getInstance());

        assertThat(service.list()).extracting(SSCustomer::getNumber).isSortedAccordingTo(
                java.util.Comparator.nullsLast(String::compareTo));
        if (!service.list().isEmpty()) {
            SSCustomer customer = service.list().get(0);
            assertThat(service.find(customer.getNumber())).contains(customer);
        }
        assertThat(service.find("__missing_customer__")).isEmpty();
    }

    @Test
    void productServiceListsAndFindsProducts() {
        ProductService service = new ProductService(SSDB.getInstance());

        assertThat(service.list()).extracting(SSProduct::getNumber).isSortedAccordingTo(
                java.util.Comparator.nullsLast(String::compareTo));
        if (!service.list().isEmpty()) {
            SSProduct product = service.list().get(0);
            assertThat(service.find(product.getNumber())).contains(product);
        }
        assertThat(service.find("__missing_product__")).isEmpty();
    }

    @Test
    void invoiceServiceListsAndFindsInvoices() {
        InvoiceService service = new InvoiceService(SSDB.getInstance());

        assertThat(service.list(null, null)).extracting(SSInvoice::getNumber)
                .isSortedAccordingTo(java.util.Comparator.nullsLast(Integer::compareTo));
        if (!service.list(null, null).isEmpty()) {
            SSInvoice invoice = service.list(null, null).get(0);
            assertThat(service.find(invoice.getNumber())).contains(invoice);
        }
        assertThat(service.find(Integer.MAX_VALUE)).isEmpty();
    }
}
