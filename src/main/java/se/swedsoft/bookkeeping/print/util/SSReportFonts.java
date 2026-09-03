package se.swedsoft.bookkeeping.print.util;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.fonts.FontExtensionsRegistry;
import net.sf.jasperreports.extensions.ExtensionsEnvironment;
import net.sf.jasperreports.extensions.ExtensionsRegistry;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Registers the bundled, PDF-embedded font families used by all reports. */
public final class SSReportFonts {
    private static final String FONT_FAMILY = "Bokfri Sans";
    private static final String FONT_DEFINITION = "org/fribok/fonts/fonts.xml";
    private static final List<String> FONT_RESOURCES = List.of(
            "/org/fribok/fonts/dejavu/DejaVuSans.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-Oblique.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-BoldOblique.ttf",
            "/org/fribok/fonts/ocrb/OCR-B.ttf");
    private static boolean registered;

    private SSReportFonts() {}

    /** Registers the report fonts and deterministic PDF defaults once per JVM. */
    public static synchronized void register() {
        if (registered) {
            return;
        }

        for (String resource : FONT_RESOURCES) {
            registerAwtFont(resource);
        }

        ExtensionsRegistry original = ExtensionsEnvironment.getSystemExtensionsRegistry();
        ExtensionsRegistry fonts = new FontExtensionsRegistry(List.of(FONT_DEFINITION));
        ExtensionsEnvironment.setSystemExtensionsRegistry(new CompositeRegistry(original, fonts));

        JRPropertiesUtil properties = JRPropertiesUtil.getInstance(
                DefaultJasperReportsContext.getInstance());
        properties.setProperty("net.sf.jasperreports.default.font.name", FONT_FAMILY);
        properties.setProperty("net.sf.jasperreports.default.pdf.font.name", FONT_FAMILY);
        properties.setProperty("net.sf.jasperreports.default.pdf.encoding", "Identity-H");
        properties.setProperty("net.sf.jasperreports.default.pdf.embedded", "true");
        registered = true;
    }

    private static void registerAwtFont(String resource) {
        try (InputStream input = SSReportFonts.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled report font: " + resource);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, input);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not register bundled report font " + resource,
                    exception);
        }
    }

    private record CompositeRegistry(ExtensionsRegistry original, ExtensionsRegistry fonts)
            implements ExtensionsRegistry {
        @Override
        public <T> List<T> getExtensions(Class<T> extensionType) {
            List<T> extensions = new ArrayList<>();
            addAll(extensions, original.getExtensions(extensionType));
            addAll(extensions, fonts.getExtensions(extensionType));
            return extensions;
        }

        private static <T> void addAll(List<T> target, List<T> values) {
            if (values != null) {
                target.addAll(values);
            }
        }
    }
}
