package org.fribok.bookkeeping.service.sie;

import se.swedsoft.bookkeeping.importexport.sie.SSSIEImporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEFile;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Preflights and imports SIE files without modifying the source file. */
public final class SieImportService {
    private static final String HISTORY_FILE = "sie-import.history";

    private final Path dataDirectory;

    public SieImportService(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

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
        boolean sourceMarkedImported = false;
        int accounts = 0;
        int vouchers = 0;
        int transactions = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.startsWith("#SIETYP ")) {
                type = firstValue(line.substring(8));
            } else if (line.startsWith("#FLAGGA ")) {
                sourceMarkedImported = "1".equals(firstValue(line.substring(8)));
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
        String digest = sha256(file);
        return new SieImportPlan(file, type, sourceMarkedImported, historyContains(digest), digest,
                accounts, vouchers, transactions, vouchersOnly);
    }

    public SieImportPlan importFile(Path input, boolean vouchersOnly, boolean allowAlreadyImported)
            throws IOException, SSImportException {
        SieImportPlan plan = inspect(input, vouchersOnly);
        if (plan.previouslyImported() && !allowAlreadyImported) {
            throw new IOException("Identical SIE content was already imported");
        }
        SSSIEImporter importer = new SSSIEImporter(plan.file().toFile(), false);
        if (vouchersOnly) {
            importer.doImportVouchers();
        } else {
            importer.doImport();
        }
        appendHistory(plan);
        return plan;
    }

    private boolean historyContains(String digest) throws IOException {
        Path history = dataDirectory.resolve(HISTORY_FILE);
        if (!Files.exists(history)) {
            return false;
        }
        String prefix = digest + "\t";
        try (var lines = Files.lines(history, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> line.equals(digest) || line.startsWith(prefix));
        }
    }

    private void appendHistory(SieImportPlan plan) throws IOException {
        Files.createDirectories(dataDirectory);
        String row = plan.sha256() + "\t" + Instant.now() + "\t" + plan.type() + "\t"
                + plan.vouchersOnly() + System.lineSeparator();
        Files.writeString(dataDirectory.resolve(HISTORY_FILE), row, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String firstValue(String value) {
        String trimmed = value.trim();
        int separator = trimmed.indexOf(' ');
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }
}
