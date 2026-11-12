package org.fribok.bookkeeping.gui.creditinvoice;

import se.swedsoft.bookkeeping.gui.util.SSBundle;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Explicit preview plus confirmation; only unbooked, unreferenced documents are eligible. */
public final class CustomerDocumentDeletionDialog extends JDialog {
    private final CustomerDocumentDeletionAdapter adapter;
    private final Consumer<CustomerDocumentDeletionAdapter.Saved> committed;
    private CustomerDocumentDeletionAdapter.Preview preview;
    private final JTextArea summary = new JTextArea(7, 64);
    private final JLabel status = new JLabel();
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private final JButton delete = new JButton(text("delete")), reload = new JButton(text("reload")), cancel = new JButton(text("cancel"));
    private boolean busy, invalid;
    public CustomerDocumentDeletionDialog(JFrame owner, CustomerDocumentDeletionAdapter adapter,
                                          Consumer<CustomerDocumentDeletionAdapter.Saved> committed) {
        super(owner, text("title"), true); requireEdt(); this.adapter = adapter; this.committed = committed;
        setName("document-deletion"); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        summary.setName("deletion-summary"); summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        status.setName("deletion-status"); confirm.setName("deletion-confirm");
        delete.setName("deletion-delete"); reload.setName("deletion-reload"); cancel.setName("deletion-cancel");
        JPanel buttons = new JPanel(); buttons.add(reload); buttons.add(delete); buttons.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(confirm, BorderLayout.NORTH); bottom.add(buttons);
        add(status, BorderLayout.NORTH); add(new JScrollPane(summary)); add(bottom, BorderLayout.SOUTH);
        confirm.addActionListener(event -> controls()); reload.addActionListener(event -> load()); delete.addActionListener(event -> delete());
        cancel.addActionListener(event -> { if (!busy) dispose(); });
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); } });
        getRootPane().registerKeyboardAction(event -> { if (!busy) dispose(); },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        load(); pack(); setLocationRelativeTo(owner);
    }
    public void invalidateSelection() { requireEdt(); invalid = true; controls(); if (!busy) dispose(); }
    private void load() {
        if (busy || invalid || adapter.isBlocked()) return;
        preview = null; confirm.setSelected(false); summary.setText(""); busy = true; status.setText(text("loading")); controls();
        new SwingWorker<CustomerDocumentDeletionAdapter.Preview, Void>() {
            protected CustomerDocumentDeletionAdapter.Preview doInBackground() throws Exception { return adapter.preview(); }
            protected void done() {
                busy = false; if (invalid) { dispose(); return; }
                try {
                    preview = get(); var p = preview.plan();
                    summary.setText(MessageFormat.format(text("summary"), text(p.kind() == Kind.INVOICE ? "invoice" : "credit"),
                            String.valueOf(p.number()), p.customer() == null ? "" : p.customer(), String.valueOf(p.date()),
                            Long.toString(p.id()), Integer.toString(p.companyId()), Integer.toString(adapter.yearId()), p.detachedOrderIds().toString()));
                    status.setText(text("ready"));
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException error) { status.setText(text("failed") + " " + error.getCause().getMessage()); }
                controls(); pack();
            }
        }.execute();
    }
    private void delete() {
        if (busy || invalid || preview == null || !confirm.isSelected() || adapter.isBlocked()) return;
        var accepted = preview; busy = true; status.setText(text("deleting")); controls();
        new SwingWorker<CustomerDocumentDeletionAdapter.Saved, Void>() {
            protected CustomerDocumentDeletionAdapter.Saved doInBackground() throws Exception { return adapter.delete(accepted); }
            protected void done() {
                busy = false;
                try {
                    var result = get();
                    if (!invalid) {
                        try { committed.accept(result); }
                        catch (RuntimeException notification) {
                            org.slf4j.LoggerFactory.getLogger(CustomerDocumentDeletionDialog.class)
                                    .error("Deletion committed but refresh notification failed; do not retry", notification);
                        }
                    }
                    dispose(); return;
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException error) {
                    status.setText(adapter.isBlocked() ? text("uncertain") : text("failed") + " " + error.getCause().getMessage());
                }
                preview = null; confirm.setSelected(false);
                if (invalid) dispose(); else { controls(); pack(); }
            }
        }.execute();
    }
    private void controls() {
        boolean active = !busy && !invalid && !adapter.isBlocked();
        confirm.setEnabled(active && preview != null); delete.setEnabled(active && preview != null && confirm.isSelected());
        reload.setEnabled(active); cancel.setEnabled(!busy);
    }
    private static String text(String key) { return SSBundle.getBundle().getString("customer.deletion." + key); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Deletion dialog requires the EDT");
    }
}
