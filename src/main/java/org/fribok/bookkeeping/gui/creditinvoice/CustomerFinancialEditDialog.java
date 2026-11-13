package org.fribok.bookkeeping.gui.creditinvoice;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.List;

import javax.swing.DefaultCellEditor;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

/** Bounded draft editor. Blank dates/amounts mean absent values, not invented defaults or zero. */
public final class CustomerFinancialEditDialog extends JDialog {
    private final CustomerFinancialEditAdapter adapter;
    private final Consumer<CustomerFinancialEditAdapter.Saved> committed;
    private CustomerFinancialEditAdapter.Loaded loaded;
    private CustomerFinancialEditAdapter.Preview preview;
    private final JTextField date = new JTextField(10), due = new JTextField(10);
    private final JTextArea status = new JTextArea(4, 70);
    private final JButton reload = new JButton(text("reload")),
            previewButton = new JButton(text("preview")),
            save = new JButton(text("save")),
            cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private final Lines lines = new Lines();
    private final JTable table = new JTable(lines);
    private boolean busy, invalid;
    private long revision;

    /**
     * Creates and starts loading an explicit draft editor on the EDT.
     *
     * @param owner nullable parent frame
     * @param adapter selection-bound workflow adapter
     * @param committed EDT notification only after a confirmed current-context save
     * @throws IllegalStateException if called outside the EDT
     */
    public CustomerFinancialEditDialog(
            JFrame owner,
            CustomerFinancialEditAdapter adapter,
            Consumer<CustomerFinancialEditAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = adapter;
        this.committed = committed;
        var target = adapter.target();
        setTitle(
                java.text.MessageFormat.format(
                        text("target"),
                        text(
                                target.kind() == NormalizedCustomerFinancialEditService.Kind.INVOICE
                                        ? "invoice"
                                        : "credit"),
                        Long.toString(target.id()),
                        Integer.toString(target.companyId()),
                        Integer.toString(target.yearId())));
        setName("customer-financial-edit");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        date.setName("financial-date");
        due.setName("financial-due");
        table.setName("financial-lines");
        status.setName("financial-status");
        reload.setName("financial-reload");
        previewButton.setName("financial-preview");
        save.setName("financial-save");
        cancel.setName("financial-cancel");
        confirm.setName("financial-confirm");
        JPanel header = new JPanel();
        header.add(new JLabel(text("date")));
        header.add(date);
        header.add(new JLabel(text("due")));
        header.add(due);
        JPanel actions = new JPanel();
        actions.add(reload);
        actions.add(previewButton);
        actions.add(confirm);
        actions.add(save);
        actions.add(cancel);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status);
        bottom.add(actions, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(table));
        add(bottom, BorderLayout.SOUTH);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(
                BigDecimal.class,
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    protected void setValue(Object value) {
                        setHorizontalAlignment(SwingConstants.RIGHT);
                        setText(
                                value == null
                                        ? ""
                                        : ((BigDecimal) value)
                                                .stripTrailingZeros()
                                                .toPlainString());
                    }
                });
        table.setDefaultEditor(
                BigDecimal.class,
                new DefaultCellEditor(new JTextField()) {
                    private BigDecimal parsed;

                    public boolean stopCellEditing() {
                        try {
                            String value = ((JTextField) getComponent()).getText().trim();
                            parsed =
                                    value.isEmpty()
                                            ? null
                                            : new BigDecimal(value.replace(',', '.'));
                            new LineAmounts(0, parsed, parsed);
                        } catch (IllegalArgumentException error) {
                            status.setText(text("invalid"));
                            return false;
                        }
                        return super.stopCellEditing();
                    }

                    public Object getCellEditorValue() {
                        return parsed;
                    }
                });
        table.addPropertyChangeListener(
                "tableCellEditor",
                event -> {
                    if (table.isEditing()) changed();
                });
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        changed();
                    }
                };
        date.getDocument().addDocumentListener(edits);
        due.getDocument().addDocumentListener(edits);
        reload.addActionListener(event -> load());
        previewButton.addActionListener(event -> preview());
        save.addActionListener(event -> save());
        confirm.addActionListener(event -> controls());
        cancel.addActionListener(
                event -> {
                    if (!busy) dispose();
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        if (!busy) dispose();
                    }
                });
        getRootPane()
                .registerKeyboardAction(
                        event -> {
                            if (!busy) dispose();
                        },
                        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Invalidates the dialog on the EDT; accepted work may finish without a stale notification.
     *
     * @throws IllegalStateException if called outside the EDT
     */
    public void invalidateSelection() {
        requireEdt();
        invalid = true;
        revision++;
        controls();
        if (!busy) dispose();
    }

    private void changed() {
        preview = null;
        revision++;
        confirm.setSelected(false);
        if (!busy) status.setText(text("ready"));
        controls();
    }

    private boolean finishEdit() {
        return !table.isEditing() || table.getCellEditor().stopCellEditing();
    }

    private void load() {
        if (busy || invalid || adapter.isBlocked()) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        loaded = null;
        changed();
        lines.set(List.of());
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Loaded, Void>() {
            protected CustomerFinancialEditAdapter.Loaded doInBackground() throws Exception {
                return adapter.load();
            }

            protected void done() {
                busy = false;
                if (invalid) {
                    dispose();
                    return;
                }
                try {
                    loaded = get();
                    var data = loaded.data();
                    date.setText(
                            data.values().date() == null ? "" : data.values().date().toString());
                    due.setText(
                            data.values().dueDate() == null
                                    ? ""
                                    : data.values().dueDate().toString());
                    lines.set(data.lines());
                    status.setText(
                            text("ready")
                                    + " "
                                    + String.valueOf(data.number())
                                    + " / "
                                    + (data.customer() == null ? "" : data.customer())
                                    + " / "
                                    + text("total")
                                    + " "
                                    + data.total().toPlainString()
                                    + " "
                                    + (data.currency() == null ? "" : data.currency()));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void preview() {
        if (busy || invalid || loaded == null || adapter.isBlocked() || !finishEdit()) return;
        changed();
        FinancialValues values;
        try {
            values =
                    new FinancialValues(
                            parse(date.getText()), parse(due.getText()), lines.values());
        } catch (RuntimeException error) {
            status.setText(text("invalid"));
            controls();
            return;
        }
        long request = revision;
        var source = loaded;
        busy = true;
        status.setText(text("previewing"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Preview, Void>() {
            protected CustomerFinancialEditAdapter.Preview doInBackground() throws Exception {
                return adapter.preview(source, values);
            }

            protected void done() {
                busy = false;
                if (invalid) {
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
                    status.setText(
                            text("totals")
                                    + " "
                                    + preview.before().toPlainString()
                                    + " -> "
                                    + preview.after().toPlainString()
                                    + " "
                                    + (loaded.data().currency() == null
                                            ? ""
                                            : loaded.data().currency()));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void save() {
        if (busy
                || invalid
                || !finishEdit()
                || preview == null
                || !confirm.isSelected()
                || adapter.isBlocked()) return;
        var accepted = preview;
        busy = true;
        status.setText(text("saving"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Saved, Void>() {
            protected CustomerFinancialEditAdapter.Saved doInBackground() throws Exception {
                return adapter.save(accepted);
            }

            protected void done() {
                busy = false;
                try {
                    var saved = get();
                    if (!invalid) {
                        try {
                            committed.accept(saved);
                        } catch (RuntimeException error) {
                            org.slf4j.LoggerFactory.getLogger(CustomerFinancialEditDialog.class)
                                    .error(
                                            "Financial edit committed but refresh notification"
                                                + " failed; do not resave",
                                            error);
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
                preview = null;
                confirm.setSelected(false);
                if (invalid) dispose();
                else controls();
            }
        }.execute();
    }

    private void controls() {
        boolean active = !busy && !invalid && !adapter.isBlocked();
        date.setEnabled(active && loaded != null);
        due.setEnabled(active && loaded != null);
        table.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private final class Lines extends AbstractTableModel {
        private List<NormalizedCustomerFinancialEditService.Line> original = List.of();
        private java.util.Map<Integer, LineAmounts> edited = new java.util.HashMap<>();

        void set(List<NormalizedCustomerFinancialEditService.Line> values) {
            original = List.copyOf(values);
            edited.clear();
            for (var row : original)
                edited.put(
                        row.rowNumber(),
                        new LineAmounts(row.rowNumber(), row.quantity(), row.unitPrice()));
            fireTableDataChanged();
        }

        List<LineAmounts> values() {
            return original.stream().map(row -> edited.get(row.rowNumber())).toList();
        }

        public int getRowCount() {
            return original.size();
        }

        public int getColumnCount() {
            return 5;
        }

        public String getColumnName(int column) {
            return text(
                    new String[] {"row", "description", "account", "quantity", "price"}[column]);
        }

        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0, 2 -> Integer.class;
                case 3, 4 -> BigDecimal.class;
                default -> String.class;
            };
        }

        public boolean isCellEditable(int row, int column) {
            return column >= 3 && loaded != null && !busy && !invalid && !adapter.isBlocked();
        }

        public Object getValueAt(int row, int column) {
            var base = original.get(row);
            var value = edited.get(base.rowNumber());
            return switch (column) {
                case 0 -> base.rowNumber();
                case 1 -> base.description();
                case 2 -> base.account();
                case 3 -> value.quantity();
                case 4 -> value.unitPrice();
                default -> throw new IndexOutOfBoundsException();
            };
        }

        public void setValueAt(Object value, int row, int column) {
            if (column != 3 && column != 4) throw new IllegalArgumentException("Read-only field");
            BigDecimal amount =
                    value == null
                            ? null
                            : value instanceof BigDecimal d
                                    ? d
                                    : new BigDecimal(value.toString().replace(',', '.'));
            int key = original.get(row).rowNumber();
            var old = edited.get(key);
            edited.put(
                    key,
                    new LineAmounts(
                            key,
                            column == 3 ? amount : old.quantity(),
                            column == 4 ? amount : old.unitPrice()));
            fireTableCellUpdated(row, column);
            changed();
        }
    }

    private static LocalDate parse(String value) {
        return value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("customer.financial." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Financial editor requires the EDT");
    }
}
