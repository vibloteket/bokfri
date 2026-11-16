package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Row;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Values;

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
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * Existing-row quantity/price/freight editing with explicit before/after preview and fresh
 * confirmation.
 */
public final class SupplierRowAmountsDialog extends JDialog {
    private final SupplierRowAmountsAdapter adapter;
    private final Consumer<SupplierRowAmountsAdapter.Saved> committed;
    private final JLabel context = new JLabel();
    private final JTextArea status = area(2);
    private final JTextArea summary = area(5);
    private final Amounts amounts = new Amounts();
    private final JTable table = new JTable(amounts);
    private final JButton reload = new JButton(text("reload"));
    private final JButton previewButton = new JButton(text("preview"));
    private final JButton save = new JButton(text("save"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private SupplierRowAmountsAdapter.Loaded loaded;
    private SupplierRowAmountsAdapter.Preview preview;
    private boolean busy;
    private boolean invalid;
    private long revision;

    /** Constructs on the EDT; initial loading and all database work run in background workers. */
    public SupplierRowAmountsDialog(
            JFrame owner,
            SupplierRowAmountsAdapter adapter,
            Consumer<SupplierRowAmountsAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = Objects.requireNonNull(adapter);
        this.committed = Objects.requireNonNull(committed);
        setName("supplier-row-amounts");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        context.setName("supplier-amount-context");
        status.setName("supplier-amount-status");
        summary.setName("supplier-amount-summary");
        table.setName("supplier-amount-rows");
        table.setAutoCreateRowSorter(true);
        table.setPreferredScrollableViewportSize(new Dimension(1000, 220));
        reload.setName("supplier-amount-reload");
        previewButton.setName("supplier-amount-preview");
        save.setName("supplier-amount-save");
        cancel.setName("supplier-amount-cancel");
        confirm.setName("supplier-amount-confirm");
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
        amounts.addTableModelListener(
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
        amounts.set(List.of());
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierRowAmountsAdapter.Loaded, Void>() {
            protected SupplierRowAmountsAdapter.Loaded doInBackground() throws Exception {
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
                    amounts.set(source.rows());
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
            proposed = amounts.proposed();
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
        new SwingWorker<SupplierRowAmountsAdapter.Preview, Void>() {
            protected SupplierRowAmountsAdapter.Preview doInBackground() throws Exception {
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
                    var before = plan.before();
                    var after = plan.after();
                    summary.setText(
                            MessageFormat.format(
                                    text("summary"),
                                    display(plan.source().currency()),
                                    decimal(plan.source().rate()),
                                    decimal(before.net()),
                                    decimal(before.payable()),
                                    decimal(before.accountingEquivalent()),
                                    decimal(after.net()),
                                    decimal(after.payable()),
                                    decimal(after.accountingEquivalent()),
                                    decimal(before.tax()),
                                    decimal(before.rounding()),
                                    plan.changes().stream()
                                            .map(change -> change.after().key())
                                            .toList()
                                            .toString(),
                                    text(
                                            plan.source().stockInfluencing()
                                                    ? "stockyes"
                                                    : "stockno")));
                    for (var change : plan.changes()) {
                        var beforeRow = change.before();
                        var afterRow = change.after();
                        summary.append(
                                "\n"
                                        + MessageFormat.format(
                                                text("rowchange"),
                                                afterRow.key(),
                                                beforeRow.quantity() == null
                                                        ? "NULL"
                                                        : beforeRow.quantity().toString(),
                                                decimal(beforeRow.unitPrice()),
                                                decimal(beforeRow.unitFreight()),
                                                afterRow.quantity() == null
                                                        ? "NULL"
                                                        : afterRow.quantity().toString(),
                                                decimal(afterRow.unitPrice()),
                                                decimal(afterRow.unitFreight())));
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
        new SwingWorker<SupplierRowAmountsAdapter.Saved, Void>() {
            protected SupplierRowAmountsAdapter.Saved doInBackground() throws Exception {
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
                            org.slf4j.LoggerFactory.getLogger(SupplierRowAmountsDialog.class)
                                    .error(
                                            "Supplier amounts committed but refresh failed; do not"
                                                    + " retry",
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

    private static String decimal(BigDecimal value) {
        return value == null ? text("absent") : value.toPlainString();
    }

    private static String display(String value) {
        return value == null ? text("absent") : value;
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.amounts." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier amount dialog requires the EDT");
        }
    }

    private static final class Amounts extends AbstractTableModel {
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
        private final List<String[]> drafts = new ArrayList<>();

        void set(List<Row> values) {
            rows = List.copyOf(values);
            drafts.clear();
            for (Row row : rows) {
                drafts.add(
                        new String[] {
                            row.quantity() == null ? "" : row.quantity().toString(),
                            row.unitPrice() == null ? "" : row.unitPrice().toPlainString(),
                            row.unitFreight() == null ? "" : row.unitFreight().toPlainString()
                        });
            }
            fireTableDataChanged();
        }

        List<Values> proposed() {
            List<Values> values = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                var draft = drafts.get(i);
                values.add(
                        new Values(
                                rows.get(i).key(),
                                draft[0].isBlank() ? null : Integer.valueOf(draft[0].trim()),
                                parse(draft[1]),
                                parse(draft[2])));
            }
            return values;
        }

        private static BigDecimal parse(String value) {
            return value.isBlank() ? null : new BigDecimal(value.trim());
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
            return column == 0 || column == 4 ? Integer.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return column >= 7;
        }

        public Object getValueAt(int row, int column) {
            var value = rows.get(row);
            return switch (column) {
                case 0 -> value.key();
                case 1 -> value.product();
                case 2 -> value.description();
                case 3 -> value.unit();
                case 4 -> value.account();
                case 5 -> value.project();
                case 6 -> value.resultUnit();
                default -> drafts.get(row)[column - 7];
            };
        }

        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) {
                return;
            }
            String text = value == null ? "" : value.toString();
            if (!Objects.equals(drafts.get(row)[column - 7], text)) {
                drafts.get(row)[column - 7] = text;
                fireTableCellUpdated(row, column);
            }
        }
    }
}
