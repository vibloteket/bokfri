package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedOpeningBalanceCommands;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Explicit initial balances only; background JDBC and EDT-only, fenced preview/confirmation. */
public final class NormalizedOpeningBalancesPanel extends JPanel implements AutoCloseable {
    private record Choice(NormalizedOpeningBalanceCommands.Account value) {
        public String toString() {
            return value == null
                    ? text("chooseaccount")
                    : value.number() + " : " + literal(value.description());
        }
    }

    private final JComboBox<Choice> account = new JComboBox<>();
    private final JTextField amount = new JTextField(18);
    private final JButton load = new JButton(text("load"));
    private final JButton add = new JButton(text("add"));
    private final JButton remove = new JButton(text("remove"));
    private final JButton preview = new JButton(text("preview"));
    private final JButton confirm = new JButton(text("confirm"));
    private final JTextArea header = new JTextArea(7, 80);
    private final JTextArea status = new JTextArea(3, 80);
    private final DefaultTableModel model =
            new DefaultTableModel(
                    new String[] {text("account"), text("description"), text("amount")}, 0) {
                public boolean isCellEditable(int row, int col) {
                    return false;
                }
            };
    private final JTable rows = new JTable(model);
    private final ArrayList<NormalizedOpeningBalanceCommands.Entry> entries = new ArrayList<>();
    private Selection selection;
    private NormalizedOpeningBalanceCommands.Preview token;
    private boolean busy;
    private boolean closed;
    private boolean hostAvailable = true;
    private long revision;

    /** Constructs without storage access or inferred accounts/amounts. Requires EDT. */
    public NormalizedOpeningBalancesPanel() {
        super(new BorderLayout(8, 8));
        requireEdt();
        setName("workspace-opening-balances");
        account.setName("opening-account");
        amount.setName("opening-amount");
        load.setName("opening-load");
        add.setName("opening-add");
        remove.setName("opening-remove");
        preview.setName("opening-preview");
        confirm.setName("opening-confirm");
        rows.setName("opening-rows");
        header.setName("opening-header");
        status.setName("opening-status");
        account.addItem(new Choice(null));
        header.setEditable(false);
        status.setEditable(false);
        header.setLineWrap(true);
        status.setLineWrap(true);
        header.setWrapStyleWord(true);
        status.setWrapStyleWord(true);
        JPanel input = new JPanel();
        input.add(load);
        input.add(account);
        input.add(amount);
        input.add(add);
        input.add(remove);
        JPanel top = new JPanel(new BorderLayout());
        top.add(input, BorderLayout.NORTH);
        top.add(new JScrollPane(header));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(rows));
        JPanel actions = new JPanel();
        actions.add(preview);
        actions.add(confirm);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JScrollPane(status));
        bottom.add(actions, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        load.addActionListener(e -> load());
        add.addActionListener(e -> addEntry());
        remove.addActionListener(e -> removeEntry());
        preview.addActionListener(e -> preview());
        confirm.addActionListener(e -> confirm());
        account.addActionListener(e -> invalidatePreview());
        amount.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                invalidatePreview();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                invalidatePreview();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                invalidatePreview();
                            }
                        });
        rows.getSelectionModel().addListSelectionListener(e -> controls());
        status.setText(text("instructions"));
        controls();
    }

    /** Replaces context, clearing all draft state and fencing outstanding callbacks. */
    public void replaceSelection(Selection value) {
        requireEdt();
        if (closed) {
            return;
        }
        revision++;
        selection = value;
        setBusy(false);
        reset();
        status.setText(text("instructions"));
        controls();
    }

    public boolean isBusy() {
        requireEdt();
        return busy;
    }

    public void setHostAvailable(boolean value) {
        requireEdt();
        hostAvailable = value;
        controls();
    }

    private boolean available() {
        return !closed
                && !busy
                && hostAvailable
                && selection != null
                && selection.isActive()
                && !selection.isBlocked();
    }

    private void setBusy(boolean value) {
        boolean old = busy;
        busy = value;
        firePropertyChange("busy", old, value);
    }

    private void invalidatePreview() {
        token = null;
        header.setText("");
        controls();
    }

    private void reset() {
        entries.clear();
        model.setRowCount(0);
        account.removeAllItems();
        account.addItem(new Choice(null));
        amount.setText("");
        invalidatePreview();
    }

    private void load() {
        if (!available()) {
            return;
        }
        invalidatePreview();
        var commands = selection.openingBalances();
        run(
                commands::accounts,
                values -> {
                    reset();
                    for (var value : values) account.addItem(new Choice(value));
                    status.setText(text("loaded"));
                });
    }

    private void addEntry() {
        if (!available()) {
            return;
        }
        invalidatePreview();
        try {
            var choice = (Choice) account.getSelectedItem();
            if (choice == null || choice.value() == null) {
                throw new IllegalArgumentException(text("chooseaccount"));
            }
            // Plain signed decimal only: no grouping, exponent, rounding or locale inference.
            String raw = amount.getText().trim();
            if (!raw.matches("[+-]?[0-9]+(?:\\.[0-9]{1,2})?")) {
                throw new IllegalArgumentException(text("signedamount"));
            }
            var value = new BigDecimal(raw);
            if (value.signum() == 0
                    || entries.stream().anyMatch(e -> e.account() == choice.value().number())) {
                throw new IllegalArgumentException(text("unique"));
            }
            entries.add(new NormalizedOpeningBalanceCommands.Entry(choice.value().number(), value));
            model.addRow(
                    new Object[] {
                        choice.value().number(),
                        literal(choice.value().description()),
                        value.toPlainString()
                    });
            account.setSelectedIndex(0);
            amount.setText("");
            status.setText(text("instructions"));
        } catch (IllegalArgumentException e) {
            status.setText(text("failed") + " " + e.getMessage());
        }
        controls();
    }

    private void removeEntry() {
        if (!available()) {
            return;
        }
        int row = rows.getSelectedRow();
        if (row < 0) {
            return;
        }
        invalidatePreview();
        entries.remove(row);
        model.removeRow(row);
        controls();
    }

    private void preview() {
        if (!available()) {
            return;
        }
        invalidatePreview();
        var commands = selection.openingBalances();
        var draft = new NormalizedOpeningBalanceCommands.Draft(entries);
        run(
                () -> commands.preview(draft),
                value -> {
                    token = value;
                    BigDecimal debit = BigDecimal.ZERO;
                    BigDecimal credit = BigDecimal.ZERO;
                    for (var entry : value.draft().entries()) {
                        if (entry.amount().signum() > 0) {
                            debit = debit.add(entry.amount());
                        } else credit = credit.subtract(entry.amount());
                    }
                    header.setText(
                            text("scope")
                                    + " "
                                    + value.companyId()
                                    + " / "
                                    + value.yearId()
                                    + "\n"
                                    + value.startsOn()
                                    + " - "
                                    + value.endsOn()
                                    + "\n"
                                    + text("plan")
                                    + " "
                                    + value.planType()
                                    + "\n"
                                    + text("debit")
                                    + " "
                                    + debit.toPlainString()
                                    + " / "
                                    + text("credit")
                                    + " "
                                    + credit.toPlainString()
                                    + "\n"
                                    + text("warning"));
                    status.setText(text("previewready"));
                });
    }

    private void confirm() {
        if (!available() || token == null) {
            return;
        }
        var accepted = token;
        token = null;
        var commands = selection.openingBalances();
        run(
                () -> commands.create(accepted),
                count -> {
                    reset();
                    status.setText(text("created") + " " + count);
                });
    }

    private <T> void run(Callable<T> task, Consumer<T> apply) {
        long request = ++revision;
        setBusy(true);
        status.setText(text("working"));
        controls();
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                return task.call();
            }

            protected void done() {
                if (closed || request != revision) {
                    return;
                }
                try {
                    apply.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    invalidatePreview();
                    status.setText(text("failed"));
                } catch (ExecutionException e) {
                    invalidatePreview();
                    status.setText(text("failed") + " " + e.getCause().getMessage());
                } finally {
                    setBusy(false);
                    if (selection != null && selection.isBlocked()) {
                        invalidatePreview();
                        status.setText(text("blocked"));
                    }
                    controls();
                }
            }
        }.execute();
    }

    private void controls() {
        boolean enabled = available();
        load.setEnabled(enabled);
        account.setEnabled(enabled);
        amount.setEnabled(enabled);
        add.setEnabled(
                enabled
                        && account.getSelectedIndex() > 0
                        && !amount.getText().isBlank()
                        && entries.size() < 200);
        remove.setEnabled(enabled && rows.getSelectedRow() >= 0);
        preview.setEnabled(enabled && entries.size() >= 2);
        confirm.setEnabled(enabled && token != null);
        rows.setEnabled(enabled);
    }

    /** Retires callbacks without cancelling an accepted transaction or closing the owner. */
    public void close() {
        requireEdt();
        if (closed) {
            return;
        }
        closed = true;
        revision++;
        setBusy(false);
        reset();
        selection = null;
        status.setText(text("closed"));
        controls();
    }

    private static String literal(String value) {
        return value == null
                ? "NULL"
                : "\""
                        + value.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t")
                        + "\"";
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.openingbalances." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Opening balances require EDT");
        }
    }
}
