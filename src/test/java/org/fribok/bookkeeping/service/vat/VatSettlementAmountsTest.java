package org.fribok.bookkeeping.service.vat;

import static org.fribok.bookkeeping.service.vat.ExplicitVatSettlementIsolationTest.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementAmounts.Kind;
import org.fribok.bookkeeping.service.vat.VatSettlementAmounts.Side;
import org.fribok.bookkeeping.service.vat.VatSettlementDiagnosticsTest.StrictRow;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class VatSettlementAmountsTest {
    static SSVoucher proposal(SSVoucherRow... rows) {
        var p = new SSVoucher(0, DAY);
        p.setVoucherRows(new ArrayList<>(List.of(rows)));
        return p;
    }

    static StrictRow row(String d, String c) {
        return new StrictRow(2610, d, c, null, null);
    }

    @Test
    void positiveCentExactValuesIncludingTrailingZerosHaveNoAmountFindingsTest() {
        assertTrue(
                VatSettlementAmounts.inspect(proposal(row("1.0000", null), row(null, "0.010000")))
                        .isEmpty());
    }

    @Test
    void missingAndBothSidesUseProposedPositionsWithoutInventedSideOrValueTest() {
        var findings = VatSettlementAmounts.inspect(proposal(row(null, null), row("1", "1")));
        assertEquals(
                List.of(Kind.MISSING_AMOUNT_SIDE, Kind.BOTH_AMOUNT_SIDES),
                findings.stream().map(x -> x.kind()).toList());
        assertEquals(List.of(1, 2), findings.stream().map(x -> x.proposedRow()).toList());
        assertTrue(
                findings.stream()
                        .allMatch(
                                x ->
                                        x.side() == null
                                                && x.amount() == null
                                                && x.account() == 2610));
    }

    @Test
    void nonPositiveAndFractionalValuesRetainExactSideAndOriginalScaleWithoutRepairTest() {
        var p = proposal(row("0", null), row(null, "-0.0010"));
        var findings = VatSettlementAmounts.inspect(p);
        assertEquals(
                List.of(Kind.NON_POSITIVE_AMOUNT, Kind.NON_POSITIVE_AMOUNT, Kind.FRACTIONAL_CENT),
                findings.stream().map(x -> x.kind()).toList());
        assertEquals(Side.DEBIT, findings.get(0).side());
        assertEquals(Side.CREDIT, findings.get(2).side());
        assertEquals(new BigDecimal("-0.0010"), findings.get(2).amount());
        assertEquals(new BigDecimal("-0.0010"), p.getRows().get(1).getCredit());
    }

    @Test
    void centBoundaryIsExactRatherThanRoundedHalfUpOrTruncatedTest() {
        var findings =
                VatSettlementAmounts.inspect(
                        proposal(
                                row("0.004", null),
                                row("0.005", null),
                                row("0.0099", null),
                                row("0.0100", null)));
        assertEquals(List.of(1, 2, 3), findings.stream().map(x -> x.proposedRow()).toList());
        assertTrue(findings.stream().allMatch(x -> x.kind() == Kind.FRACTIONAL_CENT));
    }

    @Test
    void exactStorageScaleBoundaryDistinguishesThirtyFromThirtyOneFractionDigitsTest() {
        var findings =
                VatSettlementAmounts.inspect(
                        proposal(
                                row("0." + "0".repeat(29) + "1", null),
                                row(null, "0." + "0".repeat(30) + "1")));
        assertEquals(
                List.of(Kind.FRACTIONAL_CENT, Kind.FRACTIONAL_CENT, Kind.STORAGE_RANGE),
                findings.stream().map(x -> x.kind()).toList());
        assertEquals(2, findings.get(2).proposedRow());
        assertEquals(Side.CREDIT, findings.get(2).side());
    }

    @Test
    void rowRangeAndTotalRangeAreSeparateObservationsAtSeventyIntegerDigitsTest() {
        var limit = "9".repeat(70);
        var findings =
                VatSettlementAmounts.inspect(
                        proposal(
                                row(limit, null),
                                row(limit, null),
                                row(null, limit),
                                row(null, limit)));
        assertEquals(2, findings.size());
        assertTrue(
                findings.stream()
                        .allMatch(
                                x ->
                                        x.kind() == Kind.TOTAL_ENTRY_RANGE
                                                && x.proposedRow() == null
                                                && x.account() == null));
        assertEquals(
                List.of(Side.DEBIT, Side.CREDIT), findings.stream().map(x -> x.side()).toList());
        assertEquals(
                0,
                new BigDecimal(limit)
                        .multiply(BigDecimal.valueOf(2))
                        .compareTo(findings.get(0).amount()));
    }

    @Test
    void scientificNotationAndTrailingZerosDoNotHideIntegerOverflowOrInventFractionalCentsTest() {
        var findings =
                VatSettlementAmounts.inspect(proposal(row("1E+70", null), row(null, "1E+69")));
        assertEquals(
                List.of(Kind.STORAGE_RANGE, Kind.TOTAL_ENTRY_RANGE),
                findings.stream().map(x -> x.kind()).toList());
        assertTrue(findings.stream().noneMatch(x -> x.kind() == Kind.FRACTIONAL_CENT));
    }

    @Test
    void findingsStayImmutableAfterLaterMutableProposalEditsTest() {
        var p = proposal(row("1.0010", null));
        var findings = VatSettlementAmounts.inspect(p);
        p.getRows().get(0).setDebet(BigDecimal.ONE);
        p.getRows().clear();
        assertEquals(new BigDecimal("1.0010"), findings.get(0).amount());
        assertThrows(UnsupportedOperationException.class, findings::clear);
    }

    @Test
    void rawRowsNeverResolveAccountsDimensionsOrValidityAndEmptyDoesNotApprovePostingTest() {
        assertTrue(VatSettlementAmounts.inspect(proposal()).isEmpty());
        var findings =
                VatSettlementAmounts.inspect(
                        proposal(new StrictRow(null, "0.001", null, "P", "U")));
        assertEquals(1, findings.size());
        assertEquals(null, findings.get(0).account());
        assertEquals(Kind.FRACTIONAL_CENT, findings.get(0).kind());
    }

    @Test
    void malformedExplicitProposalFailsRatherThanInventingNoFindingsTest() {
        assertThrows(NullPointerException.class, () -> VatSettlementAmounts.inspect(null));
        var p = new SSVoucher(0, DAY);
        p.setVoucherRows(new ArrayList<>(Arrays.asList((SSVoucherRow) null)));
        assertThrows(NullPointerException.class, () -> VatSettlementAmounts.inspect(p));
    }
}
