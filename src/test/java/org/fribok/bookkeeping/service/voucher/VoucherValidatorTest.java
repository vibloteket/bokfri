package org.fribok.bookkeeping.service.voucher;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent voucher validation. */
class VoucherValidatorTest {
    private final SSNewAccountingYear year = year();

    @Test
    void acceptsBalancedVoucherInsideAccountingYear() {
        SSVoucher voucher = voucher();
        voucher.addVoucherRow(row(1930, "100.00", null));
        voucher.addVoucherRow(row(3001, null, "100.00"));

        VoucherValidationResult result = VoucherValidator.validate(voucher, year);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.debitTotal()).isEqualByComparingTo("100.00");
        assertThat(result.creditTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void reportsAllUsefulValidationProblems() {
        SSVoucher voucher = new SSVoucher(0);
        voucher.setLocalDate(LocalDate.of(2025, 12, 31));
        voucher.setDescription(" ");
        voucher.addVoucherRow(row(1930, "100.00", null));
        voucher.addVoucherRow(row(3001, null, "90.00"));

        VoucherValidationResult result = VoucherValidator.validate(voucher, year);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(VoucherValidationIssue::code)
                .contains("DESCRIPTION_REQUIRED", "DATE_OUTSIDE_YEAR", "VOUCHER_NOT_BALANCED");
    }

    @Test
    void rejectsRowsWithBothDebitAndCredit() {
        SSVoucher voucher = voucher();
        voucher.addVoucherRow(row(1930, "100.00", "100.00"));

        VoucherValidationResult result = VoucherValidator.validate(voucher, year);

        assertThat(result.issues()).extracting(VoucherValidationIssue::code)
                .contains("ROW_AMOUNT_INVALID", "VOUCHER_TOTAL_ZERO");
        assertThat(result.issues()).filteredOn(issue -> issue.code().equals("ROW_AMOUNT_INVALID"))
                .extracting(VoucherValidationIssue::row).containsExactly(1);
    }

    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher(0);
        voucher.setLocalDate(LocalDate.of(2026, 8, 2));
        voucher.setDescription("Test");
        return voucher;
    }

    private static SSVoucherRow row(int number, String debit, String credit) {
        SSAccount account = new SSAccount();
        account.setNumber(number);
        SSVoucherRow row = new SSVoucherRow();
        row.setAccount(account);
        row.setDebet(debit == null ? null : new BigDecimal(debit));
        row.setCredit(credit == null ? null : new BigDecimal(credit));
        return row;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2026, 1, 1));
        year.setLocalTo(LocalDate.of(2026, 12, 31));
        return year;
    }
}
