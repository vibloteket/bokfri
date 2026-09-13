package se.swedsoft.bookkeeping.gui.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JTable;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.util.Arrays;

/** Logs display, Look & Feel, and component metrics used to diagnose HiDPI sizing. */
public final class SSUiScaleDiagnostics {

    private static final Logger LOG = LoggerFactory.getLogger(SSUiScaleDiagnostics.class);
    private static final String[] FONT_KEYS = {
        "Button.font", "CheckBox.font", "Label.font", "Menu.font", "MenuItem.font",
        "Table.font", "TableHeader.font", "TextField.font", "EditorPane.font"
    };

    private SSUiScaleDiagnostics() {}

    /** Logs process-wide display and Swing defaults after Look & Feel initialization. */
    public static void logEnvironment() {
        LOG.info("UI diagnostics: lookAndFeel={} ({})",
                UIManager.getLookAndFeel().getName(), UIManager.getLookAndFeel().getClass().getName());
        LOG.info("UI diagnostics: uiScale={} uiScale.enabled={} screenDpi={} headless={}",
                System.getProperty("sun.java2d.uiScale", "<automatic>"),
                System.getProperty("sun.java2d.uiScale.enabled", "<default>"),
                Toolkit.getDefaultToolkit().getScreenResolution(),
                GraphicsEnvironment.isHeadless());

        if (!GraphicsEnvironment.isHeadless()) {
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getScreenDevices()) {
                GraphicsConfiguration configuration = device.getDefaultConfiguration();
                AffineTransform transform = configuration.getDefaultTransform();
                LOG.info("UI diagnostics: display={} bounds={} transform=[{}, {}]",
                        device.getIDstring(), configuration.getBounds(),
                        transform.getScaleX(), transform.getScaleY());
            }
        }

        Arrays.stream(FONT_KEYS).forEach(key -> LOG.info("UI diagnostics: {}={}",
                key, describeFont(UIManager.getFont(key))));
    }

    /**
     * Logs the actual metrics of a component after construction.
     *
     * @param name stable diagnostic name
     * @param component component to inspect
     */
    public static void logComponent(String name, Component component) {
        GraphicsConfiguration configuration = component.getGraphicsConfiguration();
        AffineTransform transform = configuration == null
                ? new AffineTransform() : configuration.getDefaultTransform();
        Font font = component.getFont();
        FontMetrics metrics = component.getFontMetrics(font);
        String details = component instanceof JTable table
                ? " rowHeight=" + table.getRowHeight()
                        + " headerFont=" + describeFont(table.getTableHeader().getFont())
                : "";
        LOG.info("UI diagnostics: component={} class={} font={} metricsHeight={} size={} "
                        + "preferred={} transform=[{}, {}]{}",
                name, component.getClass().getName(), describeFont(font), metrics.getHeight(),
                component.getSize(), component.getPreferredSize(), transform.getScaleX(),
                transform.getScaleY(), details);
    }

    private static String describeFont(Font font) {
        if (font == null) {
            return "<null>";
        }
        return font.getFamily() + '/' + font.getName() + " style=" + font.getStyle()
                + " size=" + font.getSize2D() + " class=" + font.getClass().getName();
    }
}
