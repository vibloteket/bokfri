package org.fribok.bookkeeping.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class FinancialReportSnapshotPdfTest {
    @TempDir Path dir;

    static FinancialReportSnapshotPdf.Snapshot snapshot() {
        return new FinancialReportSnapshotPdf.Snapshot(
                "Income statement",
                "Company 71 / year 11 / Ångström\n"
                    + "2026-06-01 - 2026-06-30\n"
                    + "Period result: 100.250000000000",
                List.of("Account", "Description", "Amount"),
                List.of(List.of("3010", "CURRENT <html> & literal", "100.250000000000")));
    }

    @Test
    void validPdfPreservesVisibleScopeExactDecimalsLabelsAndRowsTest() throws Exception {
        Path output =
                new FinancialReportSnapshotPdf().export(snapshot(), dir.resolve("report.pdf"));
        try (var pdf = Loader.loadPDF(output.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            for (String expected :
                    List.of(
                            "Income statement",
                            "Company 71 / year 11 / Ångström",
                            "2026-06-01 - 2026-06-30",
                            "Period result: 100.250000000000",
                            "Account: 3010",
                            "Description: CURRENT <html> & literal",
                            "Amount: 100.250000000000")) assertTrue(text.contains(expected), text);
        }
        try (var files = Files.list(dir)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void emptyReportStillContainsScopeAndLargeReportsPaginateWithoutLosingLastRowTest()
            throws Exception {
        var rows = new ArrayList<List<String>>();
        for (int i = 0; i < 100; i++) rows.add(List.of("row-" + i, "value-" + i));
        var data =
                new FinancialReportSnapshotPdf.Snapshot(
                        "Balance sheet",
                        "Company 71 / year 11 / difference 10",
                        List.of("Account", "Amount"),
                        rows);
        Path output = new FinancialReportSnapshotPdf().export(data, dir.resolve("large.pdf"));
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(pdf.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Account: row-99"));
            assertTrue(text.contains("Amount: value-99"));
        }
        Path empty =
                new FinancialReportSnapshotPdf()
                        .export(
                                new FinancialReportSnapshotPdf.Snapshot(
                                        "Empty",
                                        "Company 72 / year 12 / result 0",
                                        List.of("Account"),
                                        List.of()),
                                dir.resolve("empty.pdf"));
        try (var pdf = Loader.loadPDF(empty.toFile())) {
            assertTrue(
                    new PDFTextStripper().getText(pdf).contains("Company 72 / year 12 / result 0"));
        }
    }

    @Test
    void snapshotIsDeeplyImmutableAndRejectsIncompleteShapesTest() {
        var row = new ArrayList<>(List.of("original"));
        var columns = new ArrayList<>(List.of("Account"));
        var rows = new ArrayList<List<String>>();
        rows.add(row);
        var snapshot = new FinancialReportSnapshotPdf.Snapshot("title", "header", columns, rows);
        row.set(0, "changed");
        columns.set(0, "changed");
        rows.clear();
        assertEquals("Account", snapshot.columns().get(0));
        assertEquals("original", snapshot.rows().get(0).get(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().get(0).clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FinancialReportSnapshotPdf.Snapshot(
                                "title", "header", List.of("a"), List.of(List.of("b", "c"))));
    }

    @Test
    void existingFilesDirectoriesAndSymlinksAreNeverOverwrittenTest() throws Exception {
        var exporter = new FinancialReportSnapshotPdf();
        Path output = dir.resolve("old.pdf");
        Files.writeString(output, "ORIGINAL");
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> exporter.export(snapshot(), output));
        assertEquals("ORIGINAL", Files.readString(output));
        Path directory = Files.createDirectory(dir.resolve("directory.pdf"));
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> exporter.export(snapshot(), directory));
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            Path link = dir.resolve("broken.pdf");
            Files.createSymbolicLink(link, dir.resolve("absent"));
            assertThrows(
                    java.nio.file.FileAlreadyExistsException.class,
                    () -> exporter.export(snapshot(), link));
            assertTrue(Files.isSymbolicLink(link));
        }
    }

    @Test
    void destinationCreatedDuringRenderWinsWithoutModificationAndTempIsRemovedTest()
            throws Exception {
        Path output = dir.resolve("race.pdf");
        var exporter =
                new FinancialReportSnapshotPdf(
                        (snapshot, temp) -> {
                            Files.writeString(temp, "candidate");
                            Files.writeString(output, "OTHER WRITER");
                        });
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> exporter.export(snapshot(), output));
        assertEquals("OTHER WRITER", Files.readString(output));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void renderingFailureNeverPublishesPartialOutputAndCleansTemporaryFileTest() throws Exception {
        Path output = dir.resolve("failure.pdf");
        var exporter =
                new FinancialReportSnapshotPdf(
                        (snapshot, temp) -> {
                            Files.writeString(temp, "partial");
                            throw new IOException("render failed");
                        });
        assertThrows(IOException.class, () -> exporter.export(snapshot(), output));
        assertFalse(Files.exists(output));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void twoConcurrentExportsPublishExactlyOneCompleteSnapshotTest() throws Exception {
        Path output = dir.resolve("race.pdf");
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var exporter =
                new FinancialReportSnapshotPdf(
                        (snapshot, temp) -> {
                            Files.writeString(temp, snapshot.header());
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IOException("timeout");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException(e);
                            }
                        });
        var successes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                try {
                                    exporter.export(snapshot(), output);
                                    successes.incrementAndGet();
                                } catch (java.nio.file.FileAlreadyExistsException expected) {
                                }
                                return null;
                            });
            var b =
                    executor.submit(
                            () -> {
                                try {
                                    exporter.export(snapshot(), output);
                                    successes.incrementAndGet();
                                } catch (java.nio.file.FileAlreadyExistsException expected) {
                                }
                                return null;
                            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, successes.get());
        assertEquals(snapshot().header(), Files.readString(output));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    @Test
    void missingDirectoryAndNonPdfNamesAreRejectedWithoutRenderingTest() throws Exception {
        var calls = new AtomicInteger();
        var exporter = new FinancialReportSnapshotPdf((snapshot, temp) -> calls.incrementAndGet());
        assertThrows(
                IOException.class,
                () -> exporter.export(snapshot(), dir.resolve("absent/report.pdf")));
        assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(snapshot(), dir.resolve("database.script")));
        assertEquals(0, calls.get());
        assertFalse(Files.exists(dir.resolve("absent")));
    }
}
