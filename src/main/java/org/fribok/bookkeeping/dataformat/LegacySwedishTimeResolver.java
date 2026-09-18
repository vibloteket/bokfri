package org.fribok.bookkeeping.dataformat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;

/** Resolves zone-less legacy event times using Bokfri's Swedish compatibility policy. */
public final class LegacySwedishTimeResolver {
    public static final ZoneId LEGACY_ZONE = ZoneId.of("Europe/Stockholm");

    private LegacySwedishTimeResolver() {}

    /**
     * Resolves a legacy local date-time to an instant.
     *
     * <p>Ordinary values use their sole valid offset. Spring-gap values move forward by the
     * transition duration. Autumn-overlap values use the earlier offset (the first occurrence).
     *
     * @param localTime zone-less legacy value
     * @return resolved instant and any daylight-saving adjustment made
     */
    public static Resolution resolve(LocalDateTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        ZoneRules rules = LEGACY_ZONE.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localTime);
        if (offsets.size() == 1) {
            return new Resolution(localTime.toInstant(offsets.get(0)), ResolutionKind.EXACT);
        }
        if (offsets.size() == 2) {
            return new Resolution(localTime.toInstant(offsets.get(0)),
                    ResolutionKind.EARLIER_OFFSET_IN_OVERLAP);
        }

        ZoneOffsetTransition transition = rules.getTransition(localTime);
        if (transition == null || !transition.isGap()) {
            throw new IllegalArgumentException("Cannot resolve Stockholm local time " + localTime);
        }
        Duration gap = transition.getDuration();
        LocalDateTime adjusted = localTime.plus(gap);
        return new Resolution(adjusted.toInstant(transition.getOffsetAfter()),
                ResolutionKind.FORWARD_BY_GAP);
    }

    /** Describes whether conversion was exact or required a daylight-saving policy decision. */
    public enum ResolutionKind {
        EXACT,
        FORWARD_BY_GAP,
        EARLIER_OFFSET_IN_OVERLAP
    }

    /** A resolved instant and the rule used to obtain it. */
    public record Resolution(Instant instant, ResolutionKind kind) {}
}
