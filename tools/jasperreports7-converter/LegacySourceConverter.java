package com.jaspersoft.jasperreports.legacy.xml;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.xml.JRXmlTemplateWriter;
import net.sf.jasperreports.engine.xml.JRXmlWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts one JasperReports 6 source file using Jaspersoft Studio's legacy loader. */
public final class LegacySourceConverter {
    private static final Pattern UUID_ATTRIBUTE = Pattern.compile("uuid=\"[^\"]+\"");

    private LegacySourceConverter() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected input path, output path, and logical name");
        }
        var context = DefaultJasperReportsContext.getInstance();
        var output = new ByteArrayOutputStream();
        if (arguments[0].endsWith(".jrtx")) {
            var template = TemplateXmlLoader.getInstance(context)
                    .loadTemplate(new File(arguments[0]));
            JRXmlTemplateWriter.writeTemplate(context, template, output, "UTF-8");
        } else {
            var design = ReportXmlLoader.load(context, new File(arguments[0]));
            new JRXmlWriter(context).write(design, output, "UTF-8");
        }
        String source = output.toString(StandardCharsets.UTF_8);
        Files.writeString(Path.of(arguments[1]), deterministicUuids(source, arguments[2]),
                StandardCharsets.UTF_8);
    }

    private static String deterministicUuids(String source, String logicalName) {
        Matcher matcher = UUID_ATTRIBUTE.matcher(source);
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            UUID uuid = UUID.nameUUIDFromBytes(
                    (logicalName + "#" + index++).getBytes(StandardCharsets.UTF_8));
            matcher.appendReplacement(result, "uuid=\"" + uuid + "\"");
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
