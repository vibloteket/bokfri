package se.swedsoft.bookkeeping.gui.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Corrects Windows Look & Feel fonts that are inversely normalized for display scaling. */
public final class SSWindowsFontScale {

    private static final Logger LOG = LoggerFactory.getLogger(SSWindowsFontScale.class);
    private static final float MINIMUM_NORMAL_FONT_SIZE = 10.0f;

    private SSWindowsFontScale() {}

    /**
     * Restores normal-sized Swing fonts that Windows Look & Feel divided by the display scale.
     *
     * <p>On affected JDK/Windows combinations, ordinary 11-point fonts become 7 points at 150%
     * display scaling and are then enlarged again by the graphics transform. This cancellation
     * leaves buttons, tables, labels, and fields at approximately their 100% physical size.</p>
     */
    public static void apply() {
        if (!isWindows() || GraphicsEnvironment.isHeadless()) {
            return;
        }

        double displayScale = defaultDisplayScale();
        if (displayScale <= 1.0) {
            return;
        }

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        List<Object> keys = new ArrayList<>(defaults.keySet());
        int changed = 0;
        for (Object key : keys) {
            Object value = defaults.get(key);
            if (value instanceof Font font) {
                float correctedSize = correctedSize(font.getSize2D(), displayScale);
                if (Float.compare(correctedSize, font.getSize2D()) != 0) {
                    defaults.put(key, new FontUIResource(font.deriveFont(correctedSize)));
                    changed++;
                }
            }
        }
        LOG.info("Corrected {} undersized Swing font defaults for Windows display scale {}%",
                changed, Math.round(displayScale * 100));
    }

    static float correctedSize(float fontSize, double displayScale) {
        if (displayScale <= 1.0 || fontSize >= MINIMUM_NORMAL_FONT_SIZE) {
            return fontSize;
        }
        return (float) (fontSize * displayScale);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static double defaultDisplayScale() {
        GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        AffineTransform transform = configuration.getDefaultTransform();
        return Math.max(transform.getScaleX(), transform.getScaleY());
    }
}
