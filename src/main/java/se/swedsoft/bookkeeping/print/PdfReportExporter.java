package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Central PDF file handling for both headless and Swing report adapters. */
public final class PdfReportExporter {
    private PdfReportExporter() {}

    /**
     * Exports a Jasper document with consistent path and overwrite handling.
     *
     * @param print rendered Jasper document
     * @param output destination file
     * @param overwrite whether an existing destination may be replaced
     * @return normalized absolute destination
     * @throws IOException if the destination cannot be prepared
     * @throws JRException if PDF encoding fails
     */
    public static Path export(JasperPrint print, Path output, boolean overwrite)
            throws IOException, JRException {
        Objects.requireNonNull(print, "print");
        Objects.requireNonNull(output, "output");
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        JasperExportManager.exportReportToPdfFile(print, resolved.toString());
        return resolved;
    }
}
