package org.fribok.bookkeeping.service.vat;

import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.DAY;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.accounts;
import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.voucher;
import static org.fribok.bookkeeping.service.vat.VatSettlementDiagnosticsTest.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementDiagnosticsTest.StrictRow;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.util.List;

/** Raw-key grouping is explicit-policy-only; no writer or ambient dimension lookup is allowed. */
class VatSettlementDimensionsTest {
    static StrictRow source(String debit, String credit, String project, String unit) {
        return new StrictRow(2610, debit, credit, project, unit);
    }

    static void money(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    static List<VatSettlementDiagnostics.Finding> findings(
            VatSettlementPlan p, SSVoucher... sources) {
        return VatSettlementDiagnostics.inspect(
                p.report(), accounts(), List.of(sources), p.voucher());
    }

    @Test
    void distinctPairsSurviveZeroAccountNetInFirstOccurrenceOrderTest() {
        var s = voucher(DAY, source(null, "10.25", "P2", "U2"), source("10.25", null, "P1", "U1"));
        var p = plan(accounts(), s);
        assertEquals(2, p.voucher().getRows().size());
        assertEquals(
                List.of("P2", "P1"),
                p.voucher().getRows().stream().map(SSVoucherRow::getProjectNr).toList());
        money("10.25", p.voucher().getRows().get(0).getDebet());
        money("10.25", p.voucher().getRows().get(1).getCredit());
        money("0", p.report().vatToPayOrRefund());
        assertTrue(findings(p, s).isEmpty());
    }

    @Test
    void sameRawPairAggregatesExactlyAcrossVouchersTest() {
        var first = voucher(DAY, source(null, "1.129", "P", "U"));
        var second = voucher(DAY, source(null, "2.881", "P", "U"));
        var p = plan(accounts(), first, second);
        var rows = p.voucher().getRows();
        assertEquals(3, rows.size());
        money("4.010", rows.get(0).getDebet());
        assertEquals("P", rows.get(0).getProjectNr());
        assertEquals("U", rows.get(0).getResultUnitNr());
        money("4", rows.get(1).getCredit());
        money("0.010", rows.get(2).getCredit());
        assertTrue(findings(p, first, second).isEmpty());
    }

    @Test
    void nullBlankWhitespaceAndDelimiterContainingPairsDoNotCollideTest() {
        var s =
                voucher(
                        DAY,
                        source(null, "1", null, null),
                        source(null, "1", "", null),
                        source(null, "1", " ", null),
                        source(null, "1", "a|b", "c"),
                        source(null, "1", "a", "b|c"),
                        source(null, "1", null, ""));
        var p = plan(accounts(), s);
        assertEquals(7, p.voucher().getRows().size());
        assertNull(p.voucher().getRows().get(0).getProjectNr());
        assertEquals("", p.voucher().getRows().get(1).getProjectNr());
        assertEquals(" ", p.voucher().getRows().get(2).getProjectNr());
        assertEquals("a|b", p.voucher().getRows().get(3).getProjectNr());
        assertEquals("b|c", p.voucher().getRows().get(4).getResultUnitNr());
        assertEquals("", p.voucher().getRows().get(5).getResultUnitNr());
        assertTrue(findings(p, s).isEmpty());
    }

    @Test
    void samePairCancellationNeedsNoClearingRowOrLossFindingTest() {
        var s = voucher(DAY, source(null, "10", "P", "U"), source("10", null, "P", "U"));
        var p = plan(accounts(), s);
        assertTrue(p.voucher().getRows().isEmpty());
        assertTrue(findings(p, s).isEmpty());
    }

    @Test
    void controlAndRoundingNeverInheritSourceDimensionsTest() {
        var s = voucher(DAY, source("1.99", null, "P", "U"));
        var p = plan(accounts(), s);
        assertEquals(3, p.voucher().getRows().size());
        assertEquals(1650, p.voucher().getRows().get(1).getAccountNr());
        money("1", p.voucher().getRows().get(1).getDebet());
        for (var row : p.voucher().getRows().subList(1, 3)) {
            assertNull(row.getProjectNr());
            assertNull(row.getResultUnitNr());
        }
        assertTrue(findings(p, s).isEmpty());
    }

    @Test
    void removingOrChangingCarriedDimensionReportsOriginalRawSourceReferencesTest() {
        var s = voucher(DAY, source(null, "10", "P", "U"));
        var p = plan(accounts(), s);
        p.voucher().getRows().get(0).setProjectNr("other");
        var f = findings(p, s);
        assertEquals(
                List.of("P", "U"),
                f.stream().map(VatSettlementDiagnostics.Finding::dimension).toList());
        assertTrue(f.stream().allMatch(v -> v.sourceVoucher() == 7 && v.sourceRow() == 1));
    }

    @Test
    void equalAccountTotalWithSwappedPairAmountsStillReportsLostGroupsTest() {
        var s = voucher(DAY, source(null, "10", "P1", "U"), source(null, "20", "P2", "U"));
        var p = plan(accounts(), s);
        p.voucher().getRows().get(0).setDebet(new BigDecimal("20"));
        p.voucher().getRows().get(1).setDebet(new BigDecimal("10"));
        assertEquals(4, findings(p, s).size());
    }

    @Test
    void zeroNetSamePairDoesNotHideDifferentPairLossTest() {
        var s =
                voucher(
                        DAY,
                        source(null, "10", "P1", "U"),
                        source("10", null, "P1", "U"),
                        source(null, "10", "P2", "U"));
        var p = plan(accounts(), s);
        p.voucher().getRows().get(0).setResultUnitNr(null);
        var f = findings(p, s);
        assertEquals(2, f.size());
        assertTrue(f.stream().allMatch(v -> v.sourceRow() == 3));
    }

    @Test
    void requiredFlagsUseCarriedValuesButDoNotApproveControlRowsTest() {
        var a = accounts();
        a.get(0).setProjectRequired(true);
        a.get(0).setResultUnitRequired(true);
        a.get(3).setProjectRequired(true);
        var s = voucher(DAY, source(null, "10", "P", "U"));
        var p = plan(a, s);
        var f = VatSettlementDiagnostics.inspect(p.report(), a, List.of(s), p.voucher());
        assertEquals(1, f.size());
        assertEquals(2650, f.get(0).account());
        assertEquals(VatSettlementDiagnostics.Kind.REQUIRED_PROJECT_MISSING, f.get(0).kind());
    }

    @Test
    void blankCarriedKeysRemainRawButStillFailRequiredFlagsTest() {
        var a = accounts();
        a.get(0).setProjectRequired(true);
        a.get(0).setResultUnitRequired(true);
        var s = voucher(DAY, source(null, "10", "", " \t"));
        var p = plan(a, s);
        var f = VatSettlementDiagnostics.inspect(p.report(), a, List.of(s), p.voucher());
        assertEquals(
                List.of(
                        VatSettlementDiagnostics.Kind.REQUIRED_PROJECT_MISSING,
                        VatSettlementDiagnostics.Kind.REQUIRED_UNIT_MISSING),
                f.stream().map(VatSettlementDiagnostics.Finding::kind).toList());
        assertEquals("", p.voucher().getRows().get(0).getProjectNr());
        assertEquals(" \t", p.voucher().getRows().get(0).getResultUnitNr());
    }
}
