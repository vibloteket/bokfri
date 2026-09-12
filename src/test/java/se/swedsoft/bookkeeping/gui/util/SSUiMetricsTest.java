package se.swedsoft.bookkeeping.gui.util;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Dimension;
import java.awt.Font;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests font-aware Swing control sizing.
 */
class SSUiMetricsTest {

    @Test
    void keepsLegacyMinimumForSmallFonts() {
        assertThat(SSUiMetrics.controlHeight(12, 18)).isEqualTo(18);
    }

    @Test
    void growsControlsForScaledFonts() {
        assertThat(SSUiMetrics.controlHeight(20, 18)).isEqualTo(26);
    }

    @Test
    void derivesDimensionsFromComponentFont() {
        JLabel label = new JLabel();
        label.setFont(new Font(Font.DIALOG, Font.PLAIN, 24));

        Dimension square = SSUiMetrics.squareControlSize(label, 20);
        Dimension widened = SSUiMetrics.withControlHeight(label, new Dimension(75, 20), 20);

        assertThat(square.height).isGreaterThan(20);
        assertThat(square.width).isEqualTo(square.height);
        assertThat(widened.width).isEqualTo(75);
        assertThat(widened.height).isEqualTo(square.height);
    }
}
