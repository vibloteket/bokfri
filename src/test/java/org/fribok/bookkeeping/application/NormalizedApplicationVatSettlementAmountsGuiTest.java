package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationVatSettlementAmountsGuiTest {
    @TempDir Path dir;

    @Test
    void ordinaryCentExactProposalShowsLimitedNoAmountFindingsWarningWithoutPostingApprovalTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var before = f.snapshot();
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String value = edt(() -> h.area("header").getText());
                assertTrue(value.contains(text("vatamountnofindings")), value);
                assertTrue(value.contains("NOT posting approval"), value);
                assertTrue(value.contains("NOT POSTED"), value);
                assertEquals(4, edt(() -> h.table().getRowCount()));
                assertEquals("1", String.valueOf(edt(() -> h.table().getValueAt(2, 3))));
                assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void fractionalFindingsAndExactValuesExportFrozenAfterRepairAndSourceRecheckWithoutSqlTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            NormalizedApplicationVatSettlementAmountsTest.fractional(f);
            Path output = dir.resolve("fractional.pdf");
            try (var h = new Host(owner.select(71, 11), output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String old = edt(() -> h.area("header").getText());
                assertTrue(old.contains(text("vatamountfinding.FRACTIONAL_CENT")), old);
                assertTrue(old.contains(text("vatamountproposedrow") + " 1"), old);
                assertTrue(old.contains(text("vatamountproposedrow") + " 4"), old);
                assertTrue(old.contains("0.991"), old);
                assertTrue(old.contains("0.981"), old);
                NormalizedApplicationVatSettlementAmountsTest.repair(f);
                h.click("check-sources");
                h.waitStatus("vatsourceschanged");
                assertEquals(old, edt(() -> h.area("header").getText()));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String value = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
                    assertTrue(
                            value.contains(
                                    text("vatamountfinding.FRACTIONAL_CENT")
                                            .replaceAll("\\s+", "")),
                            value);
                    assertTrue(
                            value.contains(
                                    (text("vatamountproposedrow") + " 1").replaceAll("\\s+", "")),
                            value);
                    assertTrue(
                            value.contains(
                                    (text("vatamountproposedrow") + " 4").replaceAll("\\s+", "")),
                            value);
                    assertTrue(value.contains("0.991"), value);
                    assertTrue(value.contains("0.981"), value);
                    assertTrue(value.contains("NOTPOSTED"), value);
                    assertFalse(
                            value.contains(text("vatsourceschanged").replaceAll("\\s+", "")),
                            value);
                }
                h.generate(FROM, TO);
                String fresh = edt(() -> h.area("header").getText());
                assertTrue(fresh.contains(text("vatamountnofindings")), fresh);
                assertFalse(fresh.contains(text("vatamountfinding.FRACTIONAL_CENT")), fresh);
            }
        }
    }

    @Test
    void totalEntryRangeFindingsAreLabelledAsTotalsNotSqlSideOrSourceRowFailuresTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementAmountsTest.totalsOnly(f);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String value = edt(() -> h.area("header").getText());
                assertTrue(value.contains(text("vatamountfinding.TOTAL_ENTRY_RANGE")), value);
                assertTrue(value.contains(text("vatamountproposedtotal")), value);
                assertTrue(value.contains(text("debit")), value);
                assertTrue(value.contains(text("credit")), value);
                assertFalse(value.contains(text("vatamountfinding.STORAGE_RANGE")), value);
                assertFalse(value.contains(text("vatamountproposedrow") + " 1"), value);
                assertEquals(4, edt(() -> h.table().getRowCount()));
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
                assertEquals(value, edt(() -> h.area("header").getText()));
                assertTrue(edt(() -> h.area("status").getText().contains("NOT posting approval")));
            }
        }
    }

    @Test
    void failedRecheckClearsAmountWarningsRowsReviewAndExportUntilExplicitGenerationTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            NormalizedApplicationVatSettlementAmountsTest.fractional(f);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                observed.failure = "query";
                h.click("check-sources");
                h.waitStatus("failed");
                assertEquals("", edt(() -> h.area("header").getText()));
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertFalse(edt(() -> h.button("check-sources").isEnabled()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                assertEquals(0, observed.commits.get());
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                NormalizedApplicationVatSettlementAmountsTest.repair(f);
                h.generate(FROM, TO);
                assertTrue(
                        edt(
                                () ->
                                        h.area("header")
                                                .getText()
                                                .contains(text("vatamountnofindings"))));
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedFinancialReportsPanel panel;

        Host(NormalizedApplicationOwner.Selection lease, Path output) throws Exception {
            frame = edt(JFrame::new);
            panel =
                    edt(
                            () -> {
                                var p =
                                        new NormalizedFinancialReportsPanel(
                                                parent -> output,
                                                new FinancialReportSnapshotPdf()::export);
                                p.replaceSelection(lease);
                                frame.add(p);
                                frame.setSize(1400, 1100);
                                frame.setVisible(true);
                                return p;
                            });
        }

        JButton button(String key) {
            return (JButton) find(frame, "financial-report-" + key);
        }

        JComboBox<?> kind() {
            return (JComboBox<?>) find(frame, "financial-report-kind");
        }

        JComboBox<?> account() {
            return (JComboBox<?>) find(frame, "financial-report-account");
        }

        JTextField field(String key) {
            return (JTextField) find(frame, "financial-report-" + key);
        }

        JTable table() {
            return (JTable) find(frame, "financial-report-rows");
        }

        JTextArea area(String key) {
            return (JTextArea) find(frame, "financial-report-" + key);
        }

        void inputs(LocalDate from, LocalDate to) throws Exception {
            edt(
                    () -> {
                        kind().setSelectedIndex(7);
                        field("from").setText(from.toString());
                        field("to").setText(to.toString());
                        return null;
                    });
        }

        void generate(LocalDate from, LocalDate to) throws Exception {
            inputs(from, to);
            click("generate");
            waitStatus("generated");
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
        fail("Timed out waiting for VAT report UI");
    }
}
