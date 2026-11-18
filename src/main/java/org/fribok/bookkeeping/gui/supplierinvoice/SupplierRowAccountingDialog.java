package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAccountingService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAccountingService.Values;
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

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;

/**
 * Existing-row account/project/result-unit editing with explicit before/after preview and fresh
 * confirmation.
 */
public final class SupplierRowAccountingDialog extends JDialog {
    private final SupplierRowAccountingAdapter adapter;
    private final Consumer<SupplierRowAccountingAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JTextArea status = area(2);
    private final JTextArea summary = area(5);
    private final Allocations allocations = new Allocations();
    private final JTable table = new JTable(allocations);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierRowAccountingAdapter.Loaded loaded;
    private SupplierRowAccountingAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Constructs on the EDT; initial loading and all database work run in background workers. */
    public SupplierRowAccountingDialog(
            JFrame owner,
            SupplierRowAccountingAdapter adapter,
            Consumer<SupplierRowAccountingAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-row-accounting");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-allocation-context");
        status.setName("supplier-allocation-status");
        summary.setName("supplier-allocation-summary");
        table.setName("supplier-allocation-rows");
        table.setAutoCreateRowSorter(true);
        table.setPreferredScrollableViewportSize(new Dimension(1000, 220));
        for (int column = 4; column <= 6; column++) {
            table.getColumnModel().getColumn(column).setCellEditor(new AllocationEditor(column));
            table.getColumnModel()
                    .getColumn(column)
                    .setCellRenderer(
                            new DefaultTableCellRenderer() {
                                @Override
                                protected void setValue(Object value) {
                                    setText(
                                            value instanceof Integer
                                                    ? value.toString()
                                                    : describe((String) value));
                                }
                            });
        }
        table.getColumnModel()
                .getColumn(2)
                .setCellRenderer(
                        new DefaultTableCellRenderer() {
                            @Override
                            protected void setValue(Object value) {
                                setText(describe((String) value));
                            }
                        });
        reload.setName("supplier-allocation-reload");
        previewButton.setName("supplier-allocation-preview");
        save.setName("supplier-allocation-save");
        cancel.setName("supplier-allocation-cancel");
        confirm.setName("supplier-allocation-confirm");
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
        allocations.addTableModelListener(
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
        allocations.set(null);
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierRowAccountingAdapter.Loaded, Void>() {
            protected SupplierRowAccountingAdapter.Loaded doInBackground() throws Exception {
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
                    allocations.set(source);
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
        List<Values> proposed;
        try {
            proposed = allocations.proposed();
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
        new SwingWorker<SupplierRowAccountingAdapter.Preview, Void>() {
            protected SupplierRowAccountingAdapter.Preview doInBackground() throws Exception {
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
                                    Integer.toString(plan.source().yearId()),
                                    plan.changes().stream()
                                            .map(change -> change.after().key())
                                            .toList()
                                            .toString()));
                    for (var change : plan.changes()) {
                        var before = change.before();
                        var after = change.after();
                        summary.append(
                                "\n"
                                        + MessageFormat.format(
                                                text("rowchange"),
                                                Integer.toString(after.key()),
                                                before.account() == null
                                                        ? "NULL"
                                                        : before.account().toString(),
                                                describe(before.project()),
                                                describe(before.resultUnit()),
                                                after.account() == null
                                                        ? "NULL"
                                                        : after.account().toString(),
                                                describe(after.project()),
                                                describe(after.resultUnit())));
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
        new SwingWorker<SupplierRowAccountingAdapter.Saved, Void>() {
            protected SupplierRowAccountingAdapter.Saved doInBackground() throws Exception {
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
                            org.slf4j.LoggerFactory.getLogger(SupplierRowAccountingDialog.class)
                                    .error(
                                            "Supplier allocations committed but refresh failed; do"
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
        return SSBundle.getBundle().getString("supplier.rowaccounting." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier allocation dialog requires the EDT");
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

    private record Choice(Object value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class AllocationEditor extends AbstractCellEditor implements TableCellEditor {
        private final int column;
        private final JComboBox<Choice> choices = new JComboBox<>();
        private boolean loading;

        private AllocationEditor(int column) {
            this.column = column;
            choices.setEditable(false);
            choices.addActionListener(
                    event -> {
                        if (!loading) {
                            stopCellEditing();
                        }
                    });
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int ignored) {
            loading = true;
            try {
                choices.removeAllItems();
                choices.addItem(new Choice(null, "NULL"));
                var source = allocations.source;
                if (source != null) {
                    if (column == 4) {
                        for (var account : source.accounts()) {
                            if (account.active() && account.number() > 0) {
                                choices.addItem(
                                        new Choice(
                                                account.number(),
                                                account.number()
                                                        + " "
                                                        + describe(account.description())
                                                        + (account.projectRequired()
                                                                ? text("requiresproject")
                                                                : "")
                                                        + (account.resultUnitRequired()
                                                                ? text("requiresunit")
                                                                : "")));
                            }
                        }
                    } else {
                        for (var dimension :
                                column == 5 ? source.projects() : source.resultUnits()) {
                            if (dimension.selectable()
                                    && dimension.number() != null
                                    && !dimension.number().isBlank()
                                    && !dimension
                                            .number()
                                            .codePoints()
                                            .anyMatch(Character::isISOControl)) {
                                choices.addItem(
                                        new Choice(
                                                dimension.number(),
                                                describe(dimension.number())
                                                        + " "
                                                        + describe(dimension.name())));
                            }
                        }
                    }
                }
                Choice match = null;
                for (int i = 0; i < choices.getItemCount(); i++) {
                    if (Objects.equals(choices.getItemAt(i).value(), value)) {
                        match = choices.getItemAt(i);
                        break;
                    }
                }
                if (match == null) {
                    match =
                            new Choice(
                                    value,
                                    text("historical")
                                            + (value instanceof Integer
                                                    ? value.toString()
                                                    : describe((String) value)));
                    choices.addItem(match);
                }
                choices.setSelectedItem(match);
                return choices;
            } finally {
                loading = false;
            }
        }

        @Override
        public Object getCellEditorValue() {
            var selected = (Choice) choices.getSelectedItem();
            return selected == null ? null : selected.value();
        }
    }

    private static final class Allocations extends AbstractTableModel {
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
        private NormalizedSupplierRowAccountingService.Snapshot source;
        private List<Row> rows = List.of();
        private final List<Values> drafts = new ArrayList<>();

        void set(NormalizedSupplierRowAccountingService.Snapshot source) {
            this.source = source;
            rows = source == null ? List.of() : source.rows();
            drafts.clear();
            for (var row : rows) {
                drafts.add(NormalizedSupplierRowAccountingService.values(row));
            }
            fireTableDataChanged();
        }

        List<Values> proposed() {
            return List.copyOf(drafts);
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
            return column >= 4 && column <= 6;
        }

        public Object getValueAt(int row, int column) {
            var value = rows.get(row);
            var draft = drafts.get(row);
            return switch (column) {
                case 0 -> value.key();
                case 1 -> value.product();
                case 2 -> value.description();
                case 3 -> value.unit();
                case 4 -> draft.account();
                case 5 -> draft.project();
                case 6 -> draft.resultUnit();
                case 7 -> value.quantity();
                case 8 -> value.unitPrice();
                default -> value.unitFreight();
            };
        }

        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) {
                return;
            }
            var before = drafts.get(row);
            var after =
                    new Values(
                            before.key(),
                            column == 4 ? (Integer) value : before.account(),
                            column == 5 ? (String) value : before.project(),
                            column == 6 ? (String) value : before.resultUnit());
            if (!before.equals(after)) {
                drafts.set(row, after);
                fireTableCellUpdated(row, column);
            }
        }
    }
}
