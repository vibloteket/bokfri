package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedFinancialReports;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

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

/**
 * Explicit read-only trial balance and account ledger. Background JDBC, EDT UI, no report defaults.
 */
public final class NormalizedFinancialReportsPanel extends JPanel implements AutoCloseable {
    private enum Kind {
        TRIAL,
        LEDGER,
        BALANCE,
        INCOME
    }

    private record KindChoice(Kind value) {
        public String toString() {
            return text(
                    value == null
                            ? "choosekind"
                            : switch (value) {
                                case TRIAL -> "trial";
                                case LEDGER -> "ledger";
                                case BALANCE -> "balance";
                                case INCOME -> "income";
                            });
        }
    }

    private record AccountChoice(NormalizedFinancialReports.Account value) {
        public String toString() {
            return value == null
                    ? text("chooseaccount")
                    : value.number() + " : " + raw(value.description());
        }
    }

    private final JComboBox<KindChoice> kind = new JComboBox<>();
    private final JComboBox<AccountChoice> account = new JComboBox<>();
    private final JTextField from = new JTextField();
    private final JTextField to = new JTextField();
    private final JButton load = new JButton(text("load"));
    private final JButton generate = new JButton(text("generate"));
    private final JTextArea header = new JTextArea(7, 80);
    private final JTextArea status = new JTextArea(3, 80);
    private final DefaultTableModel model =
            new DefaultTableModel() {
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JTable rows = new JTable(model);
    private Selection selection;
    private boolean busy;
    private boolean closed;
    private boolean hostAvailable = true;
    private long revision;

    /** Constructs without SQL, dates, account or report-kind selection. Requires EDT. */
    public NormalizedFinancialReportsPanel() {
        super(new BorderLayout(8, 8));
        requireEdt();
        setName("workspace-financial-reports");
        kind.setName("financial-report-kind");
        account.setName("financial-report-account");
        from.setName("financial-report-from");
        to.setName("financial-report-to");
        load.setName("financial-report-load");
        generate.setName("financial-report-generate");
        header.setName("financial-report-header");
        status.setName("financial-report-status");
        rows.setName("financial-report-rows");
        kind.addItem(new KindChoice(null));
        kind.addItem(new KindChoice(Kind.TRIAL));
        kind.addItem(new KindChoice(Kind.LEDGER));
        kind.addItem(new KindChoice(Kind.BALANCE));
        kind.addItem(new KindChoice(Kind.INCOME));
        account.addItem(new AccountChoice(null));
        header.setEditable(false);
        status.setEditable(false);
        header.setLineWrap(true);
        header.setWrapStyleWord(true);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel fields = new JPanel(new GridLayout(2, 4, 8, 8));
        for (String key : new String[] {"kind", "account", "from", "to"}) {
            fields.add(new JLabel(text(key)));
        }
        fields.add(kind);
        fields.add(account);
        fields.add(from);
        fields.add(to);
        JPanel top = new JPanel(new BorderLayout());
        top.add(fields, BorderLayout.NORTH);
        top.add(new JScrollPane(header));
        JPanel actions = new JPanel();
        actions.add(load);
        actions.add(generate);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JScrollPane(status));
        footer.add(actions, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(rows));
        add(footer, BorderLayout.SOUTH);
        kind.addActionListener(e -> invalidateReport());
        account.addActionListener(e -> invalidateReport());
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        invalidateReport();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        invalidateReport();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        invalidateReport();
                    }
                };
        from.getDocument().addDocumentListener(edits);
        to.getDocument().addDocumentListener(edits);
        load.addActionListener(e -> load());
        generate.addActionListener(e -> generate());
        status.setText(text("unselected"));
        controls();
    }

    /** Clears all old results/choices and fences callbacks on explicit context replacement. */
    public void replaceSelection(Selection value) {
        requireEdt();
        if (closed) return;
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

    /** State-only host interlock; never polls storage. */
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

    private Kind selectedKind() {
        var choice = (KindChoice) kind.getSelectedItem();
        return choice == null ? null : choice.value();
    }

    private void clear() {
        header.setText("");
        model.setRowCount(0);
    }

    private void reset() {
        clear();
        kind.setSelectedIndex(0);
        account.removeAllItems();
        account.addItem(new AccountChoice(null));
        from.setText("");
        to.setText("");
    }

    private void invalidateReport() {
        clear();
        if (!closed && !busy) status.setText(text("instructions"));
        controls();
    }

    private void load() {
        if (!available() || selectedKind() != Kind.LEDGER) return;
        var reports = selection.reports();
        run(
                reports::accounts,
                values -> {
                    account.removeAllItems();
                    account.addItem(new AccountChoice(null));
                    for (var value : values) account.addItem(new AccountChoice(value));
                    status.setText(text("loaded") + " " + values.size());
                });
    }

    private void generate() {
        if (!available()) return;
        clear();
        try {
            LocalDate first =
                    selectedKind() == Kind.BALANCE ? null : LocalDate.parse(from.getText().trim());
            LocalDate last = LocalDate.parse(to.getText().trim());
            var reports = selection.reports();
            if (selectedKind() == Kind.TRIAL) {
                run(
                        () -> reports.trialBalance(first, last),
                        value -> {
                            var report = value.report();
                            model.setColumnIdentifiers(
                                    new String[] {
                                        text("account"),
                                        text("description"),
                                        text("opening"),
                                        text("debit"),
                                        text("credit"),
                                        text("closing")
                                    });
                            for (var row : report.rows()) {
                                model.addRow(
                                        new Object[] {
                                            row.account(),
                                            raw(row.description()),
                                            row.opening(),
                                            row.debit(),
                                            row.credit(),
                                            row.closing()
                                        });
                            }
                            header.setText(
                                    scope(value.scope(), report.from(), report.to())
                                            + "\n"
                                            + text("totals")
                                            + " : "
                                            + report.openingTotal()
                                            + " / "
                                            + report.debitTotal()
                                            + " / "
                                            + report.creditTotal()
                                            + " / "
                                            + report.closingTotal());
                            status.setText(text("generated") + " " + report.rows().size());
                        });
            } else if (selectedKind() == Kind.BALANCE) {
                run(
                        () -> reports.balanceSheet(last),
                        value -> {
                            var report = value.report();
                            model.setColumnIdentifiers(
                                    new String[] {
                                        text("account"), text("description"), text("balanceamount")
                                    });
                            for (var row : report.rows()) {
                                model.addRow(
                                        new Object[] {
                                            row.account(), raw(row.description()), row.balance()
                                        });
                            }
                            header.setText(
                                    scope(value.scope(), selection.year().from(), report.date())
                                            + "\n"
                                            + text("balancescope")
                                            + "\n"
                                            + text("assets")
                                            + " "
                                            + report.assets()
                                            + " / "
                                            + text("liabilities")
                                            + " "
                                            + report.liabilitiesAndEquity()
                                            + " / "
                                            + text("currentresult")
                                            + " "
                                            + report.currentResult()
                                            + " / "
                                            + text("difference")
                                            + " "
                                            + report.difference());
                            status.setText(text("generated"));
                        });
            } else if (selectedKind() == Kind.INCOME) {
                run(
                        () -> reports.incomeStatement(first, last),
                        value -> {
                            var report = value.report();
                            model.setColumnIdentifiers(
                                    new String[] {
                                        text("account"), text("description"), text("incomeamount")
                                    });
                            for (var row : report.rows()) {
                                model.addRow(
                                        new Object[] {
                                            row.account(), raw(row.description()), row.amount()
                                        });
                            }
                            header.setText(
                                    scope(value.scope(), report.from(), report.to())
                                            + "\n"
                                            + text("incomescope")
                                            + "\n"
                                            + text("result")
                                            + " "
                                            + report.result());
                            status.setText(text("generated"));
                        });
            } else if (selectedKind() == Kind.LEDGER) {
                var choice = (AccountChoice) account.getSelectedItem();
                if (choice == null || choice.value() == null) {
                    throw new IllegalArgumentException(text("chooseaccount"));
                }
                int number = choice.value().number();
                run(
                        () -> reports.accountLedger(number, first, last),
                        value -> {
                            var report = value.report();
                            model.setColumnIdentifiers(
                                    new String[] {
                                        text("voucher"),
                                        text("date"),
                                        text("description"),
                                        text("debit"),
                                        text("credit"),
                                        text("running")
                                    });
                            for (var row : report.rows()) {
                                model.addRow(
                                        new Object[] {
                                            row.voucherNumber(),
                                            row.date(),
                                            raw(row.description()),
                                            row.debit(),
                                            row.credit(),
                                            row.balance()
                                        });
                            }
                            header.setText(
                                    scope(value.scope(), report.from(), report.to())
                                            + "\n"
                                            + text("account")
                                            + " : "
                                            + report.account()
                                            + " "
                                            + raw(report.description())
                                            + "\n"
                                            + text("opening")
                                            + " : "
                                            + report.opening()
                                            + " / "
                                            + text("closing")
                                            + " : "
                                            + report.closing());
                            status.setText(text("generated") + " " + report.rows().size());
                        });
            }
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) {
            status.setText(text("failed") + " " + failure.getMessage());
            controls();
        }
    }

    private static String scope(
            NormalizedFinancialReports.Scope scope, LocalDate from, LocalDate to) {
        return text("meaning")
                + "\n"
                + text("context")
                + " : "
                + scope.company()
                + " / "
                + scope.year()
                + " / "
                + raw(scope.companyName())
                + "\n"
                + text("period")
                + " : "
                + from
                + " - "
                + to
                + " / "
                + text("maximum")
                + " : "
                + scope.maximumVoucher();
    }

    private <T> void run(Callable<T> action, Consumer<T> success) {
        clear();
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
                if (selection != null && selection.isBlocked()) {
                    reset();
                    status.setText(text("blocked"));
                }
                controls();
            }
        }.execute();
    }

    private void controls() {
        boolean enabled = available();
        Kind selected = selectedKind();
        kind.setEnabled(enabled);
        from.setEnabled(enabled && selectedKind() != Kind.BALANCE);
        to.setEnabled(enabled);
        account.setEnabled(enabled && selected == Kind.LEDGER);
        load.setEnabled(enabled && selected == Kind.LEDGER);
        var choice = (AccountChoice) account.getSelectedItem();
        generate.setEnabled(
                enabled
                        && selected != null
                        && (selectedKind() == Kind.BALANCE || !from.getText().isBlank())
                        && !to.getText().isBlank()
                        && (selected != Kind.LEDGER || (choice != null && choice.value() != null)));
        rows.setEnabled(enabled);
    }

    /** Clears display and fences callbacks without owning application/database lifetime. */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revision++;
        reset();
        status.setText(text(selection != null && selection.isBlocked() ? "blocked" : "closed"));
        controls();
    }

    private static String raw(String value) {
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
        return SSBundle.getBundle().getString("normalized.financialreports." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Financial reports require EDT");
        }
    }
}
