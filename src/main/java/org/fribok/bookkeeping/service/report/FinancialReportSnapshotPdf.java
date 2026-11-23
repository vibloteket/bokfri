package org.fribok.bookkeeping.service.report;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.base.JRBasePrintPage;
import net.sf.jasperreports.engine.base.JRBasePrintText;
import net.sf.jasperreports.engine.type.ModeEnum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** PDF of immutable displayed strings, with atomic no-clobber publication and no storage reads. */
public final class FinancialReportSnapshotPdf {
    public record Snapshot(
            String title, String header, List<String> columns, List<List<String>> rows) {
        public Snapshot {
            Objects.requireNonNull(title, "snapshot title");
            Objects.requireNonNull(header, "snapshot header");
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            int columnCount = columns.size();
            if (title.isBlank()
                    || header.isBlank()
                    || columns.isEmpty()
                    || rows.stream().anyMatch(row -> row.size() != columnCount)) {
                throw new IllegalArgumentException("Complete displayed report snapshot required");
            }
        }
    }

    @FunctionalInterface
    interface Renderer {
        void render(Snapshot snapshot, Path temporary) throws IOException, JRException;
    }

    private final Renderer renderer;

    public FinancialReportSnapshotPdf() {
        this(FinancialReportSnapshotPdf::render);
    }

    FinancialReportSnapshotPdf(Renderer renderer) {
        this.renderer = Objects.requireNonNull(renderer);
    }

    /**
     * Writes a complete sibling temporary file, then atomically creates a new hard link. No
     * move/replace fallback: unsupported hard links fail safely without publishing output. The
     * destination directory must already exist. Accepted exports may finish after UI retirement.
     */
    public Path export(Snapshot snapshot, Path destination) throws IOException, JRException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path output =
                Objects.requireNonNull(destination, "explicit PDF destination")
                        .toAbsolutePath()
                        .normalize();
        if (output.getFileName() == null
                || !output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Explicit .pdf filename required");
        }
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Existing nonsymlink destination directory required");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(output.toString());
        }
        Path temporary = Files.createTempFile(parent, ".bokfri-report-", ".part");
        Throwable failure = null;
        boolean published = false;
        try {
            renderer.render(snapshot, temporary);
            // createLink is atomic and must fail for every existing destination (including
            // symlinks).
            // ATOMIC_MOVE without REPLACE_EXISTING does not guarantee no-clobber on all providers.
            Files.createLink(output, temporary);
            published = true;
            return output;
        } catch (IOException | JRException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else if (published) {
                    org.slf4j.LoggerFactory.getLogger(FinancialReportSnapshotPdf.class)
                            .warn(
                                    "PDF published but temporary-file cleanup failed; do not retry",
                                    cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static void render(Snapshot snapshot, Path destination) throws JRException {
        se.swedsoft.bookkeeping.print.util.SSReportFonts.register();
        JasperPrint print = new JasperPrint();
        print.setName(snapshot.title());
        print.setPageWidth(842);
        print.setPageHeight(595);
        List<String> lines = new ArrayList<>();
        append(lines, snapshot.title());
        append(lines, snapshot.header());
        for (int i = 0; i < snapshot.rows().size(); i++) {
            lines.add("");
            lines.add("#" + (i + 1));
            for (int col = 0; col < snapshot.columns().size(); col++) {
                append(lines, snapshot.columns().get(col) + ": " + snapshot.rows().get(i).get(col));
            }
        }
        final int perPage = 37;
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        for (int page = 0; page < pages; page++) {
            JRBasePrintPage sheet = new JRBasePrintPage();
            print.addPage(sheet);
            int start = page * perPage;
            for (int row = start; row < Math.min(lines.size(), start + perPage); row++) {
                text(print, sheet, lines.get(row), 30 + (row - start) * 14);
            }
            text(print, sheet, (page + 1) + " / " + pages, 565);
        }
        JasperExportManager.exportReportToPdfFile(print, destination.toString());
    }

    private static void append(List<String> lines, String value) {
        for (String paragraph : value.split("\\R", -1)) {
            int[] points = paragraph.codePoints().toArray();
            if (points.length == 0) lines.add("");
            for (int start = 0; start < points.length; start += 76) {
                lines.add(new String(points, start, Math.min(76, points.length - start)));
            }
        }
    }

    private static void text(JasperPrint print, JRBasePrintPage page, String value, int y) {
        JRBasePrintText line = new JRBasePrintText(print.getDefaultStyleProvider());
        line.setX(30);
        line.setY(y);
        line.setWidth(782);
        line.setHeight(14);
        line.setFontName("Bokfri Sans");
        line.setFontSize(9f);
        line.setMode(ModeEnum.TRANSPARENT);
        line.setText(value);
        line.setTextHeight(14f);
        line.setLeadingOffset(0f);
        line.setLineSpacingFactor(1f);
        page.addElement(line);
    }
}
