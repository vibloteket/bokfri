package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.print.util.SSReportFonts;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration coverage for bundled and embedded report fonts. */
@Tag("integration")
class ReportFontIntegrationTest {
    private static final long MAX_EMBEDDED_FONT_OVERHEAD_BYTES = 100_000;
    @TempDir
    Path temporaryDirectory;

    @Test
    void embedsEveryConfiguredStyleAndPreservesUnicodeText() throws Exception {
        SSReportFonts.register();
        String source = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://jasperreports.sourceforge.net/jasperreports
                    http://jasperreports.sourceforge.net/xsd/jasperreport.xsd"
                    name="FontProbe" pageWidth="300" pageHeight="180" columnWidth="260"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <detail><band height="112">
                    <staticText><reportElement x="0" y="0" width="260" height="20"/>
                      <textElement><font size="12"/></textElement><text><![CDATA[Regular åäö ÅÄÖ € SEK]]></text>
                    </staticText>
                    <staticText><reportElement x="0" y="22" width="260" height="20"/>
                      <textElement><font size="12" isBold="true"/></textElement><text><![CDATA[Bold åäö]]></text>
                    </staticText>
                    <staticText><reportElement x="0" y="44" width="260" height="20"/>
                      <textElement><font size="12" isItalic="true"/></textElement><text><![CDATA[Italic åäö]]></text>
                    </staticText>
                    <staticText><reportElement x="0" y="60" width="260" height="16"/>
                      <textElement><font size="12" isBold="true" isItalic="true"/></textElement>
                      <text><![CDATA[Bold italic åäö]]></text>
                    </staticText>
                  </band></detail>
                </jasperReport>
                """;
        var report = JasperCompileManager.compileReport(new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8)));
        var print = JasperFillManager.fillReport(report, new HashMap<>(), new JREmptyDataSource());
        Path pdf = temporaryDirectory.resolve("font-probe.pdf");
        JasperExportManager.exportReportToPdfFile(print, pdf.toString());

        Map<String, Boolean> fonts = pdfFonts(pdf);
        assertThat(fonts).containsOnlyKeys("DejaVuSans", "DejaVuSans-Bold",
                "DejaVuSans-Oblique", "DejaVuSans-BoldOblique");
        assertThat(fonts.values()).containsOnly(true);
        assertThat(Files.size(pdf)).isLessThan(MAX_EMBEDDED_FONT_OVERHEAD_BYTES);

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Regular åäö ÅÄÖ € SEK", "Bold åäö", "Italic åäö",
                            "Bold italic åäö");
        }
    }

    private static Map<String, Boolean> pdfFonts(Path pdf) throws Exception {
        Map<String, Boolean> fonts = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            for (var page : document.getPages()) {
                collectFonts(page.getResources(), fonts);
            }
        }
        return fonts;
    }

    private static void collectFonts(PDResources resources, Map<String, Boolean> fonts)
            throws Exception {
        if (resources == null) return;
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            PDFontDescriptor descriptor = font.getFontDescriptor();
            boolean embedded = descriptor != null && (descriptor.getFontFile() != null
                    || descriptor.getFontFile2() != null || descriptor.getFontFile3() != null);
            fonts.put(font.getName().replaceFirst("^[A-Z]{6}\\+", ""), embedded);
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject form) collectFonts(form.getResources(), fonts);
        }
    }
}
