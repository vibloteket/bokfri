package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.*;
import java.util.concurrent.*;

import javax.swing.*;

@Tag("integration")
class NormalizedApplicationVoucherReversalGuiTest {
    @Test
    void explicitPreviewShowsOriginalAndSwappedDimensionsAndPostPreservesContext()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            var before = f.snapshot();
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            assertEquals("", edt(() -> field(host, "source").getText()));
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            host.preview();
            assertEquals(before, f.snapshot());
            String summary = edt(() -> status(host).getText());
            assertTrue(summary.contains("Original SQL identity"));
            assertTrue(summary.contains("New reversal number"));
            var storedDebit = lease.voucherRegister().detail(host.id).rows().get(0).debit();
            assertTrue(summary.contains(storedDebit + " / null"));
            assertTrue(summary.contains("null / " + storedDebit));
            assertTrue(summary.contains("Local project"));
            assertTrue(summary.contains("Local unit"));
            assertFalse(summary.contains("Foreign project"));
            click(host, "post");
            host.posted();
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertEquals("", edt(() -> field(host, "source").getText()));
            assertEquals("", edt(() -> field(host, "date").getText()));
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            assertEquals(
                    0,
                    edt(() -> ((JTable) find(host.frame, "voucher-register-list")).getRowCount()));
        }
    }

    @Test
    void editsInvalidateConfirmationAndMalformedIdentityNeverOpensSql() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            edt(
                    () -> {
                        field(host, "description").setText("changed");
                        return null;
                    });
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            host.preview();
            edt(
                    () -> {
                        field(host, "date").setText("2026-06-17");
                        return null;
                    });
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            host.preview();
            edt(
                    () -> {
                        field(host, "source").setText("1 OR 1");
                        return null;
                    });
            int opens = f.opens.get();
            click(host, "preview");
            host.failed();
            assertEquals(opens, f.opens.get());
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 1);
        }
    }

    @Test
    void staleSourceRefusesPostingAndNewPreviewUsesCurrentOriginalDescription() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE voucher SET description='Updated original' WHERE id="
                                        + host.id);
            }
            var before = f.snapshot();
            click(host, "post");
            host.failed();
            assertEquals(before, f.snapshot());
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            click(host, "preview");
            host.previewed();
            assertTrue(edt(() -> status(host).getText().contains("Updated original")));
            click(host, "post");
            host.posted();
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
        }
    }

    @Test
    void documentLinkedSourceCannotBePreviewedOrChangeAnyData() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            NormalizedApplicationVoucherReversalTest.document(f, "customer_invoice", 71, 1);
            var before = f.snapshot();
            host.fill();
            click(host, "preview");
            host.failed();
            assertTrue(edt(() -> status(host).getText().contains("Document-linked")));
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void contextReplacementClearsSourceAndForeignSqlIdentityCannotBeUsed() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            var old = edt(() -> host.workspace.selection().orElseThrow().vouchers());
            host.apply(2);
            assertEquals("", edt(() -> field(host, "source").getText()));
            assertFalse(edt(() -> button(host, "post").isEnabled()));
            int opens = f.opens.get();
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            old.previewReversal(
                                    host.id, NormalizedApplicationVoucherReversalTest.DATE, "old"));
            assertEquals(opens, f.opens.get());
            host.fill();
            var before = f.snapshot();
            click(host, "preview");
            host.failed();
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void lateRowRollbackCanBePreviewedAgainAndCommitUncertaintyBlocksEverything() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            var before = f.snapshot();
            f.failure = "voucher-row";
            click(host, "post");
            host.failed();
            assertEquals(before, f.snapshot());
            assertFalse(host.owner.isBlocked());
            click(host, "preview");
            host.previewed();
            f.failure = "after";
            click(host, "post");
            await(() -> status(host).getText().equals(text("blocked")));
            assertTrue(host.owner.isBlocked());
            assertFalse(edt(() -> button(host, "preview").isEnabled()));
            assertEquals("", edt(() -> field(host, "source").getText()));
            assertFalse(
                    edt(
                            () ->
                                    ((JButton) find(host.frame, "voucher-register-refresh"))
                                            .isEnabled()));
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
        }
    }

    @Test
    void pendingPostInterlocksHostRegisterAndOtherCommandsUntilDurableCompletion()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host, "post");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                int opens = f.opens.get();
                edt(
                        () -> {
                            assertFalse(
                                    ((JButton) find(host.frame, "workspace-reload")).isEnabled());
                            assertFalse(
                                    ((JComboBox<?>) find(host.frame, "workspace-company"))
                                            .isEnabled());
                            assertFalse(
                                    ((JButton) find(host.frame, "voucher-create-load"))
                                            .isEnabled());
                            assertFalse(
                                    ((JButton) find(host.frame, "voucher-register-refresh"))
                                            .isEnabled());
                            host.workspace.reload();
                            return null;
                        });
                Thread.sleep(150);
                assertEquals(opens, f.opens.get());
            } finally {
                f.release.countDown();
            }
            host.posted();
            assertTrue(
                    edt(
                            () ->
                                    ((JButton) find(host.frame, "voucher-register-refresh"))
                                            .isEnabled()));
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
        }
    }

    @Test
    void acceptedReversalFinishesAfterClosureButCannotRepopulateClosedPanel() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host, "post");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            host.workspace.close();
                            assertTrue(host.frame.isDisplayable());
                            return null;
                        });
            } finally {
                f.release.countDown();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try {
                    NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
                    break;
                } catch (AssertionError e) {
                    Thread.sleep(20);
                }
            }
            NormalizedApplicationVoucherReversalTest.verify(f, host.id, 2);
            assertEquals(text("closed"), edt(() -> status(host).getText()));
            assertEquals("", edt(() -> field(host, "source").getText()));
            assertFalse(edt(() -> button(host, "post").isEnabled()));
        }
    }

    private static final class Host implements AutoCloseable {
        final NormalizedApplicationOwner owner;
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;
        final long id;

        Host(Fixture f) throws Exception {
            owner = f.owner();
            id = NormalizedApplicationVoucherReversalTest.source(f, owner);
            frame = edt(JFrame::new);
            workspace =
                    edt(
                            () -> {
                                var panel = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(panel);
                                frame.setSize(1300, 1100);
                                frame.setVisible(true);
                                return panel;
                            });
            await(
                    () ->
                            !workspace.isBusy()
                                    && ((JComboBox<?>) find(frame, "workspace-company"))
                                                    .getItemCount()
                                            == 3);
            apply(1);
            edt(
                    () -> {
                        ((JTabbedPane) find(frame, "workspace-documents")).setSelectedIndex(4);
                        return null;
                    });
        }

        void apply(int index) throws Exception {
            edt(
                    () -> {
                        ((JComboBox<?>) find(frame, "workspace-company")).setSelectedIndex(index);
                        ((JComboBox<?>) find(frame, "workspace-year")).setSelectedIndex(1);
                        ((JButton) find(frame, "workspace-apply")).doClick();
                        return null;
                    });
            int company = index == 1 ? 71 : 72;
            await(
                    () ->
                            !workspace.isBusy()
                                    && workspace
                                            .selection()
                                            .map(s -> s.company().id() == company)
                                            .orElse(false)
                                    && field(this, "source").isEnabled()
                                    && ((JButton) find(frame, "document-refresh")).isEnabled()
                                    && ((JButton) find(frame, "supplier-document-refresh"))
                                            .isEnabled());
        }

        void fill() throws Exception {
            edt(
                    () -> {
                        field(this, "source").setText(Long.toString(id));
                        field(this, "date").setText("2026-06-16");
                        field(this, "description").setText("Explicit reversal <html>");
                        return null;
                    });
        }

        void preview() throws Exception {
            fill();
            click(this, "preview");
            previewed();
        }

        void previewed() throws Exception {
            await(() -> button(this, "post").isEnabled());
        }

        void failed() throws Exception {
            await(
                    () ->
                            field(this, "source").isEnabled()
                                    && status(this).getText().startsWith(text("failed")));
        }

        void posted() throws Exception {
            await(
                    () ->
                            field(this, "source").isEnabled()
                                    && status(this).getText().startsWith(text("posted")));
        }

        public void close() throws Exception {
            edt(
                    () -> {
                        workspace.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.voucherreversal." + key);
    }

    private static JTextField field(Host h, String key) {
        return (JTextField) find(h.frame, "voucher-reversal-" + key);
    }

    private static JButton button(Host h, String key) {
        return (JButton) find(h.frame, "voucher-reversal-" + key);
    }

    private static JTextArea status(Host h) {
        return (JTextArea) find(h.frame, "voucher-reversal-status");
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

    private static void click(Host h, String key) throws Exception {
        edt(
                () -> {
                    assertTrue(button(h, key).isEnabled(), key);
                    button(h, key).doClick();
                    return null;
                });
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for reversal");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
