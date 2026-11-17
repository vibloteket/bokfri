package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.RowValue;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Row;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Existing-row description-only editing with explicit before/after preview and fresh confirmation.
 */
public final class SupplierRowDescriptionDialog extends JDialog {
    private final SupplierRowDescriptionAdapter adapter;
    private final Consumer<SupplierRowDescriptionAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JTextArea status = area(2);
    private final JTextArea summary = area(5);
    private final Descriptions descriptions = new Descriptions();
    private final JTable table = new JTable(descriptions);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierRowDescriptionAdapter.Loaded loaded;
    private SupplierRowDescriptionAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Constructs on the EDT; initial loading and all database work run in background workers. */
    public SupplierRowDescriptionDialog(
            JFrame owner,
            SupplierRowDescriptionAdapter adapter,
            Consumer<SupplierRowDescriptionAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-row-description");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-description-context");
        status.setName("supplier-description-status");
        summary.setName("supplier-description-summary");
        table.setName("supplier-description-rows");
        table.setAutoCreateRowSorter(true);
        table.setPreferredScrollableViewportSize(new Dimension(1000, 220));
        table.getColumnModel().getColumn(2).setCellEditor(new DescriptionEditor());
        table.getColumnModel()
                .getColumn(2)
                .setCellRenderer(
                        new DefaultTableCellRenderer() {
                            @Override
                            protected void setValue(Object value) {
                                setText(describe((String) value));
                            }
                        });
        reload.setName("supplier-description-reload");
        previewButton.setName("supplier-description-preview");
        save.setName("supplier-description-save");
        cancel.setName("supplier-description-cancel");
        confirm.setName("supplier-description-confirm");
        JPanel top = new JPanel(new BorderLayout());
        top.add(context, BorderLayout.NORTH);
        top.add(status);
        JPanel buttons = new JPanel();
        buttons.add(reload);
        buttons.add(previewButton);
        buttons.add(save);
        buttons.add(cancel);
        JPanel controls = new JPanel(new BorderLayout());
        controls.add(confirm, BorderLayout.NORTH);
        controls.add(buttons);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JScrollPane(summary));
        bottom.add(controls, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table));
        add(bottom, BorderLayout.SOUTH);
        descriptions.addTableModelListener(
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
                        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Invalidates future work and refresh notifications; accepted original-context saves may
     * finish.
     */
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
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        loaded = null;
        invalidatePreview();
        descriptions.set(List.of());
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierRowDescriptionAdapter.Loaded, Void>() {
            protected SupplierRowDescriptionAdapter.Loaded doInBackground() throws Exception {
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
                    descriptions.set(source.rows());
                    context.setText(
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
                    loaded = null;
                    invalidatePreview();
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
                pack();
            }
        }.execute();
    }

    private boolean finishCell() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            status.setText(text("invalid"));
            return false;
        }
        return true;
    }

    private void preview() {
        if (busy
                || invalid
                || loaded == null
                || !adapter.isActive()
                || adapter.isBlocked()
                || !finishCell()) {
            return;
        }
        List<RowValue> proposed;
        try {
            proposed = descriptions.proposed();
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
        new SwingWorker<SupplierRowDescriptionAdapter.Preview, Void>() {
            protected SupplierRowDescriptionAdapter.Preview doInBackground() throws Exception {
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
                                    plan.changes().stream()
                                            .map(change -> change.after().key())
                                            .toList()
                                            .toString()));
                    for (var change : plan.changes()) {
                        summary.append(
                                "\n"
                                        + MessageFormat.format(
                                                text("rowchange"),
                                                change.after().key(),
                                                describe(change.before().description()),
                                                describe(change.after().description())));
                    }
                    summary.setCaretPosition(0);
                    status.setText(text("review"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
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
                || !finishCell()
                || preview == null
                || !confirm.isSelected()) {
            return;
        }
        var accepted = preview;
        busy = true;
        status.setText(text("saving"));
        controls();
        new SwingWorker<SupplierRowDescriptionAdapter.Saved, Void>() {
            protected SupplierRowDescriptionAdapter.Saved doInBackground() throws Exception {
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
                            org.slf4j.LoggerFactory.getLogger(SupplierRowDescriptionDialog.class)
                                    .error(
                                            "Supplier descriptions committed but refresh failed; do"
                                                    + " not retry",
                                            error);
                        }
                    }
                    dispose();
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
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
        table.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private static JTextArea area(int rows) {
        var area = new JTextArea(rows, 100);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.rowdescription." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier description dialog requires the EDT");
        }
    }

    /** Displays exact raw text without allowing Swing HTML interpretation or hiding controls. */
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

    /**
     * An untouched Swing-normalized editor must return the original raw SQL string, including NULL.
     */
    private static final class DescriptionEditor extends DefaultCellEditor {
        private final JTextField field;
        private String original;
        private boolean loading;
        private boolean dirty;

        private DescriptionEditor() {
            super(new JTextField());
            field = (JTextField) getComponent();
            field.getDocument()
                    .addDocumentListener(
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
                                    if (!loading) {
                                        dirty = true;
                                    }
                                }
                            });
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            loading = true;
            try {
                original = (String) value;
                var editor = super.getTableCellEditorComponent(table, value, selected, row, column);
                dirty = false;
                return editor;
            } finally {
                loading = false;
            }
        }

        @Override
        public Object getCellEditorValue() {
            return dirty ? field.getText() : original;
        }
    }

    private static final class Descriptions extends AbstractTableModel {
        private final String[] columns = {
            "key",
            "product",
            "description",
            "unit",
            "account",
            "project",
            "resultunit",
            "quantity",
            "price",
            "freight"
        };
        private List<Row> rows = List.of();
        private final List<String> drafts = new ArrayList<>();

        void set(List<Row> values) {
            rows = List.copyOf(values);
            drafts.clear();
            for (var row : rows) {
                drafts.add(row.description());
            }
            fireTableDataChanged();
        }

        List<RowValue> proposed() {
            List<RowValue> result = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                result.add(new RowValue(rows.get(i).key(), drafts.get(i)));
            }
            return result;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return text(columns[column]);
        }

        public Class<?> getColumnClass(int column) {
            return column == 0 || column == 4 || column == 7
                    ? Integer.class
                    : column >= 8 ? BigDecimal.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return column == 2;
        }

        public Object getValueAt(int row, int column) {
            var value = rows.get(row);
            return switch (column) {
                case 0 -> value.key();
                case 1 -> value.product();
                case 2 -> drafts.get(row);
                case 3 -> value.unit();
                case 4 -> value.account();
                case 5 -> value.project();
                case 6 -> value.resultUnit();
                case 7 -> value.quantity();
                case 8 -> value.unitPrice();
                default -> value.unitFreight();
            };
        }

        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) {
                return;
            }
            String next = value == null ? null : value.toString();
            if (!Objects.equals(drafts.get(row), next)) {
                drafts.set(row, next);
                fireTableCellUpdated(row, column);
            }
        }
    }
}
