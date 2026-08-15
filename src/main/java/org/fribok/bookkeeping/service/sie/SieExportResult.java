package org.fribok.bookkeeping.service.sie;

import se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter;

import java.nio.file.Path;
import java.util.List;

/** Created SIE file and any non-persistent rounding rows added to it. */
public record SieExportResult(Path path,
                              List<SSSIEExporter.SIEExportAdjustment> adjustments) {
    public SieExportResult {
        adjustments = List.copyOf(adjustments);
    }
}
