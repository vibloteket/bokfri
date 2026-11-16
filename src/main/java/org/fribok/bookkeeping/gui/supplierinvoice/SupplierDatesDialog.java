package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Values;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Explicit supplier date drafts, complete-source preview and fresh confirmation; SQL runs off EDT.
 */
public final class SupplierDatesDialog extends JDialog {
    private final SupplierDatesAdapter adapter;
    private final Consumer<SupplierDatesAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JTextField invoice = new JTextField(12);
    private final JTextField due = new JTextField(12);
    private final JTextArea status = area(3);
    private final JTextArea summary = area(5);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierDatesAdapter.Loaded loaded;
    private SupplierDatesAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Requires EDT; initial load and all database work use background workers. */
    public SupplierDatesDialog(
            JFrame owner,
            SupplierDatesAdapter adapter,
            Consumer<SupplierDatesAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-dates");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-dates-context");
        invoice.setName("supplier-dates-invoice");
        due.setName("supplier-dates-due");
        status.setName("supplier-dates-status");
        summary.setName("supplier-dates-summary");
        reload.setName("supplier-dates-reload");
        previewButton.setName("supplier-dates-preview");
        save.setName("supplier-dates-save");
        cancel.setName("supplier-dates-cancel");
        confirm.setName("supplier-dates-confirm");
        JPanel top = new JPanel(new BorderLayout());
        top.add(context, BorderLayout.NORTH);
        top.add(status);
        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        fields.add(new JLabel(text("invoice")));
        fields.add(invoice);
        fields.add(new JLabel(text("due")));
        fields.add(due);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(fields, BorderLayout.NORTH);
        center.add(new JScrollPane(summary));
        JPanel buttons = new JPanel();
        buttons.add(reload);
        buttons.add(previewButton);
        buttons.add(save);
        buttons.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(confirm, BorderLayout.NORTH);
        bottom.add(buttons);
        add(top, BorderLayout.NORTH);
        add(center);
        add(bottom, BorderLayout.SOUTH);
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent event) {
                        changed();
                    }

                    public void removeUpdate(DocumentEvent event) {
                        changed();
                    }

                    public void changedUpdate(DocumentEvent event) {
                        changed();
                    }

                    private void changed() {
                        invalidatePreview();
                        controls();
                    }
                };
        invoice.getDocument().addDocumentListener(edits);
        due.getDocument().addDocumentListener(edits);
        reload.addActionListener(event -> load());
        previewButton.addActionListener(event -> preview());
        save.addActionListener(event -> save());
        confirm.addActionListener(event -> controls());
        cancel.addActionListener(
                event -> {
                    if (!busy) {
                        dispose();
                    }
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        if (!busy) {
                            dispose();
                        }
                    }
                });
        getRootPane()
                .registerKeyboardAction(
                        event -> {
                            if (!busy) {
                                dispose();
                            }
                        },
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    /** Stops new work and callbacks; an already accepted original-context save may finish. */
    public void invalidateSelection() {
        requireEdt();
        invalid = true;
        revision++;
        controls();
        if (!busy) {
            dispose();
        }
    }

    private void invalidatePreview() {
        preview = null;
        revision++;
        confirm.setSelected(false);
        summary.setText("");
    }

    private void load() {
        if (busy || invalid || !adapter.isActive() || adapter.isBlocked()) {
            return;
        }
        loaded = null;
        invalidatePreview();
        invoice.setText("");
        due.setText("");
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierDatesAdapter.Loaded, Void>() {
            protected SupplierDatesAdapter.Loaded doInBackground() throws Exception {
                return adapter.load();
            }

            protected void done() {
                busy = false;
                if (invalid || !adapter.isActive()) {
                    dispose();
                    return;
                }
                try {
                    loaded = get();
                    var source = loaded.source();
                    invoice.setText(date(source.values().invoiceDate(), ""));
                    due.setText(date(source.values().dueDate(), ""));
                    context.setText(
                            MessageFormat.format(
                                    text("context"),
                                    source.kind().name(),
                                    source.id(),
                                    source.number(),
                                    source.supplier(),
                                    source.paymentTerm()));
                    status.setText(text("ready"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    loaded = null;
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    loaded = null;
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void preview() {
        if (busy || invalid || loaded == null || !adapter.isActive() || adapter.isBlocked()) {
            return;
        }
        Values proposed;
        try {
            proposed = new Values(parse(invoice.getText()), parse(due.getText()));
        } catch (java.time.DateTimeException error) {
            invalidatePreview();
            status.setText(text("invalid"));
            controls();
            return;
        }
        invalidatePreview();
        long request = revision;
        var accepted = loaded;
        busy = true;
        status.setText(text("previewing"));
        controls();
        new SwingWorker<SupplierDatesAdapter.Preview, Void>() {
            protected SupplierDatesAdapter.Preview doInBackground() throws Exception {
                return adapter.preview(accepted, proposed);
            }

            protected void done() {
                busy = false;
                if (invalid || !adapter.isActive()) {
                    dispose();
                    return;
                }
                if (request != revision) {
                    status.setText(text("ready"));
                    controls();
                    return;
                }
                try {
                    preview = get();
                    var plan = preview.plan();
                    summary.setText(
                            MessageFormat.format(
                                    text("summary"),
                                    date(plan.before().invoiceDate(), "NULL"),
                                    date(plan.before().dueDate(), "NULL"),
                                    date(plan.after().invoiceDate(), "NULL"),
                                    date(plan.after().dueDate(), "NULL"),
                                    plan.source().paymentTerm(),
                                    text(plan.changed() ? "changed" : "noop")));
                    summary.setCaretPosition(0);
                    status.setText(text("review"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    loaded = null;
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    loaded = null;
                    invalidatePreview();
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void save() {
        if (busy
                || invalid
                || !adapter.isActive()
                || adapter.isBlocked()
                || preview == null
                || !confirm.isSelected()) {
            return;
        }
        var accepted = preview;
        busy = true;
        status.setText(text("saving"));
        controls();
        new SwingWorker<SupplierDatesAdapter.Saved, Void>() {
            protected SupplierDatesAdapter.Saved doInBackground() throws Exception {
                return adapter.confirm(accepted);
            }

            protected void done() {
                busy = false;
                try {
                    var saved = get();
                    if (!invalid && adapter.isActive()) {
                        try {
                            committed.accept(saved);
                        } catch (RuntimeException error) {
                            org.slf4j.LoggerFactory.getLogger(SupplierDatesDialog.class)
                                    .error(
                                            "Supplier dates committed but refresh failed; do not"
                                                    + " retry",
                                            error);
                        }
                    }
                    dispose();
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    loaded = null;
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    loaded = null;
                    status.setText(
                            adapter.isBlocked()
                                    ? text("uncertain")
                                    : text("failed") + " " + error.getCause().getMessage());
                }
                invalidatePreview();
                if (invalid || !adapter.isActive()) {
                    dispose();
                } else {
                    controls();
                }
            }
        }.execute();
    }

    private void controls() {
        boolean active = !busy && !invalid && adapter.isActive() && !adapter.isBlocked();
        invoice.setEnabled(active && loaded != null);
        due.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private static LocalDate parse(String value) {
        return value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String date(LocalDate value, String missing) {
        return value == null ? missing : value.toString();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.dates." + key);
    }

    private static JTextArea area(int rows) {
        JTextArea area = new JTextArea(rows, 72);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier date dialog requires the EDT");
        }
    }
}
