package se.swedsoft.bookkeeping.importexport.sie;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SIERoundingTest {
    @Test
    void balancedTwoDecimalAmountsNeedNoAdjustment() {
        SSVoucher voucher = new SSVoucher();
        voucher.addVoucherRow(new SSAccount(1930), new BigDecimal("0.17999999999999999"), null);
        voucher.addVoucherRow(new SSAccount(8311), null, new BigDecimal("0.17999999999999999"));

        assertThat(SIERounding.voucherAdjustment(voucher)).isZero();
    }

    @Test
    void separatelyRoundedRowsGetBalancingAdjustment() {
        SSVoucher voucher = new SSVoucher();
        voucher.addVoucherRow(new SSAccount(1930), new BigDecimal("100.004"), null);
        voucher.addVoucherRow(new SSAccount(1940), new BigDecimal("50.004"), null);
        voucher.addVoucherRow(new SSAccount(2099), null, new BigDecimal("150.008"));

        assertThat(SIERounding.voucherAdjustment(voucher)).isEqualByComparingTo("0.01");
    }
}
