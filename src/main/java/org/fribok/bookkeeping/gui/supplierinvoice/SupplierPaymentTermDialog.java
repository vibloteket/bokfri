package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierPaymentTermService.Values;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Explicit supplier payment-term drafts, complete-source preview and fresh confirmation; SQL runs
 * off EDT.
 */
public final class SupplierPaymentTermDialog extends JDialog {
    private final SupplierPaymentTermAdapter adapter;
    private final Consumer<SupplierPaymentTermAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JComboBox<Choice> currency = new JComboBox<>();

    private record Choice(String code) {
        public String toString() {
            return code == null ? text("null") : text("code") + describe(code);
        }
    }

    private final JTextArea status = area(3);
    private final JTextArea summary = area(5);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierPaymentTermAdapter.Loaded loaded;
    private SupplierPaymentTermAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Requires EDT; initial load and all database work use background workers. */
    public SupplierPaymentTermDialog(
            JFrame owner,
            SupplierPaymentTermAdapter adapter,
            Consumer<SupplierPaymentTermAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-paymentterm");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-paymentterm-context");
        currency.setName("supplier-paymentterm-code");
        currency.setEditable(false);
        status.setName("supplier-paymentterm-status");
        summary.setName("supplier-paymentterm-summary");
        reload.setName("supplier-paymentterm-reload");
        previewButton.setName("supplier-paymentterm-preview");
        save.setName("supplier-paymentterm-save");
        cancel.setName("supplier-paymentterm-cancel");
        confirm.setName("supplier-paymentterm-confirm");
        JPanel top = new JPanel(new BorderLayout());
        top.add(context, BorderLayout.NORTH);
        top.add(status);
        JPanel fields = new JPanel(new GridLayout(1, 2, 8, 8));
        fields.add(new JLabel(text("currency")));
        fields.add(currency);
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
        currency.addActionListener(
                event -> {
                    invalidatePreview();
                    controls();
                });
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
        currency.removeAllItems();
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierPaymentTermAdapter.Loaded, Void>() {
            protected SupplierPaymentTermAdapter.Loaded doInBackground() throws Exception {
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
                    currency.addItem(new Choice(null));
                    for (var term : source.terms()) {
                        currency.addItem(new Choice(term.name()));
                    }
                    String stored = source.values().paymentTerm();
                    if (stored != null
                            && source.terms().stream()
                                    .noneMatch(term -> stored.equals(term.name()))) {
                        currency.addItem(new Choice(stored));
                    }
                    currency.setSelectedItem(new Choice(stored));
                    context.setText(
                            MessageFormat.format(
                                    text("context"),
                                    source.kind().name(),
                                    Long.toString(source.id()),
                                    String.valueOf(source.number()),
                                    describe(source.supplier())));
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
            Choice selected = (Choice) currency.getSelectedItem();
            proposed = new Values(selected == null ? null : selected.code());
        } catch (IllegalArgumentException error) {
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
        new SwingWorker<SupplierPaymentTermAdapter.Preview, Void>() {
            protected SupplierPaymentTermAdapter.Preview doInBackground() throws Exception {
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
                                    describe(plan.before().paymentTerm()),
                                    describe(plan.after().paymentTerm()),
                                    String.valueOf(plan.source().dates().invoiceDate()),
                                    String.valueOf(plan.source().dates().dueDate()),
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
        new SwingWorker<SupplierPaymentTermAdapter.Saved, Void>() {
            protected SupplierPaymentTermAdapter.Saved doInBackground() throws Exception {
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
                            org.slf4j.LoggerFactory.getLogger(SupplierPaymentTermDialog.class)
                                    .error(
                                            "Supplier payment-term committed but refresh"
                                                    + " failed; do not retry",
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
        currency.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private static String describe(String value) {
        if (value == null) {
            return "NULL";
        }
        StringBuilder result = new StringBuilder("\"");
        value.codePoints()
                .forEach(
                        code -> {
                            if (code == '\\') {
                                result.append("\\\\");
                            } else if (code == '"') {
                                result.append("\\\"");
                            } else if (Character.isISOControl(code)) {
                                result.append(String.format("\\u%04x", code));
                            } else {
                                result.appendCodePoint(code);
                            }
                        });
        return result.append('"').toString();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.paymentterm." + key);
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
            throw new IllegalStateException("Supplier payment-term dialog requires the EDT");
        }
    }
}
