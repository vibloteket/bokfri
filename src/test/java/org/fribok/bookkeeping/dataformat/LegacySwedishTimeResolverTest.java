package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySwedishTimeResolverTest {

    @Test
    void resolvesOrdinaryWinterAndSummerTimes() {
        assertResolution(LocalDateTime.of(2026, 1, 15, 12, 0),
                "2026-01-15T11:00:00Z",
                LegacySwedishTimeResolver.ResolutionKind.EXACT);
        assertResolution(LocalDateTime.of(2026, 7, 15, 12, 0),
                "2026-07-15T10:00:00Z",
                LegacySwedishTimeResolver.ResolutionKind.EXACT);
    }

    @Test
    void movesSpringGapForwardByTheGapDuration() {
        assertResolution(LocalDateTime.of(2026, 3, 29, 2, 30),
                "2026-03-29T01:30:00Z",
                LegacySwedishTimeResolver.ResolutionKind.FORWARD_BY_GAP);
    }

    @Test
    void usesEarlierOffsetDuringAutumnOverlap() {
        assertResolution(LocalDateTime.of(2026, 10, 25, 2, 30),
                "2026-10-25T00:30:00Z",
                LegacySwedishTimeResolver.ResolutionKind.EARLIER_OFFSET_IN_OVERLAP);
    }

    private static void assertResolution(LocalDateTime local, String expectedInstant,
                                         LegacySwedishTimeResolver.ResolutionKind expectedKind) {
        LegacySwedishTimeResolver.Resolution resolution =
                LegacySwedishTimeResolver.resolve(local);
        assertThat(resolution.instant()).isEqualTo(Instant.parse(expectedInstant));
        assertThat(resolution.kind()).isEqualTo(expectedKind);
    }
}
