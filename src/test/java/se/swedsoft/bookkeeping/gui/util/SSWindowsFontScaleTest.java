package se.swedsoft.bookkeeping.gui.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests correction of Windows Look & Feel font sizes at scaled display settings. */
class SSWindowsFontScaleTest {

    @Test
    void restoresFontNormalizedAtOneHundredFiftyPercent() {
        assertThat(SSWindowsFontScale.correctedSize(7.0f, 1.5)).isEqualTo(10.5f);
    }

    @Test
    void leavesNormalSizedFontsUnchanged() {
        assertThat(SSWindowsFontScale.correctedSize(11.0f, 1.5)).isEqualTo(11.0f);
        assertThat(SSWindowsFontScale.correctedSize(12.0f, 2.0)).isEqualTo(12.0f);
    }

    @Test
    void leavesOneHundredPercentUnchanged() {
        assertThat(SSWindowsFontScale.correctedSize(7.0f, 1.0)).isEqualTo(7.0f);
    }
}
