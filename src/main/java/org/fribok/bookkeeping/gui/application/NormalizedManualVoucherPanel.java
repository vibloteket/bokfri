package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Account;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Preview;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/** Explicit plain-voucher creation for the active document lease; EDT UI, background JDBC. */
public final class NormalizedManualVoucherPanel extends JPanel implements AutoCloseable {
    private record Choice(Account value) {
        public String toString() {
            return value == null
                    ? text("chooseaccount")
                    : value.number() + " : " + value.description();
        }
    }

    private final JTextField date = new JTextField();
    private final JTextField description = new JTextField();
    private final JComboBox<Choice> accounts = new JComboBox<>();
    private final JButton load = new JButton(text("load"));
    private final JButton add = new JButton(text("add"));
    private final JButton remove = new JButton(text("remove"));
    private final JButton preview = new JButton(text("preview"));
    private final JButton post = new JButton(text("post"));
    private final JTextArea status = new JTextArea(5, 70);
    private final DefaultTableModel model =
            new DefaultTableModel(
                    new String[] {text("account"), text("debit"), text("credit")}, 0) {
                public boolean isCellEditable(int row, int column) {
                    return available();
                }
            };
    private final JTable rows = new JTable(model);
    private Selection selection;
    private Preview token;
    private boolean busy;
    private boolean hostAvailable = true;
    private boolean closed;
    private long revision;

    /** Constructs without storage access or default date/accounts; requires EDT. */
    public NormalizedManualVoucherPanel() {
        super(new BorderLayout(8, 8));
        requireEdt();
        setName("workspace-manual-voucher");
        date.setName("voucher-create-date");
        description.setName("voucher-create-description");
        rows.setName("voucher-create-rows");
        accounts.setName("voucher-create-account");
        load.setName("voucher-create-load");
        add.setName("voucher-create-add");
        remove.setName("voucher-create-remove");
        preview.setName("voucher-create-preview");
        post.setName("voucher-create-post");
        status.setName("voucher-create-status");
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        fields.add(new JLabel(text("date")));
        fields.add(new JLabel(text("description")));
        fields.add(date);
        fields.add(description);
        JPanel actions = new JPanel();
        actions.add(load);
        actions.add(add);
        actions.add(remove);
        actions.add(preview);
        actions.add(post);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(status);
        footer.add(actions, BorderLayout.SOUTH);
        add(fields, BorderLayout.NORTH);
        add(new JScrollPane(rows));
        add(footer, BorderLayout.SOUTH);
        accounts.addItem(new Choice(null));
        rows.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(accounts));
        model.addTableModelListener(event -> invalidatePreview());
        rows.addPropertyChangeListener(
                "tableCellEditor",
                event -> {
                    if (rows.isEditing()) invalidatePreview();
                });
        rows.getSelectionModel().addListSelectionListener(event -> controls());
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent event) {
                        invalidatePreview();
                    }

                    public void removeUpdate(DocumentEvent event) {
                        invalidatePreview();
                    }

                    public void changedUpdate(DocumentEvent event) {
                        invalidatePreview();
                    }
                };
        date.getDocument().addDocumentListener(edits);
        description.getDocument().addDocumentListener(edits);
        load.addActionListener(event -> load());
        add.addActionListener(
                event -> {
                    if (available() && model.getRowCount() < 200) {
                        model.addRow(new Object[] {new Choice(null), "", ""});
                    }
                });
        remove.addActionListener(
                event -> {
                    if (available() && rows.getSelectedRow() >= 0) {
                        if (rows.isEditing()) rows.getCellEditor().cancelCellEditing();
                        model.removeRow(rows.getSelectedRow());
                    }
                });
        preview.addActionListener(event -> preview());
        post.addActionListener(event -> post());
        reset();
        status.setText(text("unselected"));
        controls();
    }

    /** Fences old callbacks and drafts while retaining no old context; requires EDT. */
    public void replaceSelection(Selection value) {
        requireEdt();
        if (closed) return;
        revision++;
        selection = value;
        setBusy(false);
        token = null;
        accounts.removeAllItems();
        accounts.addItem(new Choice(null));
        reset();
        status.setText(text("instructions"));
        controls();
    }

    private void reset() {
        if (rows.isEditing()) rows.getCellEditor().cancelCellEditing();
        date.setText("");
        description.setText("");
        model.setRowCount(0);
        for (int index = 0; index < 2; index++) {
            model.addRow(new Object[] {new Choice(null), "", ""});
        }
    }

    /** State-only busy interlock; requires EDT. */
    public boolean isBusy() {
        requireEdt();
        return busy;
    }

    /** Disables input during host/company/year work without cancelling accepted transactions. */
    public void setHostAvailable(boolean value) {
        requireEdt();
        hostAvailable = value;
        controls();
    }

    private void setBusy(boolean value) {
        boolean old = busy;
        busy = value;
        firePropertyChange("busy", old, value);
    }

    private boolean available() {
        return !closed
                && !busy
                && hostAvailable
                && selection != null
                && selection.isActive()
                && !selection.isBlocked();
    }

    private void load() {
        if (!available()) return;
        if (rows.isEditing() && !rows.getCellEditor().stopCellEditing()) return;
        var commands = selection.vouchers();
        run(
                commands::accounts,
                values -> {
                    accounts.removeAllItems();
                    accounts.addItem(new Choice(null));
                    for (var value : values) accounts.addItem(new Choice(value));
                    // Existing row values remain explicit and are revalidated at preview/post.
                    status.setText(text(values.isEmpty() ? "noaccounts" : "instructions"));
                });
    }

    private void preview() {
        if (!available()) return;
        if (rows.isEditing() && !rows.getCellEditor().stopCellEditing()) return;
        try {
            var first = LocalDate.parse(date.getText().trim());
            var input = new ArrayList<Row>();
            for (int index = 0; index < model.getRowCount(); index++) {
                var choice = model.getValueAt(index, 0);
                if (!(choice instanceof Choice value) || value.value() == null) {
                    throw new IllegalArgumentException(text("chooseaccount"));
                }
                input.add(
                        new Row(
                                value.value().number(),
                                amount(model.getValueAt(index, 1)),
                                amount(model.getValueAt(index, 2))));
            }
            var draft = new Draft(first, description.getText(), input);
            var commands = selection.vouchers();
            run(
                    () -> commands.preview(draft),
                    value -> {
                        token = value;
                        StringBuilder summary = new StringBuilder(text("summary"));
                        summary.append("\n")
                                .append(value.companyId())
                                .append(" / ")
                                .append(value.yearId())
                                .append(" / ")
                                .append(value.number())
                                .append("\n")
                                .append(value.draft().date())
                                .append(" : ")
                                .append(literal(value.draft().description()));
                        for (var row : value.draft().rows()) {
                            summary.append("\n")
                                    .append(row.account())
                                    .append(" ")
                                    .append(
                                            literal(
                                                    value.accounts().stream()
                                                            .filter(
                                                                    account ->
                                                                            account.number()
                                                                                    == row
                                                                                            .account())
                                                            .findFirst()
                                                            .orElseThrow()
                                                            .description()))
                                    .append(" : ")
                                    .append(row.debit())
                                    .append(" / ")
                                    .append(row.credit());
                        }
                        summary.append("\n")
                                .append(text("total"))
                                .append(" ")
                                .append(value.total());
                        status.setText(summary.toString());
                    });
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) {
            token = null;
            status.setText(text("failed") + " " + failure.getMessage());
            controls();
        }
    }

    private void post() {
        if (!available() || token == null) return;
        var accepted = token;
        token = null;
        var commands = selection.vouchers();
        run(
                () -> commands.post(accepted),
                number -> {
                    reset();
                    status.setText(text("posted") + " " + number);
                });
    }

    private <T> void run(Callable<T> action, Consumer<T> success) {
        token = null;
        setBusy(true);
        long request = ++revision;
        status.setText(text("working"));
        controls();
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                return action.call();
            }

            protected void done() {
                if (closed || request != revision) return;
                setBusy(false);
                try {
                    var result = get();
                    if (available()) success.accept(result);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException failure) {
                    status.setText(text("failed") + " " + failure.getCause().getMessage());
                }
                if (selection != null && selection.isBlocked()) status.setText(text("blocked"));
                controls();
            }
        }.execute();
    }

    private void invalidatePreview() {
        token = null;
        controls();
    }

    private void controls() {
        boolean enabled = available();
        date.setEnabled(enabled);
        description.setEnabled(enabled);
        rows.setEnabled(enabled);
        accounts.setEnabled(enabled);
        load.setEnabled(enabled);
        add.setEnabled(enabled && model.getRowCount() < 200);
        remove.setEnabled(enabled && rows.getSelectedRow() >= 0);
        preview.setEnabled(
                enabled
                        && !date.getText().isBlank()
                        && !description.getText().isBlank()
                        && model.getRowCount() >= 2);
        post.setEnabled(enabled && token != null);
    }

    private static BigDecimal amount(Object value) {
        String input = value == null ? "" : value.toString().trim();
        return input.isEmpty() ? null : new BigDecimal(input);
    }

    /** Fences callbacks; the owner alone owns the command lease/accepted transactions. */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revision++;
        token = null;
        if (rows.isEditing()) rows.getCellEditor().cancelCellEditing();
        status.setText(text(selection != null && selection.isBlocked() ? "blocked" : "closed"));
        controls();
    }

    private static String literal(String value) {
        return value == null
                ? "null"
                : "\""
                        + value.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t")
                        + "\"";
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.vouchercreate." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Manual voucher panel requires EDT");
        }
    }
}
