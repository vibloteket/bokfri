package se.swedsoft.bookkeeping.data.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.calc.math.SSCustomerMath;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads after a save must not depend on when the HSQLDB trigger thread runs. */
@Tag("integration")
class SSImmediateCacheIntegrationTest {
    @BeforeAll
    static void setup() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @BeforeEach
    void reset() {
        SSDBTestFixture.resetCaches();
    }

    @AfterEach
    void backgroundErrors() {
        SSDBTestFixture.drainUncaughtExceptions();
    }

    @Test
    void voucherCreateEditDeleteImmediatelyRefreshesWarmCache() {
        SSDB db = SSDB.getInstance();
        SSVoucher voucher = new SSVoucher();
        voucher.setNumber(99001);
        voucher.setLocalDate(LocalDate.now());
        voucher.setDescription("Before edit");
        // Block the queued callback so this tests the save path, not thread scheduling luck.
        synchronized (db) {
            db.getVouchers();
            try {
                db.addVoucher(voucher, true);
                assertThat(db.getVouchers()).extracting(SSVoucher::getNumber).contains(99001);
                voucher.setDescription("After edit");
                db.updateVoucher(voucher);
                assertThat(db.getVouchers().stream().filter(v -> v.getNumber() == 99001).findFirst())
                        .get().extracting(SSVoucher::getDescription).isEqualTo("After edit");
                db.deleteVoucher(voucher);
                assertThat(db.getVouchers()).extracting(SSVoucher::getNumber).doesNotContain(99001);
            } finally {
                db.deleteVoucher(voucher);
            }
        }
    }

    @Test
    void invoiceCreateEditDeleteImmediatelyRefreshesWarmCacheAndDuplicateCallbacksAreSafe() {
        SSDB db = SSDB.getInstance();
        SSInvoice invoice = new SSInvoice();
        invoice.setCustomerNr("FOCUS-REFRESH-TEST");
        invoice.setCustomerName("Before edit");
        invoice.setLocalDate(LocalDate.now());
        invoice.setCurrencyRate(BigDecimal.ONE);
        synchronized (db) {
            db.getInvoices();
            try {
                db.addInvoice(invoice);
                assertThat(db.getInvoices()).extracting(SSInvoice::getNumber).contains(invoice.getNumber());
                db.triggerAction("NEWINVOICE", "TBL_INVOICE", invoice.getNumber().toString());
                assertThat(db.getInvoices().stream().filter(v -> v.getNumber().equals(invoice.getNumber())).count())
                        .isEqualTo(1);
                assertThat(SSCustomerMath.iInvoicesForCustomers.get(invoice.getCustomerNr()).stream()
                        .filter(v -> v.getNumber().equals(invoice.getNumber())).count()).isEqualTo(1);
                invoice.setCustomerName("After edit");
                db.updateInvoice(invoice);
                assertThat(db.getInvoices().stream().filter(v -> v.getNumber().equals(invoice.getNumber())).findFirst())
                        .get().extracting(SSInvoice::getCustomerName).isEqualTo("After edit");
                db.deleteInvoice(invoice);
                assertThat(db.getInvoices()).extracting(SSInvoice::getNumber).doesNotContain(invoice.getNumber());
            } finally {
                db.deleteInvoice(invoice);
            }
        }
    }
}
