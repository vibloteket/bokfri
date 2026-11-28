package org.fribok.bookkeeping.service.vat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure fail-closed historical-period policy. Explicit inputs are not trusted provenance or posting
 * authorization. No database reader, baseline certification or posting path is supplied here.
 */
public final class VatSettlementHistory {
    private VatSettlementHistory() {}

    /** Inclusive calendar dates, independent of time zone and locale. */
    public record Period(LocalDate first, LocalDate last) {
        public Period {
            Objects.requireNonNull(first, "first date");
            Objects.requireNonNull(last, "last date");
            if (last.isBefore(first)) {
                throw new IllegalArgumentException("Reversed history period");
            }
        }
    }

    /** Explicit normalized company/year identity and the fiscal-year date bounds. */
    public record Scope(int company, int year, Period fiscalYear) {
        public Scope {
            if (company <= 0 || year <= 0) {
                throw new IllegalArgumentException("Positive company/year identities required");
            }
            Objects.requireNonNull(fiscalYear, "fiscal year");
        }
    }

    /**
     * Immutable caller-supplied history facts, not evidence of an authorized human review. Empty
     * coverage means unknown history. Known settlements block even outside reviewed coverage.
     * Input order, overlaps and duplicate facts are retained, never interpreted as reversals.
     */
    public record Snapshot(Scope scope, List<Period> reviewedCoverage, List<Period> knownSettlements) {
        public Snapshot {
            Objects.requireNonNull(scope, "history scope");
            reviewedCoverage = List.copyOf(reviewedCoverage);
            knownSettlements = List.copyOf(knownSettlements);
            for (Period period : reviewedCoverage) {
                requireWithin(scope.fiscalYear(), period);
            }
            for (Period period : knownSettlements) {
                requireWithin(scope.fiscalYear(), period);
            }
        }
    }

    /** Independent reasons a requested historical period must remain blocked. */
    public enum Kind {
        UNREVIEWED_COVERAGE,
        KNOWN_SETTLEMENT_OVERLAP
    }

    /** Unreviewed gap inside the request, or the complete overlapping known-settlement period. */
    public record Finding(Kind kind, Period period) {
        public Finding {
            Objects.requireNonNull(kind, "history finding kind");
            Objects.requireNonNull(period, "history finding period");
        }
    }

    /**
     * Finds every uncovered date range and every overlapping known settlement. Adjacent reviewed
     * intervals jointly cover a request; one missing day blocks it. Inclusive endpoints overlap.
     * Coverage never overrides a known settlement. Findings are immutable: gaps in date order,
     * followed by conflicting known periods in input order. Empty findings only mean these explicit
     * history facts have no history-policy conflict, NOT permission to post or certify a baseline.
     *
     * @param scope expected company/year and fiscal bounds, not inferred from history
     * @param requested inclusive requested settlement period, wholly inside the fiscal year
     * @param history explicit history facts for exactly the expected scope
     * @return immutable history-policy findings, never a posting-readiness boolean
     * @throws IllegalArgumentException for scope mismatch or dates outside the fiscal year
     * @throws NullPointerException for missing explicit inputs
     */
    public static List<Finding> inspect(Scope scope, Period requested, Snapshot history) {
        Objects.requireNonNull(scope, "expected scope");
        Objects.requireNonNull(requested, "requested period");
        Objects.requireNonNull(history, "explicit history");
        if (!scope.equals(history.scope())) {
            throw new IllegalArgumentException("History belongs to a different scope");
        }
        requireWithin(scope.fiscalYear(), requested);
        var findings = new ArrayList<Finding>();
        var coverage = new ArrayList<>(history.reviewedCoverage());
        coverage.sort(Comparator.comparing(Period::first).thenComparing(Period::last));
        // Epoch-day arithmetic avoids plusDays overflow at LocalDate.MAX.
        long next = requested.first().toEpochDay();
        long end = requested.last().toEpochDay();
        for (Period period : coverage) {
            long first = Math.max(next, period.first().toEpochDay());
            long last = Math.min(end, period.last().toEpochDay());
            if (last < first) {
                continue;
            }
            if (first > next) {
                addGap(findings, next, first - 1);
            }
            next = last + 1;
            if (next > end) {
                break;
            }
        }
        if (next <= end) {
            addGap(findings, next, end);
        }
        for (Period known : history.knownSettlements()) {
            if (!known.last().isBefore(requested.first())
                    && !known.first().isAfter(requested.last())) {
                findings.add(new Finding(Kind.KNOWN_SETTLEMENT_OVERLAP, known));
            }
        }
        return List.copyOf(findings);
    }

    private static void addGap(List<Finding> findings, long first, long last) {
        findings.add(new Finding(
                Kind.UNREVIEWED_COVERAGE,
                new Period(LocalDate.ofEpochDay(first), LocalDate.ofEpochDay(last))));
    }

    private static void requireWithin(Period bounds, Period period) {
        Objects.requireNonNull(period, "history period");
        if (period.first().isBefore(bounds.first()) || period.last().isAfter(bounds.last())) {
            throw new IllegalArgumentException("History period outside fiscal year");
        }
    }
}
