package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService.Values;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.text.MessageFormat;
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
 * Explicit supplier VAT/rounding drafts, complete-source preview and fresh confirmation; SQL runs
 * off EDT.
 */
public final class SupplierTaxRoundingDialog extends JDialog {
    private final SupplierTaxRoundingAdapter adapter;
    private final Consumer<SupplierTaxRoundingAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JTextField tax = new JTextField(12);
    private final JTextField rounding = new JTextField(12);
    private final JTextArea status = area(3);
    private final JTextArea summary = area(5);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierTaxRoundingAdapter.Loaded loaded;
    private SupplierTaxRoundingAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Requires EDT; initial load and all database work use background workers. */
    public SupplierTaxRoundingDialog(
            JFrame owner,
            SupplierTaxRoundingAdapter adapter,
            Consumer<SupplierTaxRoundingAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-tax-rounding");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-tax-rounding-context");
        tax.setName("supplier-tax-rounding-tax");
        rounding.setName("supplier-tax-rounding-rounding");
        status.setName("supplier-tax-rounding-status");
        summary.setName("supplier-tax-rounding-summary");
        reload.setName("supplier-tax-rounding-reload");
        previewButton.setName("supplier-tax-rounding-preview");
        save.setName("supplier-tax-rounding-save");
        cancel.setName("supplier-tax-rounding-cancel");
        confirm.setName("supplier-tax-rounding-confirm");
        JPanel top = new JPanel(new BorderLayout());
        top.add(context, BorderLayout.NORTH);
        top.add(status);
        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        fields.add(new JLabel(text("tax")));
        fields.add(tax);
        fields.add(new JLabel(text("rounding")));
        fields.add(rounding);
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
        tax.getDocument().addDocumentListener(edits);
        rounding.getDocument().addDocumentListener(edits);
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
        tax.setText("");
        rounding.setText("");
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierTaxRoundingAdapter.Loaded, Void>() {
            protected SupplierTaxRoundingAdapter.Loaded doInBackground() throws Exception {
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
                    tax.setText(decimal(source.values().tax(), ""));
                    rounding.setText(decimal(source.values().rounding(), ""));
                    context.setText(
                            MessageFormat.format(
                                    text("context"),
                                    source.kind().name(),
                                    source.id(),
                                    source.number(),
                                    source.supplier(),
                                    source.currency(),
                                    decimal(source.rate(), "NULL")));
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
            proposed = new Values(parse(tax.getText()), parse(rounding.getText()));
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
        new SwingWorker<SupplierTaxRoundingAdapter.Preview, Void>() {
            protected SupplierTaxRoundingAdapter.Preview doInBackground() throws Exception {
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
                    var before = plan.beforeTotals();
                    var after = plan.afterTotals();
                    summary.setText(
                            MessageFormat.format(
                                    text("summary"),
                                    decimal(plan.before().tax(), "NULL"),
                                    decimal(plan.before().rounding(), "NULL"),
                                    decimal(plan.after().tax(), "NULL"),
                                    decimal(plan.after().rounding(), "NULL"),
                                    decimal(before.net(), "NULL"),
                                    decimal(before.payable(), "NULL"),
                                    decimal(before.accountingEquivalent(), "NULL"),
                                    decimal(after.payable(), "NULL"),
                                    decimal(after.accountingEquivalent(), "NULL"),
                                    plan.source().currency(),
                                    decimal(plan.source().rate(), "NULL"),
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
        new SwingWorker<SupplierTaxRoundingAdapter.Saved, Void>() {
            protected SupplierTaxRoundingAdapter.Saved doInBackground() throws Exception {
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
                            org.slf4j.LoggerFactory.getLogger(SupplierTaxRoundingDialog.class)
                                    .error(
                                            "Supplier VAT/rounding committed but refresh failed; do"
                                                + " not retry",
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
        tax.setEnabled(active && loaded != null);
        rounding.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private static BigDecimal parse(String value) {
        return value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static String decimal(BigDecimal value, String missing) {
        return value == null ? missing : value.toPlainString();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.taxrounding." + key);
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
            throw new IllegalStateException("Supplier VAT/rounding dialog requires the EDT");
        }
    }
}
