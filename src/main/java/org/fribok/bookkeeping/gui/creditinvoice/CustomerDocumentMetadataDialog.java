package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Metadata-only form: reads/writes off-EDT; no close/retry while a transaction is in flight. */
public final class CustomerDocumentMetadataDialog extends JDialog {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerDocumentMetadataDialog.class);
    private final CustomerDocumentMetadataAdapter adapter;
    private final Consumer<CustomerDocumentMetadataAdapter.Saved> committed;
    private CustomerDocumentMetadataAdapter.Loaded loaded;
    private final JCheckBox printed = new JCheckBox(text("printed")), missingOcr = new JCheckBox(text("missingocr")),
            interest = new JCheckBox(text("interest"));
    private final JTextField ocr = new JTextField(24);
    private final JSpinner reminders = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JButton save = new JButton(text("save")), reload = new JButton(text("reload")), cancel = new JButton(text("cancel"));
    private final JLabel status = new JLabel(text("loading"));
    private boolean busy, invalid;

    public CustomerDocumentMetadataDialog(JFrame owner, CustomerDocumentMetadataAdapter adapter,
                                          Consumer<CustomerDocumentMetadataAdapter.Saved> committed) {
        super(owner, text("title"), true); requireEdt(); this.adapter = adapter; this.committed = committed;
        var target = adapter.target();
        setTitle(text("title") + " / " + java.text.MessageFormat.format(text("target"),
                text(target.kind() == CustomerDocumentMetadataAdapter.Kind.INVOICE ? "invoice" : "credit"),
                Long.toString(target.id()), Integer.toString(target.companyId()), Integer.toString(target.yearId())));
        setName("document-metadata"); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        printed.setName("metadata-printed"); missingOcr.setName("metadata-missing-ocr"); ocr.setName("metadata-ocr");
        interest.setName("metadata-interest"); reminders.setName("metadata-reminders");
        save.setName("metadata-save"); reload.setName("metadata-reload"); cancel.setName("metadata-cancel"); status.setName("metadata-status");
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(printed); fields.add(interest); fields.add(new JLabel(text("ocr"))); fields.add(ocr);
        fields.add(missingOcr); fields.add(new JLabel()); fields.add(new JLabel(text("reminders"))); fields.add(reminders);
        JPanel buttons = new JPanel(); buttons.add(reload); buttons.add(save); buttons.add(cancel);
        add(fields, BorderLayout.CENTER); add(status, BorderLayout.NORTH); add(buttons, BorderLayout.SOUTH);
        missingOcr.addActionListener(event -> enableControls());
        save.addActionListener(event -> save()); reload.addActionListener(event -> load());
        cancel.addActionListener(event -> { if (!busy) dispose(); });
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); } });
        getRootPane().registerKeyboardAction(event -> { if (!busy) dispose(); },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        load(); pack(); setLocationRelativeTo(owner);
    }

    /** Called when the owning view changes selection/closes. Accepted work finishes in its old context. */
    public void invalidateSelection() {
        requireEdt(); invalid = true; enableControls(); if (!busy) dispose();
    }
    private void load() {
        if (busy || invalid || adapter.isBlocked()) return;
        loaded = null; busy = true; status.setText(text("loading")); enableControls();
        new SwingWorker<CustomerDocumentMetadataAdapter.Loaded, Void>() {
            protected CustomerDocumentMetadataAdapter.Loaded doInBackground() throws Exception { return adapter.load(); }
            protected void done() {
                busy = false;
                if (invalid) { dispose(); return; }
                try {
                    loaded = get(); Metadata values = loaded.values();
                    printed.setSelected(values.printed()); interest.setSelected(values.interestInvoiced());
                    missingOcr.setSelected(values.ocrNumber() == null); ocr.setText(values.ocrNumber() == null ? "" : values.ocrNumber());
                    reminders.setValue(values.reminderCount()); status.setText(text("ready"));
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException failure) { status.setText(text("failed") + " " + failure.getCause().getMessage()); }
                enableControls(); pack();
            }
        }.execute();
    }
    private void save() {
        if (busy || invalid || loaded == null || adapter.isBlocked()) return;
        Metadata replacement;
        try {
            reminders.commitEdit(); int count = ((Number) reminders.getValue()).intValue();
            if (count < 0) throw new IllegalArgumentException();
            replacement = new Metadata(printed.isSelected(), missingOcr.isSelected() ? null : ocr.getText(), count, interest.isSelected());
        } catch (java.text.ParseException | IllegalArgumentException invalidInput) { status.setText(text("invalid")); return; }
        busy = true; status.setText(text("saving")); enableControls();
        new SwingWorker<CustomerDocumentMetadataAdapter.Saved, Void>() {
            protected CustomerDocumentMetadataAdapter.Saved doInBackground() throws Exception { return adapter.save(loaded, replacement); }
            protected void done() {
                busy = false;
                try {
                    var saved = get();
                    if (!invalid) {
                        try { committed.accept(saved); }
                        catch (RuntimeException notification) { LOG.error("Metadata committed but refresh notification failed; do not retry save", notification); }
                    }
                    dispose(); return;
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException failure) {
                    status.setText(adapter.isBlocked() ? text("uncertain") : text("failed") + " " + failure.getCause().getMessage());
                }
                if (invalid) dispose(); else { enableControls(); pack(); }
            }
        }.execute();
    }
    private void enableControls() {
        boolean edit = !busy && !invalid && !adapter.isBlocked() && loaded != null;
        printed.setEnabled(edit); interest.setEnabled(edit); missingOcr.setEnabled(edit); ocr.setEnabled(edit && !missingOcr.isSelected());
        reminders.setEnabled(edit); save.setEnabled(edit); reload.setEnabled(!busy && !invalid && !adapter.isBlocked()); cancel.setEnabled(!busy);
    }
    private static String text(String key) { return SSBundle.getBundle().getString("customer.metadata." + key); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Metadata dialog requires the EDT");
    }
}
