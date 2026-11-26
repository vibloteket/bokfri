package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
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
class NormalizedApplicationVatSettlementPreviewGuiTest {
    @TempDir Path dir;

    @Test
    void explicitProposalShowsExactRowsDeclaredControlAndWarningsWithoutSqlWritesTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                assertEquals(8, edt(() -> h.kind().getItemCount()));
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.kind().setSelectedIndex(7);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                assertFalse(edt(() -> h.account().isEnabled()));
                assertFalse(edt(() -> h.button("load").isEnabled()));
                var before = f.snapshot();
                h.generate(FROM, TO);
                assertEquals(4, edt(() -> h.table().getRowCount()));
                assertEquals(6, edt(() -> h.table().getColumnCount()));
                assertEquals("null", edt(() -> h.table().getValueAt(0, 4)));
                assertEquals("null", edt(() -> h.table().getValueAt(0, 5)));
                assertEquals(2650, edt(() -> h.table().getValueAt(2, 0)));
                money("1", edt(() -> h.table().getValueAt(2, 3)));
                assertEquals("", edt(() -> h.table().getValueAt(2, 2)));
                money("0.98", edt(() -> h.table().getValueAt(3, 3)));
                assertTrue(
                        edt(() -> h.area("header").getText())
                                .contains(text("vatsettlementmeaning")));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("vatnet") + " 1"));
                assertFalse(edt(() -> h.table().isCellEditable(0, 2)));
                assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void realProposalPdfKeepsDisplayedControlAfterLaterPostingWithoutAnySqlReadTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            Path output = dir.resolve("proposal.pdf");
            try (var h = new Host(lease, output);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate(FROM, TO);
                lease.vouchers().post(lease.vouchers().preview(draft(DAY)));
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                try (var pdf = Loader.loadPDF(output.toFile())) {
                    String value = new PDFTextStripper().getText(pdf);
                    for (String expected :
                            java.util.List.of(
                                    "NOT POSTED",
                                    "No voucher number",
                                    "71 / 11",
                                    "2026-06-30",
                                    text("account") + ": 2650",
                                    text("credit") + ": 1",
                                    text("credit") + ": 0.98",
                                    "VAT payable")) assertTrue(value.contains(expected), value);
                    assertFalse(value.contains(text("vatcompat")), value);
                }
                money("1", edt(() -> h.table().getValueAt(2, 3)));
                money(
                        "3",
                        lease.reports().vatSettlementPreview(FROM, TO).report().vatToPayOrRefund());
            }
        }
    }

    @Test
    void editsInvalidPeriodsAndMissingRolesClearExportableProposalWithoutPostingTest()
            throws Exception {
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
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(opens, f.opens.get());
                h.inputs(LocalDate.of(2025, 1, 1), TO);
                h.click("generate");
                h.waitStatus("failed");
                try (var c = f.open()) {
                    c.createStatement()
                            .executeUpdate("UPDATE account SET vat_code=NULL WHERE number=3740");
                }
                h.inputs(FROM, TO);
                h.click("generate");
                h.waitStatus("failed");
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                edt(
                        () -> {
                            h.kind().setSelectedIndex(6);
                            return null;
                        });
                h.click("generate");
                h.waitStatus("generated");
                assertEquals(16, edt(() -> h.table().getRowCount()));
            }
        }
    }

    @Test
    void exactReversalShowsEmptyZeroProposalAndInclusiveRefundWithoutAssigningNumberTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease, dir.resolve("unused.pdf"))) {
                h.generate(DAY, DAY);
                money("1", edt(() -> h.table().getValueAt(2, 3)));
                long id = lease.voucherRegister().page(null).headers().get(0).id();
                lease.vouchers()
                        .postReversal(
                                lease.vouchers()
                                        .previewReversal(id, DAY.plusDays(1), "Reverse VAT"));
                h.generate(FROM, TO);
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("vatnet") + " 0"));
                h.generate(DAY.plusDays(1), DAY.plusDays(1));
                assertEquals(1650, edt(() -> h.table().getValueAt(2, 0)));
                money("1", edt(() -> h.table().getValueAt(2, 2)));
                money("0.98", edt(() -> h.table().getValueAt(3, 2)));
            }
        }
    }

    @Test
    void currentMappingsRefreshOnlyExplicitlyAndForeignReplacementClearsProposalTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                try (var c = f.open()) {
                    c.createStatement()
                            .executeUpdate(
                                    "UPDATE account SET"
                                            + " vat_code='U2',active=FALSE,description='<html>new'"
                                            + " WHERE number=2610");
                }
                assertTrue(edt(() -> h.table().getValueAt(0, 1).toString()).contains("Output VAT"));
                h.generate(FROM, TO);
                assertEquals(3, edt(() -> h.table().getRowCount()));
                assertEquals(quote("<html>new"), edt(() -> h.table().getValueAt(0, 1)));
                money("1.98", edt(() -> h.table().getValueAt(2, 3)));
                assertEquals(3740, edt(() -> h.table().getValueAt(2, 0)));
                var foreign = owner.select(72, 12);
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.panel.replaceSelection(foreign);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                h.generate(FROM, TO);
                assertEquals(0, edt(() -> h.table().getRowCount()));
            }
        }
    }

    @Test
    void queryFailureClearsProposalAndExplicitRetryDoesNotCommitTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
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
                assertEquals(4, edt(() -> h.table().getRowCount()));
                assertEquals(0, observed.commits.get());
            }
        }
    }

    @Test
    void pendingProposalReadInterlocksAndCloseFencesLateCallbacksTest() throws Exception {
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
                assertEquals(text("closed"), edt(() -> h.area("status").getText()));
            }
        }
    }

    @Test
    void uncertainCommitBlocksPreviewAndClearsItsExportableSnapshotTest() throws Exception {
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
                        java.sql.SQLException.class,
                        () -> lease.reports().vatSettlementPreview(FROM, TO));
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

    @Test
    void policyChangeDuringPendingPreviewCannotRelabelOldRowsOrPdfTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate(FROM, TO);
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                try {
                    h.click("generate");
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    edt(
                            () -> {
                                h.kind().setSelectedIndex(5);
                                return null;
                            });
                    assertEquals(0, edt(() -> h.table().getRowCount()));
                } finally {
                    observed.release.countDown();
                }
                h.waitStatus("instructions");
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(edt(() -> h.button("export-pdf").isEnabled()));
                observed.entered = null;
                observed.release = null;
                h.click("generate");
                h.waitStatus("generated");
                assertEquals(16, edt(() -> h.table().getRowCount()));
                money("0", edt(() -> h.table().getValueAt(15, 2)));
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
