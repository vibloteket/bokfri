package org.fribok.bookkeeping.service.voucher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration coverage for validation and persistence as one use case. */
@Tag("integration")
class VoucherServiceIntegrationTest {
    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @BeforeEach
    void clearCaches() {
        SSDBTestFixture.resetCaches();
    }

    @AfterEach
    void assertNoBackgroundErrors() {
        SSDBTestFixture.drainUncaughtExceptions();
    }

    @Test
    void validVoucherIsNumberedAndPersisted() {
        VoucherService service = new VoucherService(SSDB.getInstance());
        SSVoucher voucher = validVoucher();
        int expectedNumber = service.nextNumber();

        service.create(voucher);

        try {
            SSDBTestFixture.resetCaches();
            assertThat(voucher.getNumber()).isEqualTo(expectedNumber);
            assertThat(SSDB.getInstance().getVouchers()).extracting(SSVoucher::getNumber)
                    .contains(expectedNumber);
        } finally {
            SSDB.getInstance().deleteVoucher(voucher);
        }
    }

    @Test
    void listIsSortedAndFindReturnsVoucherByNumber() {
        VoucherService service = new VoucherService(SSDB.getInstance());
        SSVoucher voucher = validVoucher();
        service.create(voucher);

        try {
            SSDBTestFixture.resetCaches();
            assertThat(service.list()).extracting(SSVoucher::getNumber).isSorted();
            assertThat(service.find(voucher.getNumber())).isPresent()
                    .get().extracting(SSVoucher::getDescription)
                    .isEqualTo("Voucher service integration test");
            assertThat(service.find(Integer.MAX_VALUE)).isEmpty();
        } finally {
            SSDB.getInstance().deleteVoucher(voucher);
        }
    }

    @Test
    void invalidVoucherIsNotPersisted() {
        VoucherService service = new VoucherService(SSDB.getInstance());
        int countBefore = SSDB.getInstance().getVouchers().size();
        SSVoucher voucher = validVoucher();
        voucher.getRows().get(1).setCredit(new BigDecimal("99.00"));

        assertThatThrownBy(() -> service.create(voucher))
                .isInstanceOf(VoucherValidationException.class);

        SSDBTestFixture.resetCaches();
        assertThat(SSDB.getInstance().getVouchers()).hasSize(countBefore);
    }

    private static SSVoucher validVoucher() {
        SSVoucher voucher = new SSVoucher(0);
        voucher.setLocalDate(SSDB.getInstance().getCurrentYear().getLocalFrom());
        voucher.setDescription("Voucher service integration test");
        SSAccount first = SSDB.getInstance().getAccounts().get(0);
        SSAccount second = SSDB.getInstance().getAccounts().get(1);
        voucher.addVoucherRow(row(first, new BigDecimal("42.00"), null));
        voucher.addVoucherRow(row(second, null, new BigDecimal("42.00")));
        return voucher;
    }

    private static SSVoucherRow row(SSAccount account, BigDecimal debit, BigDecimal credit) {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccount(account);
        row.setDebet(debit);
        row.setCredit(credit);
        return row;
    }
}
