package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.draft;
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
class NormalizedApplicationVatSettlementDiagnosticsGuiTest {
    @TempDir Path dir;

    @Test
    void concreteAccountFindingsAreDisplayedWithoutChangingArithmeticOrWritingSqlTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            NormalizedApplicationVatSettlementDiagnosticsTest.flags(f);
            var before = f.snapshot();
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String value = edt(() -> h.area("header").getText());
                for (String key :
                        java.util.List.of(
                                "vatsettlementfindings",
                                "vatsettlementfinding.INACTIVE_ACCOUNT",
                                "vatsettlementfinding.REQUIRED_PROJECT_MISSING",
                                "vatsettlementfinding.REQUIRED_UNIT_MISSING"))
                    assertTrue(value.contains(text(key)), value);
                assertTrue(value.contains("2610"));
                assertTrue(value.contains("2650"));
                assertTrue(value.contains("3740"));
                assertEquals(4, edt(() -> h.table().getRowCount()));
                money("1", edt(() -> h.table().getValueAt(2, 3)));
                assertTrue(value.contains("NOT POSTED"));
                assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void netZeroSourceDimensionFindingsExportInOldFrozenPdfWithoutSqlAfterLaterPostingTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            var lease = owner.select(71, 11);
            Path output = dir.resolve("dimensions.pdf");
            try (var h = new Host(lease, output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                assertEquals(0, edt(() -> h.table().getRowCount()));
                String value = edt(() -> h.area("header").getText());
                assertTrue(
                        value.contains(text("vatsettlementfinding.SOURCE_PROJECT_NOT_CARRIED")),
                        value);
                assertTrue(
                        value.contains(text("vatsettlementfinding.SOURCE_UNIT_NOT_CARRIED")),
                        value);
                assertTrue(value.contains(quote("P1")), value);
                assertTrue(value.contains(quote("P2")), value);
                assertTrue(value.contains(text("vatsettlementfindingrow") + " 2"), value);
                lease.vouchers().post(lease.vouchers().preview(draft(DAY)));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String result = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
                    for (String expected :
                            java.util.List.of(
                                    "NOT POSTED",
                                    "P1",
                                    "P2",
                                    "U1",
                                    text("vatsettlementfinding.SOURCE_PROJECT_NOT_CARRIED"),
                                    text("vatsettlementfinding.SOURCE_UNIT_NOT_CARRIED"),
                                    text("vatsettlementfindingrow") + " 2"))
                        assertTrue(result.contains(expected), result);
                }
                assertEquals(0, edt(() -> h.table().getRowCount()));
                money(
                        "1",
                        lease.reports().vatSettlementPreview(FROM, TO).report().vatToPayOrRefund());
            }
        }
    }

    @Test
    void noFindingsMessageNeverClaimsPostingApprovalAndMetadataRefreshIsExplicitTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            NormalizedApplicationVatSettlementDiagnosticsTest.flags(f);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                try (var c = f.open()) {
                    c.createStatement()
                            .executeUpdate(
                                    "UPDATE account SET"
                                        + " active=TRUE,project_required=FALSE,result_unit_required=FALSE"
                                        + " WHERE accounting_year_id=(SELECT id FROM"
                                        + " accounting_year WHERE legacy_id=11)");
                }
                assertTrue(
                        edt(() -> h.area("header").getText())
                                .contains(text("vatsettlementfinding.INACTIVE_ACCOUNT")));
                h.generate(FROM, TO);
                String value = edt(() -> h.area("header").getText());
                assertTrue(value.contains(text("vatsettlementnofindings")), value);
                assertTrue(value.contains("NOT posting approval"), value);
                assertTrue(value.contains("NOT POSTED"), value);
                assertFalse(value.contains("Ready to post"), value);
                assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
            }
        }
    }

    @Test
    void readFailureClearsFindingsAndOldPdfCapabilityBeforeExplicitRetryTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            NormalizedApplicationVatSettlementDiagnosticsTest.flags(f);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                assertTrue(
                        edt(() -> h.area("header").getText())
                                .contains(text("vatsettlementfindings")));
                observed.failure = "query";
                h.click("generate");
                h.waitStatus("failed");
                assertEquals("", edt(() -> h.area("header").getText()));
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                h.click("generate");
                h.waitStatus("generated");
                assertTrue(
                        edt(() -> h.area("header").getText())
                                .contains(text("vatsettlementfindings")));
                assertEquals(0, observed.commits.get());
            }
        }
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static void money(String expected, Object actual) {
        assertEquals(
                0,
                new java.math.BigDecimal(expected)
                        .compareTo(new java.math.BigDecimal(String.valueOf(actual))));
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
