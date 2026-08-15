package org.fribok.bookkeeping.service.sie;

import se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEType;
import se.swedsoft.bookkeeping.importexport.util.SSExportException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Headless SIE export with safe output-file handling. */
public final class SieExportService {
    public SieExportResult export(Path output, SIEType type, String comment, boolean overwrite,
                                  boolean allowRoundingAdjustments)
            throws IOException, SSExportException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target) && !overwrite) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path temporary = Files.createTempFile(target.getParent(), ".bokfri-sie-", ".se");
        try {
            SSSIEExporter exporter = new SSSIEExporter(type, comment);
            exporter.setAllowRoundingAdjustments(allowRoundingAdjustments);
            exporter.exportSIE(temporary.toFile());
            if (overwrite) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target);
            }
            return new SieExportResult(target, exporter.getAdjustments());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
