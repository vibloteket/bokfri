package org.fribok.bookkeeping.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.calc.util.SSVATUtil;
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

class ExplicitVatSettlementIsolationTest {
    static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    static final class RawRow extends SSVoucherRow {
        RawRow(Integer number, String debit, String credit) {
            setAccountNr(number);
            setDebet(debit == null ? null : new BigDecimal(debit));
            setCredit(credit == null ? null : new BigDecimal(credit));
        }

        @Override
        public SSAccount getAccount() {
            throw new AssertionError("ambient account getter");
        }

        @Override
        public SSAccount getAccount(List<SSAccount> accounts) {
            throw new AssertionError("mutating account getter");
        }

        @Override
        public boolean isValid() {
            throw new AssertionError("ambient validity getter");
        }
    }

    static SSAccount account(int number, String code) {
        var a = new SSAccount(number);
        a.setVATCode(code);
        return a;
    }

    static SSNewAccountingYear year(List<SSAccount> accounts, SSVoucher... vouchers) {
        var y = new SSNewAccountingYear();
        var p = new SSAccountPlan();
        p.setAccounts(new ArrayList<>(accounts));
        y.setAccountPlan(p);
        y.setVouchers(List.of(vouchers));
        return y;
    }

    static SSVoucher voucher(LocalDate day, SSVoucherRow... rows) {
        var v = new SSVoucher(7, day);
        v.setVoucherRows(new ArrayList<>(List.of(rows)));
        return v;
    }

    static List<SSAccount> accounts() {
        return List.of(
                account(2610, "U1"),
                account(2640, "I"),
                account(1650, "R1"),
                account(2650, "R2"),
                account(3740, "A"));
    }

    static BigDecimal debit(SSVoucher v) {
        return v.getRows().stream()
                .map(r -> r.getDebet() == null ? BigDecimal.ZERO : r.getDebet())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal credit(SSVoucher v) {
        return v.getRows().stream()
                .map(r -> r.getCredit() == null ? BigDecimal.ZERO : r.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void payableAndRefundPlansUseOnlyRawNumbersAndRemainUnnumberedUntilWriterTest()
            throws Exception {
        for (boolean refund : List.of(false, true)) {
            var a = accounts();
            var source =
                    voucher(
                            DAY,
                            new RawRow(2610, null, refund ? "10.25" : "25.75"),
                            new RawRow(2640, refund ? "25.75" : "10.25", null));
            var writes = new AtomicInteger();
            var service =
                    new VatService(
                            year(a, source),
                            v -> {
                                assertEquals(0, v.getNumber());
                                writes.incrementAndGet();
                                v.setNumber(42);
                            });
            var plan = service.plan(DAY, DAY);
            assertEquals(0, plan.voucher().getNumber());
            assertEquals(DAY, plan.voucher().getLocalDate());
            assertEquals(4, plan.voucher().getRows().size());
            assertEquals(refund ? 1650 : 2650, plan.voucher().getRows().get(2).getAccountNr());
            money(
                    "15",
                    refund
                            ? plan.voucher().getRows().get(2).getDebet()
                            : plan.voucher().getRows().get(2).getCredit());
            money(
                    "0.50",
                    refund
                            ? plan.voucher().getRows().get(3).getDebet()
                            : plan.voucher().getRows().get(3).getCredit());
            assertEquals(0, debit(plan.voucher()).compareTo(credit(plan.voucher())));
            assertEquals(0, writes.get());
            assertEquals(42, service.commit(plan).voucherNumber());
            assertEquals(1, writes.get());
        }
    }

    @Test
    void directExplicitGeneratorSkipsLegacyDefaultsAndUsesSuppliedRolesTest() {
        var a = accounts();
        var rows =
                List.of(
                        voucher(
                                DAY,
                                new RawRow(2610, null, "25.75"),
                                new RawRow(2640, "10.25", null)));
        var result =
                SSVATUtil.generateVATVoucher(
                        "Ignored legacy name", DAY, DAY, a.get(2), a.get(3), a.get(4), a, rows);
        assertEquals(0, result.getNumber());
        assertEquals(DAY, result.getLocalDate());
        assertNotNull(result.getDescription());
        assertEquals(2650, result.getRows().get(2).getAccountNr());
        money("25.75", debit(result));
        money("25.75", credit(result));
    }

    @Test
    void settlementAggregateTruncationIsNotSilentlyChangedToPerAccountReportTruncationTest() {
        var a = new ArrayList<>(accounts());
        a.add(account(2611, "U1"));
        var source = voucher(DAY, new RawRow(2610, null, "0.99"), new RawRow(2611, null, "0.99"));
        var plan = new VatService(year(a, source), v -> {}).plan(DAY, DAY);
        money("0", plan.report().vatToPayOrRefund());
        money("1", plan.voucher().getRows().get(2).getCredit());
        money("0.98", plan.voucher().getRows().get(3).getCredit());
        money("1.98", debit(plan.voucher()));
        money("1.98", credit(plan.voucher()));
    }

    @Test
    void historicalEligibilityPeriodAndSourceRowsRemainUnchangedTest() {
        var a = accounts();
        a.get(0).setActive(false);
        var added = new RawRow(2610, null, "10.25");
        added.setAdded(true);
        var crossed = new RawRow(2610, null, "999");
        crossed.setCrossed(true);
        var empty = new RawRow(2610, null, null);
        var unknown = new RawRow(9999, null, "999");
        var nullable = new RawRow(null, null, "999");
        var eligible = voucher(DAY, added, crossed, empty, unknown, nullable);
        var y =
                year(
                        a,
                        eligible,
                        voucher(DAY.minusDays(1), new RawRow(2610, null, "999")),
                        voucher(null, new RawRow(2610, null, "999")));
        var plan = new VatService(y, v -> {}).plan(DAY, DAY);
        money("10.25", plan.voucher().getRows().get(0).getDebet());
        assertEquals(2610, added.getAccountNr());
        money("10.25", added.getCredit());
        assertTrue(added.isAdded());
        assertTrue(crossed.isCrossed());
        assertNull(empty.getDebet());
        assertNull(empty.getCredit());
        assertEquals(5, eligible.getRows().size());
    }

    @Test
    void netZeroClearsBothVatAccountsWithoutInventingControlOrRoundingRowsTest() {
        var y =
                year(
                        accounts(),
                        voucher(
                                DAY,
                                new RawRow(2610, null, "10.25"),
                                new RawRow(2640, "10.25", null)));
        var plan = new VatService(y, v -> {}).plan(DAY, DAY);
        assertEquals(2, plan.voucher().getRows().size());
        money("10.25", debit(plan.voucher()));
        money("10.25", credit(plan.voucher()));
        assertTrue(
                plan.voucher().getRows().stream()
                        .noneMatch(r -> List.of(1650, 2650, 3740).contains(r.getAccountNr())));
    }

    @Test
    void emptyAndInvalidPeriodsNeverInvokeExplicitWriterTest() throws Exception {
        var writes = new AtomicInteger();
        var service = new VatService(year(accounts()), v -> writes.incrementAndGet());
        var empty = service.plan(DAY, DAY);
        assertEquals(0, empty.voucher().getNumber());
        assertTrue(empty.voucher().getRows().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.commit(empty));
        assertThrows(IllegalArgumentException.class, () -> service.plan(DAY, DAY.minusDays(1)));
        assertEquals(0, writes.get());
    }
}
