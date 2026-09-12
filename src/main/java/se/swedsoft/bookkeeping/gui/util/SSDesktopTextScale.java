package se.swedsoft.bookkeeping.gui.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the Windows accessibility text-size setting to Swing defaults.
 *
 * <p>Java accounts for Windows display DPI, but does not currently apply the separate
 * Settings &gt; Accessibility &gt; Text size value to Swing fonts.</p>
 */
public final class SSDesktopTextScale {

    private static final Logger LOG = LoggerFactory.getLogger(SSDesktopTextScale.class);
    private static final Pattern HEX_VALUE = Pattern.compile("(?i)\\b0x([0-9a-f]+)\\b");
    private static final Pattern DECIMAL_VALUE = Pattern.compile("(?i)REG_DWORD\\s+(\\d+)\\b");
    private static final String WINDOWS_TEXT_SCALE_KEY =
            "HKCU\\Software\\Microsoft\\Accessibility";
    private static final double MINIMUM_SCALE = 1.0;
    private static final double MAXIMUM_SCALE = 2.25;

    private SSDesktopTextScale() {}

    /**
     * Scales all installed Look &amp; Feel fonts according to the desktop text-size preference.
     */
    public static void apply() {
        double scale = readScale();
        if (scale <= MINIMUM_SCALE) {
            return;
        }

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        List<Object> keys = new ArrayList<>(defaults.keySet());
        int changed = 0;
        for (Object key : keys) {
            Object value = defaults.get(key);
            if (value instanceof Font font) {
                defaults.put(key, new FontUIResource(font.deriveFont(
                        (float) (font.getSize2D() * scale))));
                changed++;
            }
        }
        LOG.info("Applied desktop text scale {}% to {} Swing font defaults",
                Math.round(scale * 100), changed);
    }

    static double parseWindowsTextScale(String output) {
        Matcher hexadecimal = HEX_VALUE.matcher(output);
        if (hexadecimal.find()) {
            return clamp(Integer.parseInt(hexadecimal.group(1), 16) / 100.0);
        }

        Matcher decimal = DECIMAL_VALUE.matcher(output);
        if (decimal.find()) {
            return clamp(Integer.parseInt(decimal.group(1)) / 100.0);
        }
        return MINIMUM_SCALE;
    }

    private static double readScale() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return MINIMUM_SCALE;
        }

        ProcessBuilder command = new ProcessBuilder("reg", "query", WINDOWS_TEXT_SCALE_KEY,
                "/v", "TextScaleFactor");
        try {
            Process process = command.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (left, right) -> left + '\n' + right);
            }
            if (process.waitFor() == 0) {
                return parseWindowsTextScale(output);
            }
        } catch (IOException exception) {
            LOG.warn("Could not read the Windows accessibility text scale", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return MINIMUM_SCALE;
    }

    private static double clamp(double scale) {
        return Math.max(MINIMUM_SCALE, Math.min(MAXIMUM_SCALE, scale));
    }
}
