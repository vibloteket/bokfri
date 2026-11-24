package org.fribok.bookkeeping.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

class ExplicitVatReportServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    private static SSAccount account(int n, String code) {
        var a = new SSAccount(n);
        a.setVATCode(code);
        return a;
    }

    private static final class StrictRow extends SSVoucherRow {
        StrictRow(Integer number, String debit, String credit) {
            setAccountNr(number);
            if (debit != null) setDebet(new BigDecimal(debit));
            if (credit != null) setCredit(new BigDecimal(credit));
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

    private static SSNewAccountingYear year(List<SSAccount> accounts, List<SSVoucherRow> rows) {
        var p = new SSAccountPlan();
        p.setAccounts(new ArrayList<>(accounts));
        var y = new SSNewAccountingYear();
        y.setAccountPlan(p);
        var v = new SSVoucher();
        v.setLocalDate(DAY);
        v.setVoucherRows(new ArrayList<>(rows));
        y.setVouchers(List.of(v));
        return y;
    }

    private static VatService service(SSNewAccountingYear y) {
        return new VatService(
                y,
                v -> {
                    throw new AssertionError("report must not write");
                });
    }

    private static BigDecimal box(VatReport r, int n) {
        return r.boxes().stream().filter(b -> b.number() == n).findFirst().orElseThrow().amount();
    }

    @Test
    void strictRawRowsResolveOnlyExplicitNumbersAndPreservePerAccountTruncationTest() {
        var out = account(2610, "U1");
        var second = account(2611, "U1");
        var input = account(2640, "I");
        var ignored = account(1930, null);
        var y =
                year(
                        List.of(out, second, input, ignored),
                        List.of(
                                new StrictRow(2610, null, "12.90"),
                                new StrictRow(2611, null, "12.90"),
                                new StrictRow(2640, "5.99", null),
                                new StrictRow(1930, "1000", null)));
        var r = service(y).report(DAY, DAY);
        assertEquals(16, r.boxes().size());
        assertEquals(0, new BigDecimal("24").compareTo(box(r, 10)));
        assertEquals(0, new BigDecimal("5").compareTo(box(r, 48)));
        assertEquals(0, new BigDecimal("19").compareTo(r.vatToPayOrRefund()));
        assertThrows(UnsupportedOperationException.class, () -> r.boxes().clear());
    }

    @Test
    void invalidCrossedEmptyAndUndatedRowsAreExcludedButAddedInactiveRowsRemainReportableTest() {
        var a = account(2610, "U1");
        a.setActive(false);
        var added = new StrictRow(2610, null, "-2.99");
        added.setAdded(true);
        var crossed = new StrictRow(2610, null, "100");
        crossed.setCrossed(true);
        var y =
                year(
                        List.of(a),
                        List.of(
                                added,
                                crossed,
                                new StrictRow(null, null, "100"),
                                new StrictRow(9999, null, "100"),
                                new StrictRow(2610, null, null)));
        var undated = new SSVoucher();
        undated.setLocalDate(null);
        undated.setVoucherRows(new ArrayList<>(List.of(new StrictRow(2610, null, "100"))));
        y.setVouchers(List.of(y.getVouchers().get(0), undated));
        assertEquals(0, new BigDecimal("-2").compareTo(box(service(y).report(DAY, DAY), 10)));
        assertEquals(BigDecimal.ZERO, box(service(y).report(DAY.plusDays(1), DAY.plusDays(1)), 10));
    }

    @Test
    void everyCodeGroupAndNetFormulaStayAsExistingVatRulesTest() {
        var accounts = new ArrayList<SSAccount>();
        var rows = new ArrayList<SSVoucherRow>();
        String[] codes = {
            "MP1", "MP2", "MP3", "PTOG", "U1", "UVL", "U2", "U3", "VFEU", "TFEU", "TFFU", "U1MI",
            "UEU", "UTFU", "U2MI", "U3MI", "I", "IVL", "IBU", "IBU1", "IBU2", "IBU3", "UI1", "UI2",
            "UI3", "R1", "R2", "UNKNOWN"
        };
        for (int i = 0; i < codes.length; i++) {
            accounts.add(account(1000 + i, codes[i]));
            rows.add(new StrictRow(1000 + i, "0", "1.99"));
        }
        var r = service(year(accounts, rows)).report(DAY, DAY);
        for (var b : r.boxes()) {
            int expected =
                    switch (b.number()) {
                        case 5, 50 -> 4;
                        case 10 -> 2;
                        case 30 -> 3;
                        case 20, 21, 22 -> -1;
                        case 48 -> -2;
                        case 49 -> 14;
                        default -> 1;
                    };
            assertEquals(
                    0, BigDecimal.valueOf(expected).compareTo(b.amount()), "box " + b.number());
        }
    }

    @Test
    void
            duplicateOrUnknownPlanNumbersAndMalformedPeriodsFailInsteadOfResolvingAmbientDefaultsTest() {
        var duplicate = year(List.of(account(2610, "U1"), account(2610, "I")), List.of());
        assertThrows(IllegalArgumentException.class, () -> service(duplicate).report(DAY, DAY));
        var y = year(List.of(account(2610, "U1")), List.of());
        assertThrows(IllegalArgumentException.class, () -> service(y).report(null, DAY));
        assertThrows(
                IllegalArgumentException.class, () -> service(y).report(DAY, DAY.minusDays(1)));
        y.getAccountPlan().getAccounts().get(0).setNumber(null);
        assertThrows(IllegalArgumentException.class, () -> service(y).report(DAY, DAY));
    }
}
