package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Bounded supplier display-name/reference editor with explicit null controls; never edits financial
 * fields.
 */
public final class SupplierDocumentMetadataDialog extends JDialog {
    private final SupplierDocumentMetadataAdapter adapter;
    private final Consumer<SupplierDocumentMetadataAdapter.Saved> committed;
    private final JLabel target = new JLabel();
    private final JLabel status = new JLabel();
    private final JTextField name = new JTextField(32);
    private final JTextField reference = new JTextField(32);
    private final JCheckBox nullName = new JCheckBox(text("nullname"));
    private final JCheckBox nullReference = new JCheckBox(text("nullreference"));
    private final JButton save = new JButton(text("save"));
    private final JButton reload = new JButton(text("reload"));
    private final JButton cancel = new JButton(text("cancel"));
    private SupplierDocumentMetadataAdapter.Loaded loaded;
    private boolean busy;
    private boolean invalid;
    private boolean filling;
    private boolean nameEdited;
    private boolean referenceEdited;

    /** Constructs on the EDT and loads raw values off the EDT; no data is saved automatically. */
    public SupplierDocumentMetadataDialog(
            JFrame owner,
            SupplierDocumentMetadataAdapter adapter,
            Consumer<SupplierDocumentMetadataAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-document-metadata");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        target.setName("supplier-metadata-target");
        status.setName("supplier-metadata-status");
        name.setName("supplier-metadata-name");
        reference.setName("supplier-metadata-reference");
        nullName.setName("supplier-metadata-name-null");
        nullReference.setName("supplier-metadata-reference-null");
        save.setName("supplier-metadata-save");
        reload.setName("supplier-metadata-reload");
        cancel.setName("supplier-metadata-cancel");
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(new JLabel(text("name")));
        fields.add(name);
        fields.add(new JLabel(""));
        fields.add(nullName);
        fields.add(new JLabel(text("reference")));
        fields.add(reference);
        fields.add(new JLabel(""));
        fields.add(nullReference);
        JPanel buttons = new JPanel();
        buttons.add(reload);
        buttons.add(save);
        buttons.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status);
        bottom.add(buttons, BorderLayout.SOUTH);
        add(target, BorderLayout.NORTH);
        add(fields);
        add(bottom, BorderLayout.SOUTH);
        name.getDocument().addDocumentListener(changed(true));
        reference.getDocument().addDocumentListener(changed(false));
        nullName.addActionListener(
                event -> {
                    nameEdited = true;
                    controls();
                });
        nullReference.addActionListener(
                event -> {
                    referenceEdited = true;
                    controls();
                });
        save.addActionListener(event -> save());
        reload.addActionListener(event -> load());
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
                        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    private DocumentListener changed(boolean supplierName) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                edit();
            }

            public void removeUpdate(DocumentEvent event) {
                edit();
            }

            public void changedUpdate(DocumentEvent event) {
                edit();
            }

            private void edit() {
                if (!filling) {
                    if (supplierName) {
                        nameEdited = true;
                    } else {
                        referenceEdited = true;
                    }
                }
            }
        };
    }

    /**
     * Invalidates future loads/saves and refresh callbacks; accepted original-context writes may
     * finish.
     */
    public void invalidateSelection() {
        requireEdt();
        invalid = true;
        controls();
        if (!busy) {
            dispose();
        }
    }

    private void load() {
        if (busy || invalid || !adapter.isActive() || adapter.isBlocked()) {
            return;
        }
        loaded = null;
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierDocumentMetadataAdapter.Loaded, Void>() {
            protected SupplierDocumentMetadataAdapter.Loaded doInBackground() throws Exception {
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
                    filling = true;
                    try {
                        name.setText(source.supplierName() == null ? "" : source.supplierName());
                        reference.setText(source.reference() == null ? "" : source.reference());
                        nullName.setSelected(source.supplierName() == null);
                        nullReference.setSelected(source.reference() == null);
                    } finally {
                        filling = false;
                    }
                    nameEdited = false;
                    referenceEdited = false;
                    target.setText(
                            MessageFormat.format(
                                    text("target"),
                                    text(source.kind() == Kind.INVOICE ? "invoice" : "credit"),
                                    String.valueOf(source.number()),
                                    Long.toString(source.id()),
                                    Integer.toString(source.companyId()),
                                    Integer.toString(adapter.yearId())));
                    status.setText(text("ready"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
                pack();
            }
        }.execute();
    }

    private void save() {
        if (busy || invalid || loaded == null || !adapter.isActive() || adapter.isBlocked()) {
            return;
        }
        var accepted = loaded;
        // JTextField may visually normalize historical newlines; untouched values must remain raw
        // SQL strings.
        String acceptedName =
                nameEdited
                        ? (nullName.isSelected() ? null : name.getText())
                        : loaded.source().supplierName();
        String acceptedReference =
                referenceEdited
                        ? (nullReference.isSelected() ? null : reference.getText())
                        : loaded.source().reference();
        busy = true;
        status.setText(text("saving"));
        controls();
        new SwingWorker<SupplierDocumentMetadataAdapter.Saved, Void>() {
            protected SupplierDocumentMetadataAdapter.Saved doInBackground() throws Exception {
                return adapter.save(accepted, acceptedName, acceptedReference);
            }

            protected void done() {
                busy = false;
                try {
                    var result = get();
                    if (!invalid && adapter.isActive()) {
                        try {
                            committed.accept(result);
                        } catch (RuntimeException failure) {
                            org.slf4j.LoggerFactory.getLogger(SupplierDocumentMetadataDialog.class)
                                    .error(
                                            "Supplier metadata committed but refresh notification"
                                                + " failed; do not retry",
                                            failure);
                        }
                    }
                    dispose();
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(
                            adapter.isBlocked()
                                    ? text("uncertain")
                                    : text("failed") + " " + error.getCause().getMessage());
                }
                loaded = null;
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
        boolean editable = active && loaded != null;
        name.setEnabled(editable && !nullName.isSelected());
        reference.setEnabled(editable && !nullReference.isSelected());
        nullName.setEnabled(editable);
        nullReference.setEnabled(editable);
        save.setEnabled(editable);
        reload.setEnabled(active);
        cancel.setEnabled(!busy);
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.metadata." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier metadata dialog requires the EDT");
        }
    }
}
