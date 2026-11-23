package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.accounts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedFinancialReportsPanel;
import org.fribok.bookkeeping.service.report.FinancialReportSnapshotPdf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Exact displayed snapshots, explicit paths, off-EDT writes and fenced export callbacks. */
@Tag("integration")
class NormalizedApplicationReportSnapshotPdfGuiTest {
    @TempDir Path dir;

    @Test
    void allFourGeneratedKindsExportRealPdfWithoutOpeningSqlTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            accounts(f);
            for (int kind = 1; kind <= 4; kind++) {
                Path output = dir.resolve("kind-" + kind + ".pdf");
                try (var h =
                        new Host(
                                owner.select(71, 11),
                                p -> {
                                    assertTrue(SwingUtilities.isEventDispatchThread());
                                    return output;
                                },
                                (snapshot, path) -> {
                                    assertFalse(SwingUtilities.isEventDispatchThread());
                                    return new FinancialReportSnapshotPdf().export(snapshot, path);
                                })) {
                    assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                    h.generate(kind);
                    String header = edt(() -> h.area("header").getText());
                    int opens = f.opens.get();
                    h.click("export-pdf");
                    h.waitStatus("pdfsaved");
                    assertEquals(opens, f.opens.get());
                    assertEquals(header, edt(() -> h.area("header").getText()));
                    try (var pdf = Loader.loadPDF(output.toFile())) {
                        String text = new PDFTextStripper().getText(pdf);
                        assertTrue(text.contains("71 / 11"), text);
                        assertTrue(text.contains("2026-06"), text);
                        assertTrue(text.contains("not a fresh database read"), text);
                    }
                }
            }
        }
    }

    @Test
    void laterPostingDoesNotSilentlyChangeExportedDisplayedSnapshotTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var captured = new AtomicReference<FinancialReportSnapshotPdf.Snapshot>();
            Path output = dir.resolve("old.pdf");
            try (var h =
                    new Host(
                            lease,
                            p -> output,
                            (snapshot, path) -> {
                                captured.set(snapshot);
                                return new FinancialReportSnapshotPdf().export(snapshot, path);
                            })) {
                h.generate(3);
                lease.vouchers()
                        .post(
                                lease.vouchers()
                                        .preview(
                                                NormalizedApplicationFinancialReportsTest.draft(
                                                        java.time.LocalDate.of(2026, 6, 15), "1")));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                assertEquals(
                        0,
                        new java.math.BigDecimal(captured.get().rows().get(0).get(2))
                                .compareTo(java.math.BigDecimal.TEN));
                assertTrue(Files.exists(output));
            }
        }
    }

    @Test
    void chooserCancelDoesNotWriteOrClearGeneratedSnapshotTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var writes = new AtomicInteger();
            try (var h =
                    new Host(
                            owner.select(71, 11),
                            p -> null,
                            (s, p) -> {
                                writes.incrementAndGet();
                                return p;
                            })) {
                h.generate(3);
                String header = edt(() -> h.area("header").getText());
                int opens = f.opens.get();
                h.click("export-pdf");
                assertEquals(0, writes.get());
                assertEquals(opens, f.opens.get());
                assertEquals(header, edt(() -> h.area("header").getText()));
                assertTrue(edt(() -> h.button("export-pdf").isEnabled()));
            }
        }
    }

    @Test
    void inputKindAndContextChangesInvalidateExportPermissionTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            try (var h =
                    new Host(
                            owner.select(71, 11),
                            p -> dir.resolve("no.pdf"),
                            new FinancialReportSnapshotPdf()::export)) {
                h.generate(3);
                edt(
                        () -> {
                            h.field("to").setText("2026-06-15");
                            return null;
                        });
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                h.generate(3);
                edt(
                        () -> {
                            h.kind().setSelectedIndex(4);
                            return null;
                        });
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                h.generate(4);
                var next = owner.select(72, 12);
                edt(
                        () -> {
                            h.panel.replaceSelection(next);
                            return null;
                        });
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                assertFalse(Files.exists(dir.resolve("no.pdf")));
            }
        }
    }

    @Test
    void modalChooserContextRetirementAbortsBeforeWriterTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var ref = new AtomicReference<NormalizedFinancialReportsPanel>();
            var writes = new AtomicInteger();
            try (var h =
                    new Host(
                            owner.select(71, 11),
                            p -> {
                                ref.get().replaceSelection(null);
                                return dir.resolve("no.pdf");
                            },
                            (s, p) -> {
                                writes.incrementAndGet();
                                return p;
                            })) {
                ref.set(h.panel);
                h.generate(3);
                h.click("export-pdf");
                assertEquals(0, writes.get());
                assertFalse(Files.exists(dir.resolve("no.pdf")));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
            }
        }
    }

    @Test
    void existingDestinationNeverOverwrittenAndExplicitRetryCanSucceedTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var destination = new AtomicReference<>(dir.resolve("existing.pdf"));
            Files.writeString(destination.get(), "KEEP");
            try (var h =
                    new Host(
                            owner.select(71, 11),
                            p -> destination.get(),
                            new FinancialReportSnapshotPdf()::export)) {
                h.generate(3);
                h.click("export-pdf");
                h.waitStatus("pdffailed");
                assertEquals("KEEP", Files.readString(destination.get()));
                assertTrue(edt(() -> h.button("export-pdf").isEnabled()));
                destination.set(dir.resolve("new.pdf"));
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertTrue(Files.exists(destination.get()));
            }
        }
    }

    @Test
    void acceptedExportFinishesOriginalSnapshotAfterPanelClosureWithoutLateUiUpdateTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            Path output = dir.resolve("accepted.pdf");
            try (var h =
                    new Host(
                            owner.select(71, 11),
                            p -> output,
                            (s, p) -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new java.io.IOException("timeout");
                                    return new FinancialReportSnapshotPdf().export(s, p);
                                } finally {
                                    finished.countDown();
                                }
                            })) {
                h.generate(3);
                try {
                    h.click("export-pdf");
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertFalse(edt(() -> h.button("generate").isEnabled()));
                    assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                    edt(
                            () -> {
                                h.panel.close();
                                return null;
                            });
                } finally {
                    release.countDown();
                }
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertTrue(Files.exists(output));
                edt(() -> null);
                assertEquals(text("closed"), edt(() -> h.area("status").getText()));
                assertEquals("", edt(() -> h.area("header").getText()));
            }
        }
    }

    @Test
    void blockedLeaseDisablesExportWithoutChoosingOrWritingTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var choices = new AtomicInteger();
            try (var h =
                    new Host(
                            lease,
                            p -> {
                                choices.incrementAndGet();
                                return dir.resolve("no.pdf");
                            },
                            new FinancialReportSnapshotPdf()::export)) {
                h.generate(3);
                var token =
                        lease.vouchers()
                                .preview(
                                        NormalizedApplicationFinancialReportsTest.draft(
                                                java.time.LocalDate.of(2026, 6, 15), "1"));
                f.failure = "after";
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().post(token));
                edt(
                        () -> {
                            h.panel.setHostAvailable(false);
                            return null;
                        });
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                assertEquals(0, choices.get());
                assertFalse(Files.exists(dir.resolve("no.pdf")));
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedFinancialReportsPanel panel;

        Host(
                NormalizedApplicationOwner.Selection lease,
                NormalizedFinancialReportsPanel.DestinationChooser chooser,
                NormalizedFinancialReportsPanel.SnapshotWriter writer)
                throws Exception {
            frame = edt(JFrame::new);
            panel =
                    edt(
                            () -> {
                                var p = new NormalizedFinancialReportsPanel(chooser, writer);
                                p.replaceSelection(lease);
                                frame.add(p);
                                frame.setSize(1400, 1100);
                                frame.setVisible(true);
                                return p;
                            });
        }

        JButton button(String k) {
            return (JButton) find(frame, "financial-report-" + k);
        }

        JComboBox<?> kind() {
            return (JComboBox<?>) find(frame, "financial-report-kind");
        }

        JTextField field(String k) {
            return (JTextField) find(frame, "financial-report-" + k);
        }

        JTextArea area(String k) {
            return (JTextArea) find(frame, "financial-report-" + k);
        }

        void click(String key) throws Exception {
            edt(
                    () -> {
                        assertTrue(button(key).isEnabled(), key);
                        button(key).doClick();
                        return null;
                    });
        }

        void waitStatus(String key) throws Exception {
            await(() -> !panel.isBusy() && area("status").getText().startsWith(text(key)));
        }

        void generate(int kind) throws Exception {
            edt(
                    () -> {
                        kind().setSelectedIndex(kind);
                        field("from").setText("2026-06-01");
                        field("to").setText("2026-06-30");
                        return null;
                    });
            if (kind == 2) {
                click("load");
                waitStatus("loaded");
                edt(
                        () -> {
                            ((JComboBox<?>) find(frame, "financial-report-account"))
                                    .setSelectedIndex(1);
                            return null;
                        });
            }
            click("generate");
            waitStatus("generated");
        }

        public void close() throws Exception {
            edt(
                    () -> {
                        panel.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static Component find(Container root, String name) {
        for (var c : root.getComponents()) {
            if (name.equals(c.getName())) return c;
            if (c instanceof Container child) {
                var found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.financialreports." + key);
    }

    private static <T> T edt(Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        var task = new FutureTask<T>(call);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for snapshot PDF UI");
    }
}
