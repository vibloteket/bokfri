package org.fribok.bookkeeping.service.vat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VatExplicitInputsTest {
    @Test
    void payableAndRefundPlansUseExplicitInputsAndPreserveRounding() throws Exception {
        for (boolean refund : List.of(false, true)) {
            SSAccount output = account(2611, "U1");
            SSAccount input = account(2641, "I");
            SSAccount receivable = account(1650, "R1");
            SSAccount payable = account(2650, "R2");
            SSAccount rounding = account(3740, "A");
            SSAccount cash = account(1930, null);
            SSAccountPlan accounts = new SSAccountPlan();
            accounts.setAccounts(new ArrayList<>(List.of(output, input, receivable, payable, rounding, cash)));
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setAccountPlan(accounts);
            LocalDate date = LocalDate.of(2026, 1, 15);
            SSVoucher source = new SSVoucher();
            source.setLocalDate(date);
            source.addVoucherRow(row(output, refund ? "-10.25" : "-25.75"));
            source.addVoucherRow(row(input, refund ? "25.75" : "10.25"));
            source.addVoucherRow(row(cash, refund ? "-15.50" : "15.50"));
            // An out-of-period voucher must not affect the report or settlement.
            SSVoucher excluded = new SSVoucher();
            excluded.setLocalDate(date.minusYears(1));
            excluded.addVoucherRow(row(output, "-1000.00"));
            excluded.addVoucherRow(row(cash, "1000.00"));
            year.setVouchers(List.of(source, excluded));
            AtomicInteger writes = new AtomicInteger();
            VatService service = new VatService(year, voucher -> {
                writes.incrementAndGet();
                voucher.setNumber(42);
            });
            VatSettlementPlan plan = service.plan(date, date);
            assertThat(writes.get()).isZero();
            // Report truncates each account as in the legacy service (25 - 10).
            assertThat(plan.report().vatToPayOrRefund())
                    .isEqualByComparingTo(refund ? "-15" : "15");
            assertThat(SSVoucherMath.getDebetSum(plan.voucher()))
                    .isEqualByComparingTo(SSVoucherMath.getCreditSum(plan.voucher()));
            assertThat(plan.voucher().getRows()).hasSize(4);
            assertThat(plan.voucher().getRows().get(2).getAccount())
                    .isEqualTo(refund ? receivable : payable);
            assertThat(refund ? plan.voucher().getRows().get(2).getDebet()
                    : plan.voucher().getRows().get(2).getCredit()).isEqualByComparingTo("15");
            assertThat(plan.voucher().getRows().get(3).getAccount()).isEqualTo(rounding);
            assertThat(refund ? plan.voucher().getRows().get(3).getDebet()
                    : plan.voucher().getRows().get(3).getCredit()).isEqualByComparingTo("0.50");
            assertThat(service.commit(plan).voucherNumber()).isEqualTo(42);
            assertThat(writes.get()).isEqualTo(1);
            assertThatThrownBy(() -> service.report(date, date.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            VatSettlementPlan empty = service.plan(date.plusDays(1), date.plusDays(1));
            assertThatThrownBy(() -> service.commit(empty)).isInstanceOf(IllegalArgumentException.class);
            assertThat(writes.get()).isEqualTo(1);
        }
    }

    private static SSAccount account(int number, String code) {
        SSAccount account = new SSAccount(number);
        account.setVATCode(code);
        return account;
    }

    private static SSVoucherRow row(SSAccount account, String value) {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccount(account);
        row.setValue(new BigDecimal(value));
        return row;
    }
}
