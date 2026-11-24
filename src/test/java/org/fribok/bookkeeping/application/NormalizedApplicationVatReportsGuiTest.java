package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.CountDownLatch;
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
class NormalizedApplicationVatReportsGuiTest {
    @TempDir Path dir;

    @Test
    void explicitPeriodVatKindDisplaysAllBoxesAndRoundingMeaningWithoutSqlChangesTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("vat.pdf"));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.kind().setSelectedIndex(5);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                assertFalse(edt(() -> h.button("generate").isEnabled()));
                assertFalse(edt(() -> h.button("load").isEnabled()));
                assertFalse(edt(() -> h.account().isEnabled()));
                var before = f.snapshot();
                h.generate(FROM, TO);
                assertEquals(16, edt(() -> h.table().getRowCount()));
                assertEquals(3, edt(() -> h.table().getColumnCount()));
                assertEquals(
                        0,
                        new java.math.BigDecimal("15")
                                .compareTo(
                                        edt(
                                                () ->
                                                        (java.math.BigDecimal)
                                                                h.table().getValueAt(15, 2))));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("vatmeaning")));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("vatnet")));
                assertFalse(edt(() -> h.table().isCellEditable(0, 2)));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void realVatSnapshotPdfExportsWithoutSqlAndKeepsOriginalAfterLaterPostingTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            Path output = dir.resolve("vat.pdf");
            try (var h = new Host(lease, output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                lease.vouchers().post(lease.vouchers().preview(draft(DAY)));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String text = new PDFTextStripper().getText(pdf);
                    for (String expected :
                            java.util.List.of(
                                    "VAT report",
                                    "71 / 11",
                                    "2026-06-01",
                                    "VAT box: 49",
                                    "Whole-unit amount: 15",
                                    "truncated toward zero"))
                        assertTrue(text.contains(expected), text);
                }
                assertEquals(
                        0,
                        new java.math.BigDecimal("30")
                                .compareTo(
                                        lease.reports()
                                                .vatReport(FROM, TO)
                                                .report()
                                                .vatToPayOrRefund()));
            }
        }
    }

    @Test
    void editsClearResultsAndInvalidDatesDoNotReadStorageTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.field("from").setText("bad");
                            return null;
                        });
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(opens, f.opens.get());
                h.inputs(LocalDate.of(2025, 1, 1), TO);
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(0, edt(() -> h.table().getRowCount()));
                h.inputs(TO, FROM);
                h.click("generate");
                h.waitStatus("failed");
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
            }
        }
    }

    @Test
    void inclusiveDayAndExactReversalUseFreshExplicitGenerateTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease, dir.resolve("unused.pdf"))) {
                h.generate(DAY, DAY);
                money("15", edt(() -> h.table().getValueAt(15, 2)));
                long id = lease.voucherRegister().page(null).headers().get(0).id();
                lease.vouchers()
                        .postReversal(
                                lease.vouchers()
                                        .previewReversal(id, DAY.plusDays(1), "Reverse VAT"));
                h.generate(FROM, TO);
                money("0", edt(() -> h.table().getValueAt(15, 2)));
                h.generate(DAY.plusDays(1), DAY.plusDays(1));
                money("-15", edt(() -> h.table().getValueAt(15, 2)));
            }
        }
    }

    @Test
    void currentMappingsRefreshOnlyOnGenerateAndForeignContextClearsChoicesTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                try (var c = f.open()) {
                    c.createStatement()
                            .executeUpdate(
                                    "UPDATE account SET vat_code='U2',active=FALSE WHERE"
                                        + " number=2610");
                }
                money("25", edt(() -> h.table().getValueAt(1, 2)));
                h.generate(FROM, TO);
                money("0", edt(() -> h.table().getValueAt(1, 2)));
                money("25", edt(() -> h.table().getValueAt(2, 2)));
                var foreign = owner.select(72, 12);
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.panel.replaceSelection(foreign);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                assertEquals(0, edt(() -> h.table().getRowCount()));
                h.generate(FROM, TO);
                money("0", edt(() -> h.table().getValueAt(15, 2)));
                assertEquals(16, edt(() -> h.table().getRowCount()));
            }
        }
    }

    @Test
    void queryFailureClearsOldResultsWithoutCommitBlockAndExplicitRetrySucceedsTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            accounts(f);
            try (var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                observed.failure = "query";
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                h.click("generate");
                h.waitStatus("generated");
                assertEquals(16, edt(() -> h.table().getRowCount()));
                assertEquals(0, observed.commits.get());
            }
        }
    }

    @Test
    void pendingVatReadInterlocksAndCloseFencesLateCallbacksTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            accounts(f);
            try (var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.inputs(FROM, TO);
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                int closes = observed.closes.get();
                try {
                    h.click("generate");
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    assertFalse(edt(() -> h.kind().isEnabled()));
                    assertFalse(edt(() -> h.button("generate").isEnabled()));
                    assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                    edt(
                            () -> {
                                h.panel.close();
                                return null;
                            });
                } finally {
                    observed.release.countDown();
                }
                await(() -> observed.closes.get() > closes);
                edt(() -> null);
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertEquals(text("closed"), edt(() -> h.area("status").getText()));
            }
        }
    }

    @Test
    void ownerUncertaintyBlocksVatAndRetirementClearsExportableSnapshotTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease, dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                var token = lease.vouchers().preview(draft(DAY));
                f.failure = "after";
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().post(token));
                int opens = f.opens.get();
                assertThrows(
                        java.sql.SQLException.class, () -> lease.reports().vatReport(FROM, TO));
                assertEquals(opens, f.opens.get());
                edt(
                        () -> {
                            h.panel.close();
                            return null;
                        });
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals(text("blocked"), edt(() -> h.area("status").getText()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
            }
        }
    }

    private static void money(String expected, Object actual) {
        assertEquals(
                0, new java.math.BigDecimal(expected).compareTo((java.math.BigDecimal) actual));
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
                        kind().setSelectedIndex(5);
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
