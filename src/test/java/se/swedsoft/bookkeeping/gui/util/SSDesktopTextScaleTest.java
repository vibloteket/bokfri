package se.swedsoft.bookkeeping.gui.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests parsing of the Windows accessibility text-size registry value.
 */
class SSDesktopTextScaleTest {

    @Test
    void parsesHexadecimalRegistryOutput() {
        String output = "    TextScaleFactor    REG_DWORD    0x7d";

        assertThat(SSDesktopTextScale.parseWindowsTextScale(output)).isEqualTo(1.25);
    }

    @Test
    void parsesDecimalRegistryOutput() {
        String output = "    TextScaleFactor    REG_DWORD    150";

        assertThat(SSDesktopTextScale.parseWindowsTextScale(output)).isEqualTo(1.5);
    }

    @Test
    void defaultsAndClampsUnexpectedValues() {
        assertThat(SSDesktopTextScale.parseWindowsTextScale("ERROR: missing")).isEqualTo(1.0);
        assertThat(SSDesktopTextScale.parseWindowsTextScale(
                "TextScaleFactor REG_DWORD 0x32")).isEqualTo(1.0);
        assertThat(SSDesktopTextScale.parseWindowsTextScale(
                "TextScaleFactor REG_DWORD 0x3e8")).isEqualTo(2.25);
    }
}
