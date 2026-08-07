package org.fribok.bookkeeping.service.sie;

import se.swedsoft.bookkeeping.importexport.sie.SSSIEImporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEFile;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Preflights and imports SIE files into the selected accounting year. */
public final class SieImportService {
    public SieImportPlan inspect(Path input, boolean vouchersOnly) throws IOException {
        Path file = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("SIE file does not exist: " + file);
        }
        List<String> lines = SIEFile.readFile(file.toFile());
        if (lines.isEmpty()) {
            throw new IOException("SIE file is empty");
        }
        String type = null;
        boolean alreadyImported = false;
        int accounts = 0;
        int vouchers = 0;
        int transactions = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.startsWith("#SIETYP ")) {
                type = firstValue(line.substring(8));
            } else if (line.startsWith("#FLAGGA ")) {
                alreadyImported = "1".equals(firstValue(line.substring(8)));
            } else if (line.startsWith("#KONTO ")) {
                accounts++;
            } else if (line.startsWith("#VER ")) {
                vouchers++;
            } else if (line.startsWith("#TRANS ")) {
                transactions++;
            }
        }
        if (type == null || !(type.equals("1") || type.equals("2")
                || type.equals("3") || type.equals("4"))) {
            throw new IOException("SIE file has no supported #SIETYP");
        }
        if (vouchersOnly && !type.equals("4")) {
            throw new IOException("Voucher import requires SIE type 4");
        }
        return new SieImportPlan(file, type, alreadyImported, accounts, vouchers,
                transactions, vouchersOnly);
    }

    public SieImportPlan importFile(Path input, boolean vouchersOnly, boolean allowAlreadyImported)
            throws IOException, SSImportException {
        SieImportPlan plan = inspect(input, vouchersOnly);
        if (plan.alreadyImported() && !allowAlreadyImported) {
            throw new IOException("SIE file is marked as already imported (#FLAGGA 1)");
        }
        SSSIEImporter importer = new SSSIEImporter(plan.file().toFile());
        if (vouchersOnly) {
            importer.doImportVouchers();
        } else {
            importer.doImport();
        }
        return plan;
    }

    private static String firstValue(String value) {
        String trimmed = value.trim();
        int separator = trimmed.indexOf(' ');
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }
}
