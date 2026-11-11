package se.swedsoft.bookkeeping.gui.creditinvoice.panel;

import org.fribok.bookkeeping.gui.creditinvoice.CustomerCreditDialogAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Restricted normalized credit request form, not the legacy arbitrary financial-row editor.
 * Backend actions run off the EDT; success notifications occur on the EDT only after confirmed commit.
 */
public final class NormalizedCreditRequestPanel extends JPanel {
    private static final Logger LOG = LoggerFactory.getLogger(NormalizedCreditRequestPanel.class);
    private final ResourceBundle bundle = SSBundle.getBundle();
    private final CustomerCreditDialogAdapter adapter;
    private final Consumer<CustomerCreditDialogAdapter.Created> saved;
    private final Runnable close;
    private final JTextField date = new JTextField(12), amount = new JTextField(12);
    private final JButton preview = new JButton(text("preview")), create = new JButton(text("create"));
    private final JButton cancel = new JButton(text("cancel"));
    private final JTextArea status = new JTextArea(4, 48);
    private CustomerCreditDialogAdapter.Preview current;
    private boolean busy, finished;
    private long revision;

    /** Constructs Swing components only; no database or legacy singleton access occurs here. */
    public NormalizedCreditRequestPanel(CustomerCreditDialogAdapter adapter,
            Consumer<CustomerCreditDialogAdapter.Created> saved, Runnable close) {
        super(new BorderLayout(8, 8));
        this.adapter = java.util.Objects.requireNonNull(adapter, "adapter");
        this.saved = java.util.Objects.requireNonNull(saved, "saved callback");
        this.close = java.util.Objects.requireNonNull(close, "close callback");
        date.setName("credit-date"); amount.setName("credit-amount");
        preview.setName("credit-preview"); create.setName("credit-create"); cancel.setName("credit-cancel");
        status.setName("credit-status"); status.setEditable(false);
        status.setLineWrap(true); status.setWrapStyleWord(true);
        date.setText(adapter.defaultDate().toString());
        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        JLabel dateLabel = new JLabel(text("date")), amountLabel = new JLabel(text("amount"));
        dateLabel.setLabelFor(date); amountLabel.setLabelFor(amount);
        fields.add(dateLabel); fields.add(date); fields.add(amountLabel); fields.add(amount);
        add(fields, BorderLayout.NORTH); add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(); buttons.add(preview); buttons.add(create); buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH); status.setText(text("ready")); create.setEnabled(false);
        DocumentListener changed = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { invalidatePreview(); }
            public void removeUpdate(DocumentEvent event) { invalidatePreview(); }
            public void changedUpdate(DocumentEvent event) { invalidatePreview(); }
        };
        date.getDocument().addDocumentListener(changed); amount.getDocument().addDocumentListener(changed);
        preview.addActionListener(event -> preview()); create.addActionListener(event -> save());
        cancel.addActionListener(event -> { if (!busy) close.run(); });
    }

    /** The owning dialog must not dispose the form while a backend action is in flight. */
    public boolean isBusy() { return busy; }

    private String text(String key) { return bundle.getString("creditinvoice.normalized." + key); }

    private void invalidatePreview() {
        revision++; current = null; create.setEnabled(false);
        if (!busy && !finished) status.setText(text("stale"));
    }

    private void controls(boolean active) {
        date.setEnabled(active); amount.setEnabled(active); preview.setEnabled(active);
        create.setEnabled(active && current != null); cancel.setEnabled(!busy);
    }

    private void preview() {
        if (busy || finished || adapter.hasUncertainCommit()) return;
        final LocalDate requestedDate;
        final BigDecimal requestedAmount;
        try { requestedDate = LocalDate.parse(date.getText().trim()); }
        catch (DateTimeParseException invalid) { status.setText(text("invaliddate")); return; }
        String value = amount.getText().trim();
        try {
            if (!value.isEmpty() && !value.matches("[0-9]+([.,][0-9]+)?")) throw new NumberFormatException();
            requestedAmount = value.isEmpty() ? null : new BigDecimal(value.replace(',', '.'));
            if (requestedAmount != null && requestedAmount.signum() <= 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { status.setText(text("invalidamount")); return; }
        final long requestedRevision = revision;
        current = null; busy = true; controls(false); status.setText(text("busy"));
        new SwingWorker<CustomerCreditDialogAdapter.Preview, Void>() {
            protected CustomerCreditDialogAdapter.Preview doInBackground() throws Exception {
                return adapter.preview(requestedDate, requestedAmount);
            }
            protected void done() {
                busy = false;
                try {
                    var result = get();
                    if (revision != requestedRevision) {
                        current = null; status.setText(text("stale")); controls(true); return;
                    }
                    current = result;
                    status.setText(MessageFormat.format(text("summary"), Integer.toString(current.invoiceNumber()),
                            Integer.toString(current.creditNumber()), current.date().toString(),
                            current.amount().toPlainString(), current.balance().toPlainString()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); failure(interrupted);
                } catch (ExecutionException failed) { failure(failed.getCause()); }
                controls(!adapter.hasUncertainCommit());
            }
        }.execute();
    }

    private void save() {
        if (busy || finished || current == null || adapter.hasUncertainCommit()) return;
        var confirmed = current; busy = true; controls(false); status.setText(text("busy"));
        new SwingWorker<CustomerCreditDialogAdapter.Created, Void>() {
            protected CustomerCreditDialogAdapter.Created doInBackground() throws Exception {
                return adapter.save(confirmed);
            }
            protected void done() {
                busy = false;
                final CustomerCreditDialogAdapter.Created result;
                try { result = get(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); failure(interrupted);
                    controls(!adapter.hasUncertainCommit()); return;
                } catch (ExecutionException failed) {
                    failure(failed.getCause()); controls(!adapter.hasUncertainCommit()); return;
                }
                finished = true; current = null; status.setText(text("saved")); controls(false);
                // Callback errors are not write failures: the credit has already committed.
                try { saved.accept(result); }
                catch (RuntimeException notification) {
                    LOG.error("Credit committed; GUI notification failed", notification);
                }
                finally { close.run(); }
            }
        }.execute();
    }

    private void failure(Throwable failure) {
        current = null;
        status.setText(adapter.hasUncertainCommit() ? text("uncertain")
                : MessageFormat.format(text("failed"), failure.getMessage()));
    }
}
