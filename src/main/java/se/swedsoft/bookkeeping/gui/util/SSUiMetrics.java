package se.swedsoft.bookkeeping.gui.util;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.FontMetrics;

/**
 * Calculates control dimensions from the active Swing font instead of fixed pixel assumptions.
 */
public final class SSUiMetrics {

    private static final int VERTICAL_TEXT_PADDING = 6;

    private SSUiMetrics() {}

    /**
     * Returns a control height that can display the component font with comfortable padding.
     *
     * @param component component whose effective font should be measured
     * @param minimumHeight smallest accepted logical height
     * @return font-aware logical control height
     */
    public static int controlHeight(JComponent component, int minimumHeight) {
        FontMetrics metrics = component.getFontMetrics(component.getFont());
        return controlHeight(metrics.getHeight(), minimumHeight);
    }

    /**
     * Returns a copy of a dimension with a font-aware minimum height.
     *
     * @param component component whose effective font should be measured
     * @param size original preferred size
     * @param minimumHeight smallest accepted logical height
     * @return copied dimension with sufficient height
     */
    public static Dimension withControlHeight(JComponent component, Dimension size,
            int minimumHeight) {
        return new Dimension(size.width, Math.max(size.height, controlHeight(component, minimumHeight)));
    }

    /**
     * Returns a square size large enough for the component font.
     *
     * @param component component whose effective font should be measured
     * @param minimumSize smallest accepted logical width and height
     * @return font-aware square dimension
     */
    public static Dimension squareControlSize(JComponent component, int minimumSize) {
        int size = controlHeight(component, minimumSize);
        return new Dimension(size, size);
    }

    static int controlHeight(int fontHeight, int minimumHeight) {
        return Math.max(minimumHeight, fontHeight + VERTICAL_TEXT_PADDING);
    }
}
