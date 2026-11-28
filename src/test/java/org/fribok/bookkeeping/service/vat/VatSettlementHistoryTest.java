package org.fribok.bookkeeping.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Finding;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Kind;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Period;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Scope;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class VatSettlementHistoryTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final Period YEAR = new Period(START, LocalDate.of(2024, 12, 31));
    private static final Scope SCOPE = new Scope(1, 2, YEAR);

    private static Period days(int first, int last) {
        return new Period(START.plusDays(first), START.plusDays(last));
    }

    private static List<Finding> inspect(Period request, List<Period> coverage, List<Period> known) {
        return VatSettlementHistory.inspect(SCOPE, request, new Snapshot(SCOPE, coverage, known));
    }

    @Test
    void emptyRegistryIsUnknownNotProofOfUnsettledPeriodTest() {
        assertEquals(
                List.of(new Finding(Kind.UNREVIEWED_COVERAGE, days(0, 90))),
                inspect(days(0, 90), List.of(), List.of()));
    }

    @Test
    void explicitFullCoverageHasNoHistoryFindingButDoesNotAuthorizePostingTest() {
        assertTrue(inspect(days(0, 90), List.of(YEAR), List.of()).isEmpty());
    }

    @Test
    void adjacentUnorderedNestedAndDuplicateReviewsCoverWithoutMutatingInputsTest() {
        var coverage = new ArrayList<>(List.of(days(60, 90), days(0, 29), days(30, 59), days(5, 10),
                days(0, 29)));
        var before = List.copyOf(coverage);
        assertTrue(inspect(days(0, 90), coverage, List.of()).isEmpty());
        assertEquals(before, coverage);
    }

    @Test
    void everyUnreviewedGapIsReturnedIncludingOneDayAndBothEdgesTest() {
        assertEquals(
                List.of(new Finding(Kind.UNREVIEWED_COVERAGE, days(0, 0)),
                        new Finding(Kind.UNREVIEWED_COVERAGE, days(30, 30)),
                        new Finding(Kind.UNREVIEWED_COVERAGE, days(60, 90))),
                inspect(days(0, 90), List.of(days(1, 29), days(31, 59)), List.of()));
    }

    @Test
    void reviewsOutsideRequestNeitherCoverNorCreateExtraGapsTest() {
        assertEquals(List.of(new Finding(Kind.UNREVIEWED_COVERAGE, days(30, 59))),
                inspect(days(30, 59), List.of(days(0, 29), days(60, 90)), List.of()));
    }

    @Test
    void inclusiveKnownOverlapBlocksEvenWithFullReviewedCoverageTest() {
        var known = List.of(days(0, 30), days(59, 80), days(20, 70), days(40, 40));
        assertEquals(known.stream().map(p -> new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, p)).toList(),
                inspect(days(30, 59), List.of(YEAR), known));
    }

    @Test
    void adjacentKnownPeriodsDoNotOverlapTest() {
        assertTrue(inspect(days(30, 59), List.of(YEAR), List.of(days(0, 29), days(60, 90))).isEmpty());
    }

    @Test
    void unknownCoverageAndKnownOverlapAreIndependentAndBothRetainedTest() {
        assertEquals(List.of(new Finding(Kind.UNREVIEWED_COVERAGE, days(30, 59)),
                        new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, days(40, 70))),
                inspect(days(30, 59), List.of(), List.of(days(40, 70))));
    }

    @Test
    void duplicateKnownFactsAreNotSilentlyCancelledOrTreatedAsReversalsTest() {
        var known = days(0, 90);
        assertEquals(List.of(new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, known),
                        new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, known)),
                inspect(known, List.of(YEAR), List.of(known, known)));
    }

    @Test
    void missingHistoryAndWrongCompanyYearOrFiscalBoundsFailClosedTest() {
        assertThrows(NullPointerException.class, () -> VatSettlementHistory.inspect(SCOPE, YEAR, null));
        for (Scope other : List.of(new Scope(2, 2, YEAR), new Scope(1, 3, YEAR),
                new Scope(1, 2, days(0, 90)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> VatSettlementHistory.inspect(SCOPE, days(0, 90),
                            new Snapshot(other, List.of(), List.of())));
        }
    }

    @Test
    void outOfYearReviewsKnownSettlementsAndRequestsAreRejectedRatherThanClippedTest() {
        for (Period outside : List.of(days(-1, 0), days(365, 366))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Snapshot(SCOPE, List.of(outside), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new Snapshot(SCOPE, List.of(), List.of(outside)));
            assertThrows(IllegalArgumentException.class,
                    () -> inspect(outside, List.of(YEAR), List.of()));
        }
    }

    @Test
    void invalidAndNullInputsAreRejectedTest() {
        assertThrows(IllegalArgumentException.class, () -> days(1, 0));
        assertThrows(NullPointerException.class, () -> new Period(null, START));
        assertThrows(NullPointerException.class, () -> new Period(START, null));
        assertThrows(IllegalArgumentException.class, () -> new Scope(0, 2, YEAR));
        assertThrows(IllegalArgumentException.class, () -> new Scope(1, -1, YEAR));
        assertThrows(NullPointerException.class, () -> new Scope(1, 2, null));
        assertThrows(NullPointerException.class, () -> new Snapshot(null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new Snapshot(SCOPE, null, List.of()));
        assertThrows(NullPointerException.class, () -> new Snapshot(SCOPE, List.of(), null));
        assertThrows(NullPointerException.class,
                () -> new Snapshot(SCOPE, Arrays.asList((Period) null), List.of()));
        assertThrows(NullPointerException.class,
                () -> new Snapshot(SCOPE, List.of(), Arrays.asList((Period) null)));
        var history = new Snapshot(SCOPE, List.of(), List.of());
        assertThrows(NullPointerException.class, () -> VatSettlementHistory.inspect(null, YEAR, history));
        assertThrows(NullPointerException.class, () -> VatSettlementHistory.inspect(SCOPE, null, history));
        assertThrows(NullPointerException.class, () -> new Finding(null, YEAR));
        assertThrows(NullPointerException.class, () -> new Finding(Kind.UNREVIEWED_COVERAGE, null));
    }

    @Test
    void snapshotAndFindingsCannotBeChangedThroughCallerListsTest() {
        var reviews = new ArrayList<>(List.of(days(0, 29)));
        var known = new ArrayList<>(List.of(days(30, 59)));
        var history = new Snapshot(SCOPE, reviews, known);
        var findings = VatSettlementHistory.inspect(SCOPE, days(0, 59), history);
        reviews.clear();
        known.clear();
        assertEquals(List.of(days(0, 29)), history.reviewedCoverage());
        assertEquals(List.of(days(30, 59)), history.knownSettlements());
        assertEquals(2, findings.size());
        assertEquals(findings, VatSettlementHistory.inspect(SCOPE, days(0, 59), history));
        assertThrows(UnsupportedOperationException.class, () -> history.reviewedCoverage().clear());
        assertThrows(UnsupportedOperationException.class, () -> history.knownSettlements().clear());
        assertThrows(UnsupportedOperationException.class, () -> findings.clear());
    }

    @Test
    void leapDayAndNonCalendarFiscalYearAreOrdinaryInclusiveDatesTest() {
        var fiscal = new Period(LocalDate.of(2023, 7, 1), LocalDate.of(2024, 6, 30));
        var scope = new Scope(1, 2, fiscal);
        var leap = new Period(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
        var coverage = List.of(new Period(fiscal.first(), leap.first().minusDays(1)),
                new Period(leap.last().plusDays(1), fiscal.last()));
        assertEquals(List.of(new Finding(Kind.UNREVIEWED_COVERAGE, leap)),
                VatSettlementHistory.inspect(scope, fiscal, new Snapshot(scope, coverage, List.of())));
    }

    @Test
    void extremeLocalDatesDoNotOverflowWhenAdvancingCoverageTest() {
        for (LocalDate date : List.of(LocalDate.MIN, LocalDate.MAX)) {
            var period = new Period(date, date);
            var scope = new Scope(1, 2, period);
            assertTrue(VatSettlementHistory.inspect(scope, period,
                    new Snapshot(scope, List.of(period), List.of())).isEmpty());
            assertEquals(List.of(new Finding(Kind.UNREVIEWED_COVERAGE, period)),
                    VatSettlementHistory.inspect(scope, period,
                            new Snapshot(scope, List.of(), List.of())));
        }
    }

    @Test
    void deterministicRandomIntervalsMatchIndependentDailyCoverageAndOverlapOracleTest() {
        var random = new Random(75);
        for (int trial = 0; trial < 500; trial++) {
            var coverage = new ArrayList<Period>();
            var known = new ArrayList<Period>();
            var covered = new boolean[91];
            for (int i = 0; i < 12; i++) {
                int first = random.nextInt(91);
                int last = first + random.nextInt(91 - first);
                coverage.add(days(first, last));
                for (int day = first; day <= last; day++) {
                    covered[day] = true;
                }
                int settledFirst = random.nextInt(91);
                known.add(days(settledFirst, settledFirst + random.nextInt(91 - settledFirst)));
            }
            int first = random.nextInt(91);
            int last = first + random.nextInt(91 - first);
            var expected = new ArrayList<Finding>();
            for (int day = first; day <= last; day++) {
                if (!covered[day]) {
                    int gapFirst = day;
                    while (day < last && !covered[day + 1]) {
                        day++;
                    }
                    expected.add(new Finding(Kind.UNREVIEWED_COVERAGE, days(gapFirst, day)));
                }
            }
            for (Period settled : known) {
                boolean overlap = false;
                for (int day = first; day <= last; day++) {
                    var date = START.plusDays(day);
                    overlap |= !date.isBefore(settled.first()) && !date.isAfter(settled.last());
                }
                if (overlap) {
                    expected.add(new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, settled));
                }
            }
            assertEquals(expected, inspect(days(first, last), coverage, known), "trial " + trial);
        }
    }
}
