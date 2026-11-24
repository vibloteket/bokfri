package org.fribok.bookkeeping.service.vat;

import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.DAY;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.account;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.accounts;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.credit;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.debit;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.money;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.voucher;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.year;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.RawRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

/** Characterizes existing discrepancies; these expectations are not a filing-policy contract. */
class VatRoundingPolicyAuditTest {
    @Test
    void sameFieldAccountSplittingLosesWholeKronorInBothDirectionsTest() {
        for (int sign : new int[] {1, -1}) {
            var split = new ArrayList<>(accounts());
            split.add(account(2611, "U1"));
            String amount = sign == 1 ? "0.99" : "-0.99";
            var source =
                    voucher(DAY, new RawRow(2610, null, amount), new RawRow(2611, null, amount));
            var plan =
                    new VatService(
                                    year(split, source),
                                    v -> {
                                        throw new AssertionError("audit must not write");
                                    })
                            .plan(DAY, DAY);
            money("0", plan.report().vatToPayOrRefund());
            assertEquals(0, BigDecimal.valueOf(sign).compareTo(control(plan)));
            // Independent field-total reference: truncate AFTER adding both account balances.
            var fieldTotal =
                    new BigDecimal(amount)
                            .multiply(BigDecimal.valueOf(2))
                            .setScale(0, RoundingMode.DOWN);
            assertEquals(0, BigDecimal.valueOf(sign).compareTo(fieldTotal));
            money("0", debit(plan.voucher()).subtract(credit(plan.voucher())));
            assertEquals(0, plan.voucher().getNumber());
        }
    }

    @Test
    void differentFieldsMustNotPoolFractionalKronorIntoBox49Test() {
        var selected = new ArrayList<>(accounts());
        selected.add(account(2620, "U2"));
        var plan =
                new VatService(
                                year(
                                        selected,
                                        voucher(
                                                DAY,
                                                new RawRow(2610, null, "0.99"),
                                                new RawRow(2620, null, "0.99"))),
                                v -> {
                                    throw new AssertionError("audit must not write");
                                })
                        .plan(DAY, DAY);
        money("0", plan.report().vatToPayOrRefund());
        money("1", control(plan));
        money("0", debit(plan.voucher()).subtract(credit(plan.voucher())));
    }

    @Test
    void netTruncationDiffersFromSubtractingDeclaredFieldsForPayableAndRefundTest() {
        for (boolean refund : new boolean[] {false, true}) {
            var plan =
                    new VatService(
                                    year(
                                            accounts(),
                                            voucher(
                                                    DAY,
                                                    new RawRow(
                                                            2610, null, refund ? "5.99" : "12.90"),
                                                    new RawRow(
                                                            2640,
                                                            refund ? "12.90" : "5.99",
                                                            null))),
                                    v -> {
                                        throw new AssertionError("audit must not write");
                                    })
                            .plan(DAY, DAY);
            money(refund ? "-7" : "7", plan.report().vatToPayOrRefund());
            money(refund ? "-6" : "6", control(plan));
            money("0", debit(plan.voucher()).subtract(credit(plan.voucher())));
        }
    }

    @Test
    void oppositeSignAccountsInOneFieldCanOverstateInsteadOfUnderstateTest() {
        var selected = new ArrayList<>(accounts());
        selected.add(account(2611, "U1"));
        var plan =
                new VatService(
                                year(
                                        selected,
                                        voucher(
                                                DAY,
                                                new RawRow(2610, null, "1.90"),
                                                new RawRow(2611, null, "-0.95"))),
                                v -> {
                                    throw new AssertionError("audit must not write");
                                })
                        .plan(DAY, DAY);
        money("1", plan.report().vatToPayOrRefund());
        money(
                "0",
                new BigDecimal("1.90")
                        .subtract(new BigDecimal("0.95"))
                        .setScale(0, RoundingMode.DOWN));
        money("0", control(plan));
        money("0", debit(plan.voucher()).subtract(credit(plan.voucher())));
    }

    private static BigDecimal control(VatSettlementPlan plan) {
        BigDecimal result = BigDecimal.ZERO;
        for (var row : plan.voucher().getRows()) {
            if (row.getAccountNr() == 2650 && row.getCredit() != null)
                result = result.add(row.getCredit());
            if (row.getAccountNr() == 1650 && row.getDebet() != null)
                result = result.subtract(row.getDebet());
        }
        return result;
    }
}
