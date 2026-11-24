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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.RawRow;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class ExplicitVatDeclarationPolicyTest {
    private static VatService service(List<SSAccount> accounts, SSVoucher... vouchers) {
        return new VatService(
                year(accounts, vouchers),
                v -> {
                    throw new AssertionError("declaration calculation must not write");
                });
    }

    private static BigDecimal box(VatReport r, int number) {
        return r.boxes().stream()
                .filter(b -> b.number() == number)
                .findFirst()
                .orElseThrow()
                .amount();
    }

    private static BigDecimal signedControl(VatSettlementPlan plan) {
        BigDecimal value = BigDecimal.ZERO;
        for (var row : plan.voucher().getRows()) {
            if (row.getAccountNr() == 2650 && row.getCredit() != null)
                value = value.add(row.getCredit());
            if (row.getAccountNr() == 1650 && row.getDebet() != null)
                value = value.subtract(row.getDebet());
        }
        return value;
    }

    private static void balanced(VatSettlementPlan plan, String expected) {
        money(expected, plan.report().vatToPayOrRefund());
        money(expected, signedControl(plan));
        money("0", debit(plan.voucher()).subtract(credit(plan.voucher())));
        assertEquals(0, plan.voucher().getNumber());
        assertEquals(DAY, plan.voucher().getLocalDate());
    }

    @Test
    void sixAuditCounterexamplesAgreeUnderNamedPolicyWithoutChangingCompatibilityTest() {
        String[][] examples = {
            {"U1", "0.99", "0.99", "0", "1"},
            {"U1", "-0.99", "-0.99", "0", "-1"},
            {"U2", "0.99", "0.99", "0", "0"},
            {"I", "12.90", "5.99", "7", "7"},
            {"I", "5.99", "12.90", "-7", "-7"},
            {"U1", "1.90", "-0.95", "1", "0"}
        };
        for (var e : examples) {
            var a = new ArrayList<>(accounts());
            int second = e[0].equals("I") ? 2640 : 2611;
            if (second != 2640) a.add(account(second, e[0]));
            var source =
                    voucher(
                            DAY,
                            new RawRow(2610, null, e[1]),
                            new RawRow(
                                    second,
                                    second == 2640 ? e[2] : null,
                                    second == 2640 ? null : e[2]));
            var s = service(a, source);
            money(e[3], s.report(DAY, DAY).vatToPayOrRefund());
            var old = s.plan(DAY, DAY);
            balanced(s.declarationPlan(DAY, DAY), e[4]);
            money(e[3], s.report(DAY, DAY).vatToPayOrRefund());
            assertEquals(old.report(), s.plan(DAY, DAY).report());
            money(e[1], source.getRows().get(0).getCredit());
            assertEquals(2, source.getRows().size());
        }
    }

    @Test
    void everyTaxCodeIsClearedExactlyAndAllDeclaredFieldsDriveControlTest() {
        String[] codes = {
            "U1", "UVL", "U2", "U3", "U1MI", "UEU", "UTFU", "U2MI", "U3MI", "UI1", "UI2", "UI3",
            "I", "IVL"
        };
        var a = new ArrayList<>(accounts().subList(2, 5));
        var rows = new ArrayList<SSVoucherRow>();
        for (int i = 0; i < codes.length; i++) {
            for (int j = 0; j < 2; j++) {
                int n = 4000 + i * 2 + j;
                a.add(account(n, codes[i]));
                boolean input = codes[i].equals("I") || codes[i].equals("IVL");
                rows.add(new RawRow(n, input ? "0.99" : null, input ? null : "0.99"));
            }
        }
        var source = voucher(DAY, rows.toArray(SSVoucherRow[]::new));
        var plan = service(a, source).declarationPlan(DAY, DAY);
        balanced(plan, "12");
        money("3", box(plan.report(), 10));
        money("5", box(plan.report(), 30));
        money("3", box(plan.report(), 48));
        assertEquals(30, plan.voucher().getRows().size());
        for (int i = 0; i < rows.size(); i++) {
            var reversal = plan.voucher().getRows().get(i);
            assertEquals(rows.get(i).getAccountNr(), reversal.getAccountNr());
            money("0.99", reversal.getDebet() == null ? reversal.getCredit() : reversal.getDebet());
            assertEquals(rows.get(i).getCredit() == null, reversal.getDebet() == null);
        }
    }

    @Test
    void taxBaseFieldsAreAggregatedButNeverClearedAsTaxAccountsTest() {
        String[] codes = {
            "MP1", "MP2", "MP3", "PTOG", "VFEU", "TFEU", "TFFU", "IBU", "IBU1", "IBU2", "IBU3"
        };
        var a = new ArrayList<>(accounts());
        var rows = new ArrayList<SSVoucherRow>();
        for (int i = 0; i < codes.length; i++) {
            a.add(account(4000 + i, codes[i]));
            rows.add(new RawRow(4000 + i, null, "0.99"));
        }
        var s = service(a, voucher(DAY, rows.toArray(SSVoucherRow[]::new)));
        var plan = s.declarationPlan(DAY, DAY);
        balanced(plan, "0");
        money("3", box(plan.report(), 5));
        money("3", box(plan.report(), 50));
        assertTrue(plan.voucher().getRows().isEmpty());
        money("0", box(s.report(DAY, DAY), 5));
    }

    @Test
    void zeroExactNetCanStillRequireDeclaredControlAndAnOpposingRoundingRowTest() {
        var a = new ArrayList<>(accounts());
        a.add(account(2620, "U2"));
        a.add(account(2630, "U3"));
        var plan =
                service(
                                a,
                                voucher(
                                        DAY,
                                        new RawRow(2610, null, "1.90"),
                                        new RawRow(2620, null, "-0.95"),
                                        new RawRow(2630, null, "-0.95")))
                        .declarationPlan(DAY, DAY);
        balanced(plan, "1");
        assertEquals(5, plan.voucher().getRows().size());
        var rounding = plan.voucher().getRows().get(4);
        assertEquals(3740, rounding.getAccountNr());
        money("1", rounding.getDebet());
    }

    @Test
    void declaredZeroHasNoControlAndFractionalCentsAreNotPrematurelyRoundedTest() {
        var plan =
                service(accounts(), voucher(DAY, new RawRow(2610, null, "0.999")))
                        .declarationPlan(DAY, DAY);
        balanced(plan, "0");
        assertEquals(2, plan.voucher().getRows().size());
        money("0.999", plan.voucher().getRows().get(0).getDebet());
        money("0.999", plan.voucher().getRows().get(1).getCredit());
        assertEquals(3740, plan.voucher().getRows().get(1).getAccountNr());
    }

    @Test
    void periodsRawEligibilityAndSnapshotImmutabilityStayExplicitTest() {
        var a = accounts();
        a.get(0).setActive(false);
        var added = new RawRow(2610, null, "1.99");
        added.setAdded(true);
        var crossed = new RawRow(2610, null, "999");
        crossed.setCrossed(true);
        var empty = new RawRow(2610, null, null);
        var current =
                voucher(
                        DAY,
                        added,
                        crossed,
                        empty,
                        new RawRow(null, null, "999"),
                        new RawRow(9999, null, "999"));
        var s =
                service(
                        a,
                        current,
                        voucher(DAY.minusDays(1), new RawRow(2610, null, "999")),
                        voucher(null, new RawRow(2610, null, "999")));
        var plan = s.declarationPlan(DAY, DAY);
        balanced(plan, "1");
        assertEquals(5, current.getRows().size());
        assertTrue(added.isAdded());
        assertTrue(crossed.isCrossed());
        assertNull(empty.getDebet());
        assertNull(empty.getCredit());
        assertThrows(UnsupportedOperationException.class, () -> plan.report().boxes().clear());
        added.setCredit(new BigDecimal("2.99"));
        money("1", plan.report().vatToPayOrRefund());
        balanced(s.declarationPlan(DAY, DAY), "2");
    }

    @Test
    void rolesMustBeExplicitUniqueAndNeverInferredFromAccountNumbersTest() {
        var missing = new ArrayList<>(accounts());
        missing.get(2).setVATCode(null);
        assertThrows(IllegalStateException.class, () -> service(missing).declarationPlan(DAY, DAY));
        money("0", service(missing).declarationReport(DAY, DAY).vatToPayOrRefund());
        for (String role : List.of("R1", "R2", "A")) {
            var ambiguous = new ArrayList<>(accounts());
            ambiguous.add(account(4999, role));
            assertThrows(
                    IllegalStateException.class,
                    () -> service(ambiguous).declarationPlan(DAY, DAY));
        }
        var remapped = new ArrayList<>(accounts().subList(0, 2));
        remapped.addAll(List.of(account(1700, "R1"), account(2700, "R2"), account(3700, "A")));
        var plan =
                service(remapped, voucher(DAY, new RawRow(2610, null, "1.99")))
                        .declarationPlan(DAY, DAY);
        assertEquals(2700, plan.voucher().getRows().get(1).getAccountNr());
        assertEquals(3700, plan.voucher().getRows().get(2).getAccountNr());
    }

    @Test
    void malformedExplicitInputsAndLegacyContextAreRejectedWithoutWritesTest() {
        var s = service(accounts());
        assertThrows(IllegalArgumentException.class, () -> s.declarationReport(null, DAY));
        assertThrows(
                IllegalArgumentException.class, () -> s.declarationPlan(DAY, DAY.minusDays(1)));
        var duplicate = new ArrayList<>(accounts());
        duplicate.add(account(2610, "U2"));
        assertThrows(
                IllegalArgumentException.class, () -> service(duplicate).declarationPlan(DAY, DAY));
        var invalid = new ArrayList<>(accounts());
        invalid.add(account(4999, "U2"));
        var invalidService = service(invalid);
        invalid.get(5).setNumber(null);
        assertThrows(
                IllegalArgumentException.class, () -> invalidService.declarationReport(DAY, DAY));
        var legacy = new VatService(SSDB.getInstance());
        assertThrows(IllegalStateException.class, () -> legacy.declarationReport(DAY, DAY));
        assertThrows(IllegalStateException.class, () -> legacy.declarationPlan(DAY, DAY));
        var empty = s.declarationPlan(DAY, DAY);
        balanced(empty, "0");
        assertTrue(empty.voucher().getRows().isEmpty());
    }
}
