package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatDimensionCatalogueTest.close;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatDimensionCatalogueTest.sql;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.draft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
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
class NormalizedApplicationVatDimensionCatalogueGuiTest {
    @TempDir Path dir;

    @Test
    void closureFindingsAndOldPdfStayFrozenAfterRepairAndLaterPostingWithoutExportSqlTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            close(f, "P1", TO.toString());
            close(f, "P2", null);
            var lease = owner.select(71, 11);
            Path output = dir.resolve("closed.pdf");
            try (var h = new Host(lease, output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String old = edt(() -> h.area("header").getText());
                for (String kind :
                        List.of("PROJECT_CLOSED_ON_DATE", "PROJECT_CLOSURE_DATE_UNKNOWN"))
                    assertTrue(old.contains(text("vatsettlementfinding." + kind)), old);
                assertEquals(2, edt(() -> h.table().getRowCount()));
                assertEquals(6, edt(() -> h.table().getColumnCount()));
                sql(
                        f,
                        "UPDATE project SET concluded=FALSE WHERE company_id=(SELECT id FROM"
                                + " company WHERE legacy_id=71)");
                lease.vouchers().post(lease.vouchers().preview(draft(DAY)));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String value = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
                    for (String key :
                            List.of("PROJECT_CLOSED_ON_DATE", "PROJECT_CLOSURE_DATE_UNKNOWN"))
                        assertTrue(value.contains(text("vatsettlementfinding." + key)), value);
                    for (String key : List.of("P1", "P2", "U1", "NOT POSTED"))
                        assertTrue(value.contains(key), value);
                }
                assertEquals(old, edt(() -> h.area("header").getText()));
                assertTrue(lease.reports().vatSettlementPreview(FROM, TO).findings().isEmpty());
            }
        }
    }

    @Test
    void unknownForeignAndBlankRawKeysAreLiteralAndExportTheirObservationKindsTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            NormalizedApplicationVatDimensionCatalogueTest.foreignAndBlank(f);
            Path output = dir.resolve("unknown.pdf");
            try (var h = new Host(owner.select(71, 11), output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                String value = edt(() -> h.area("header").getText());
                for (String key :
                        List.of(
                                "UNKNOWN_PROJECT",
                                "UNKNOWN_UNIT",
                                "PROJECT_KEY_BLANK",
                                "UNIT_KEY_BLANK"))
                    assertTrue(value.contains(text("vatsettlementfinding." + key)), value);
                assertEquals("\"FOREIGN\"", edt(() -> h.table().getValueAt(0, 4)));
                assertEquals("\"\"", edt(() -> h.table().getValueAt(1, 4)));
                assertEquals("\" \"", edt(() -> h.table().getValueAt(1, 5)));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String result = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
                    for (String key :
                            List.of(
                                    "UNKNOWN_PROJECT",
                                    "UNKNOWN_UNIT",
                                    "PROJECT_KEY_BLANK",
                                    "UNIT_KEY_BLANK"))
                        assertTrue(result.contains(text("vatsettlementfinding." + key)), result);
                }
            }
        }
    }

    @Test
    void explicitRefreshAfterCatalogueRepairClearsFindingsWithoutPostingApprovalTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            close(f, "P1", TO.toString());
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                String old = edt(() -> h.area("header").getText());
                sql(
                        f,
                        "UPDATE project SET concluded=FALSE WHERE company_id=(SELECT id FROM"
                                + " company WHERE legacy_id=71)");
                assertEquals(old, edt(() -> h.area("header").getText()));
                h.generate(FROM, TO);
                String fresh = edt(() -> h.area("header").getText());
                assertTrue(fresh.contains(text("vatsettlementnofindings")), fresh);
                assertTrue(fresh.contains("NOT POSTED"), fresh);
                assertFalse(
                        fresh.contains(text("vatsettlementfinding.PROJECT_CLOSED_ON_DATE")), fresh);
                assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
            }
        }
    }

    @Test
    void malformedCatalogueFailureClearsPriorRowsWarningsAndPdfThenAllowsExplicitRetryTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVatSettlementDiagnosticsTest.offsetting(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                sql(
                        f,
                        "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                                + " id,'','Bad','Bad',FALSE FROM company WHERE legacy_id=71");
                h.inputs(FROM, TO);
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(
                        edt(
                                () ->
                                        ((JButton) find(h.frame, "financial-report-export-pdf"))
                                                .isEnabled()));
                sql(
                        f,
                        "DELETE FROM project WHERE number='' AND company_id=(SELECT id FROM company"
                                + " WHERE legacy_id=71)");
                h.generate(FROM, TO);
                assertEquals(2, edt(() -> h.table().getRowCount()));
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
