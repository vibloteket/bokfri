package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
class NormalizedApplicationVatSourceRecheckGuiTest {
    @TempDir Path dir;

    static void sql(Fixture f, String statement) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(statement);
        }
    }

    @Test
    void unselectedConstructionAndOtherVatChoicesNeverEnableOrAutomaticallyRunSourceChecksTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            int opens = f.opens.get();
            try (var h = new Host(lease, dir.resolve("unused.pdf"))) {
                assertEquals(opens, f.opens.get());
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                assertFalse(edt(() -> h.button("check-sources").isEnabled()));
                for (int index : List.of(5, 6)) {
                    h.inputs(index);
                    assertEquals(opens, f.opens.get());
                    h.click("generate");
                    h.waitStatus("generated");
                    opens = f.opens.get();
                    assertFalse(edt(() -> h.button("check-sources").isEnabled()));
                    assertTrue(edt(() -> find(h.frame, "financial-report-post") == null));
                }
            }
        }
    }

    @Test
    void
            generateCapturesReviewAndExplicitUnchangedCheckPreservesRowsHeaderAndReadOnlyLifecycleTest()
                    throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                observed.reset();
                int opens = observed.opens.get();
                h.generate();
                assertEquals(opens + 1, observed.opens.get());
                assertEquals(2, observed.rollbacks.get());
                String header = edt(() -> h.area("header").getText());
                var rows = h.cells();
                assertTrue(header.contains(text("vatsourcecaptured")), header);
                assertTrue(edt(() -> h.button("check-sources").isEnabled()));
                assertTrue(edt(() -> h.area("status").getText().startsWith(text("generated"))));
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
                assertEquals(opens + 2, observed.opens.get());
                assertEquals(4, observed.rollbacks.get());
                assertEquals(2, observed.closes.get());
                assertEquals(0, observed.commits.get());
                assertEquals(header, edt(() -> h.area("header").getText()));
                assertEquals(rows, h.cells());
                assertTrue(edt(() -> h.area("status").getText().contains("NOT posting approval")));
                assertTrue(
                        edt(
                                () ->
                                        h.area("status")
                                                .getText()
                                                .contains("a guarantee for a later write")));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void changedSourceWithEqualDisplayedProposalIsDetectedAndOnlyGenerateRefreshesReviewTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate();
                String header = edt(() -> h.area("header").getText());
                var rows = h.cells();
                sql(
                        f,
                        "UPDATE voucher SET description='Changed invisible source' WHERE"
                                + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                + " legacy_id=11)");
                h.click("check-sources");
                h.waitStatus("vatsourceschanged");
                assertEquals(header, edt(() -> h.area("header").getText()));
                assertEquals(rows, h.cells());
                assertTrue(edt(() -> h.button("export-pdf").isEnabled()));
                h.generate();
                assertEquals(rows, h.cells());
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
            }
        }
    }

    @Test
    void unchangedAndChangedChecksReuseIdenticalFrozenPdfSnapshotWithoutSqlOnEitherExportTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            var snapshots = new ArrayList<FinancialReportSnapshotPdf.Snapshot>();
            try (var h =
                            new Host(
                                    lease,
                                    dir.resolve("first.pdf"),
                                    (snapshot, path) -> {
                                        snapshots.add(snapshot);
                                        return new FinancialReportSnapshotPdf()
                                                .export(snapshot, path);
                                    });
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                h.generate();
                var cells = h.cells();
                String header = edt(() -> h.area("header").getText());
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
                int opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                lease.vouchers().post(lease.vouchers().preview(draft(DAY)));
                h.click("check-sources");
                h.waitStatus("vatsourceschanged");
                h.output = dir.resolve("second.pdf");
                opens = f.opens.get();
                h.click("export-pdf");
                h.waitStatus("pdfsaved");
                assertEquals(opens, f.opens.get());
                assertSame(snapshots.get(0), snapshots.get(1));
                assertEquals(cells, h.cells());
                assertEquals(header, edt(() -> h.area("header").getText()));
                for (Path path : List.of(dir.resolve("first.pdf"), dir.resolve("second.pdf"))) {
                    try (var pdf = Loader.loadPDF(path.toFile())) {
                        // The snapshot exporter wraps long header text even within words.
                        String value = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
                        assertTrue(
                                value.contains(text("vatsourcecaptured").replaceAll("\\s+", "")),
                                value);
                        assertTrue(value.contains("NOTPOSTED"), value);
                        assertFalse(
                                value.contains(text("vatsourcesunchanged").replaceAll("\\s+", "")),
                                value);
                        assertFalse(
                                value.contains(text("vatsourceschanged").replaceAll("\\s+", "")),
                                value);
                    }
                }
                assertEquals("1", String.valueOf(edt(() -> h.table().getValueAt(2, 3))));
                assertEquals(
                        0,
                        new java.math.BigDecimal("3")
                                .compareTo(
                                        lease.reports()
                                                .vatSettlementPreview(FROM, TO)
                                                .report()
                                                .vatToPayOrRefund()));
            }
        }
    }

    @Test
    void exactSourceRestorationMayMatchAgainWithoutClaimingTransactionHistoryTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate();
                sql(
                        f,
                        "UPDATE voucher SET description='Temporarily changed' WHERE"
                                + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                + " legacy_id=11)");
                h.click("check-sources");
                h.waitStatus("vatsourceschanged");
                sql(
                        f,
                        "UPDATE voucher SET description='Split VAT source' WHERE"
                                + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                + " legacy_id=11)");
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
                String value = edt(() -> h.area("status").getText());
                assertTrue(value.contains("Exact restoration may match again"), value);
                assertTrue(value.contains("NOT posting approval, transaction history"), value);
            }
        }
    }

    @Test
    void dateAndKindEditsInvalidateReviewExportAndCheckWithoutSqlUntilExplicitGenerateTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                for (String field : List.of("from", "to")) {
                    h.generate();
                    int opens = f.opens.get();
                    edt(
                            () -> {
                                h.field(field)
                                        .setText(
                                                (field.equals("from")
                                                                ? FROM.plusDays(1)
                                                                : TO.minusDays(1))
                                                        .toString());
                                return null;
                            });
                    assertEquals(opens, f.opens.get());
                    h.assertCleared();
                }
                h.generate();
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.kind().setSelectedIndex(6);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                h.assertCleared();
                h.click("generate");
                h.waitStatus("generated");
                assertFalse(edt(() -> h.button("check-sources").isEnabled()));
            }
        }
    }

    @Test
    void explicitSelectionReplacementClearsReviewAndRequiresNewSettlementGenerationTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            try (var h = new Host(owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate();
                var replacement = owner.select(72, 12);
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.panel.replaceSelection(replacement);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                h.assertCleared();
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                h.generate();
                assertTrue(edt(() -> h.button("check-sources").isEnabled()));
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
            }
        }
    }

    @Test
    void failedQueryRollbackOrCloseClearsOldReviewRowsAndExportThenRequiresExplicitGenerateTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String mode : List.of("query", "rollback", "close")) {
            try (var f = new Fixture();
                    var setup = f.owner()) {
                prepared(f, setup);
                try (var observed = new Observed(f);
                        var h =
                                new Host(
                                        observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                    h.generate();
                    observed.failure = mode;
                    h.click("check-sources");
                    h.waitStatus("failed");
                    h.assertCleared();
                    assertFalse(observed.owner.isBlocked());
                    assertEquals(0, observed.commits.get());
                    observed.failure = "";
                    h.generate();
                    h.click("check-sources");
                    h.waitStatus("vatsourcesunchanged");
                }
            }
        }
    }

    @Test
    void pendingCheckDisablesActionsAndRejectsResultAfterProgrammaticDateEditTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                h.click("check-sources");
                try {
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    for (String key : List.of("check-sources", "export-pdf", "generate"))
                        assertFalse(edt(() -> h.button(key).isEnabled()));
                    edt(
                            () -> {
                                h.field("from").setText(FROM.plusDays(1).toString());
                                return null;
                            });
                } finally {
                    observed.release.countDown();
                }
                h.waitStatus("instructions");
                h.assertCleared();
                assertEquals(0, observed.commits.get());
                h.generate();
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
            }
        }
    }

    @Test
    void pendingCheckCannotRestoreSnapshotAfterPanelReplacementOrClosureTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (boolean close : List.of(false, true)) {
            try (var f = new Fixture();
                    var setup = f.owner()) {
                prepared(f, setup);
                try (var observed = new Observed(f);
                        var h =
                                new Host(
                                        observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                    h.generate();
                    int closes = observed.closes.get();
                    observed.entered = new CountDownLatch(1);
                    observed.release = new CountDownLatch(1);
                    h.click("check-sources");
                    try {
                        assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                        edt(
                                () -> {
                                    if (close) h.panel.close();
                                    else h.panel.replaceSelection(null);
                                    return null;
                                });
                    } finally {
                        observed.release.countDown();
                    }
                    await(() -> observed.closes.get() == closes + 1);
                    h.assertCleared();
                    assertTrue(
                            edt(
                                    () ->
                                            h.area("status")
                                                    .getText()
                                                    .startsWith(
                                                            text(
                                                                    close
                                                                            ? "closed"
                                                                            : "instructions"))));
                    assertEquals(0, observed.commits.get());
                }
            }
        }
    }

    @Test
    void hostInterlockDuringPendingCheckDiscardsOutcomeAndRequiresNewReviewTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            try (var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11), dir.resolve("unused.pdf"))) {
                h.generate();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                h.click("check-sources");
                try {
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    edt(
                            () -> {
                                h.panel.setHostAvailable(false);
                                return null;
                            });
                } finally {
                    observed.release.countDown();
                }
                h.waitStatus("instructions");
                h.assertCleared();
                edt(
                        () -> {
                            h.panel.setHostAvailable(true);
                            return null;
                        });
                assertFalse(edt(() -> h.button("check-sources").isEnabled()));
                h.generate();
                h.click("check-sources");
                h.waitStatus("vatsourcesunchanged");
            }
        }
    }

    @Test
    void uncertainPostingOutcomeDisablesSourceCheckAndExportBeforeAnyFurtherSqlTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease, dir.resolve("unused.pdf"))) {
                h.generate();
                var token = lease.vouchers().preview(draft(DAY));
                f.failure = "after";
                assertThrows(SQLException.class, () -> lease.vouchers().post(token));
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.panel.setHostAvailable(true);
                            h.button("check-sources").doClick();
                            return null;
                        });
                for (String key : List.of("check-sources", "export-pdf", "generate"))
                    assertFalse(edt(() -> h.button(key).isEnabled()));
                assertEquals(opens, f.opens.get());
                assertTrue(owner.isBlocked());
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedFinancialReportsPanel panel;
        volatile Path output;

        Host(NormalizedApplicationOwner.Selection lease, Path output) throws Exception {
            this(lease, output, new FinancialReportSnapshotPdf()::export);
        }

        Host(
                NormalizedApplicationOwner.Selection lease,
                Path output,
                NormalizedFinancialReportsPanel.SnapshotWriter writer)
                throws Exception {
            this.output = output;
            frame = edt(JFrame::new);
            panel =
                    edt(
                            () -> {
                                var p =
                                        new NormalizedFinancialReportsPanel(
                                                parent -> this.output, writer);
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

        JTextField field(String key) {
            return (JTextField) find(frame, "financial-report-" + key);
        }

        JTextArea area(String key) {
            return (JTextArea) find(frame, "financial-report-" + key);
        }

        JTable table() {
            return (JTable) find(frame, "financial-report-rows");
        }

        void inputs(int index) throws Exception {
            edt(
                    () -> {
                        kind().setSelectedIndex(index);
                        field("from").setText(FROM.toString());
                        field("to").setText(TO.toString());
                        return null;
                    });
        }

        void generate() throws Exception {
            inputs(7);
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

        List<List<String>> cells() throws Exception {
            return edt(
                    () -> {
                        var result = new ArrayList<List<String>>();
                        for (int row = 0; row < table().getRowCount(); row++) {
                            var values = new ArrayList<String>();
                            for (int col = 0; col < table().getColumnCount(); col++)
                                values.add(String.valueOf(table().getValueAt(row, col)));
                            result.add(values);
                        }
                        return result;
                    });
        }

        void assertCleared() throws Exception {
            assertEquals(0, edt(() -> table().getRowCount()));
            assertEquals("", edt(() -> area("header").getText()));
            assertFalse(edt(() -> button("check-sources").isEnabled()));
            assertFalse(edt(() -> button("export-pdf").isEnabled()));
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
                var result = find(child, name);
                if (result != null) return result;
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
        fail("Timed out waiting for source-review GUI");
    }
}
