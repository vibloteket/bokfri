package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.CompanyDraft;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.CompanyPreview;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Currency;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Explicit empty-company command, independent of document context; construct and control on EDT.
 */
public final class NormalizedCompanyCreationPanel extends JPanel implements AutoCloseable {
    private record CurrencyChoice(Currency value) {
        public String toString() {
            return value == null
                    ? text("choosecurrency")
                    : "[" + value.code() + "] " + value.description();
        }
    }

    private final NormalizedApplicationOwner owner;
    private final Consumer<Integer> created;
    private final JTextField name = new JTextField();
    private final JTextField corporate = new JTextField();
    private final JTextField vat = new JTextField();
    private final JComboBox<CurrencyChoice> currencies = new JComboBox<>();
    private final JComboBox<String> periods =
            new JComboBox<>(new String[] {text("chooseperiod"), "1", "3", "12"});
    private final JComboBox<String> rounding =
            new JComboBox<>(new String[] {text("chooserounding"), text("no"), text("yes")});
    private final JButton load = new JButton(text("load"));
    private final JButton preview = new JButton(text("preview"));
    private final JButton confirm = new JButton(text("confirm"));
    private final JTextArea status = new JTextArea(2, 70);
    private CompanyPreview token;
    private boolean busy;
    private boolean hostAvailable = true;
    private boolean closed;
    private long revision;

    /**
     * Constructs without JDBC or defaults; all database work is performed in background workers.
     */
    public NormalizedCompanyCreationPanel(
            NormalizedApplicationOwner owner, Consumer<Integer> created) {
        super(new BorderLayout(8, 8));
        requireEdt();
        this.owner = java.util.Objects.requireNonNull(owner);
        this.created = java.util.Objects.requireNonNull(created);
        setName("workspace-company-creation");
        name.setName("company-create-name");
        corporate.setName("company-create-corporate");
        vat.setName("company-create-vat");
        currencies.setName("company-create-currency");
        periods.setName("company-create-period");
        rounding.setName("company-create-rounding");
        load.setName("company-create-load");
        preview.setName("company-create-preview");
        confirm.setName("company-create-confirm");
        status.setName("company-create-status");
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel fields = new JPanel(new GridLayout(4, 3, 8, 8));
        fields.add(new JLabel(text("name")));
        fields.add(new JLabel(text("corporate")));
        fields.add(new JLabel(text("vat")));
        fields.add(name);
        fields.add(corporate);
        fields.add(vat);
        fields.add(new JLabel(text("currency")));
        fields.add(new JLabel(text("period")));
        fields.add(new JLabel(text("rounding")));
        fields.add(currencies);
        fields.add(periods);
        fields.add(rounding);
        JPanel actions = new JPanel();
        actions.add(load);
        actions.add(preview);
        actions.add(confirm);
        add(fields, BorderLayout.NORTH);
        add(status);
        add(actions, BorderLayout.SOUTH);
        currencies.addItem(new CurrencyChoice(null));
        currencies.addActionListener(event -> invalidatePreview());
        periods.addActionListener(event -> invalidatePreview());
        rounding.addActionListener(event -> invalidatePreview());
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
        name.getDocument().addDocumentListener(edits);
        corporate.getDocument().addDocumentListener(edits);
        vat.getDocument().addDocumentListener(edits);
        load.addActionListener(event -> load());
        preview.addActionListener(event -> preview());
        confirm.addActionListener(event -> confirm());
        status.setText(text("instructions"));
        controls();
    }

    /** State-only host interlock; requires EDT. */
    public boolean isBusy() {
        requireEdt();
        return busy;
    }

    /**
     * Disables input during host/year commands without cancelling accepted transactions; requires
     * EDT.
     */
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

    private void load() {
        if (!available()) return;
        run(
                owner::companyCurrencies,
                values -> {
                    currencies.removeAllItems();
                    currencies.addItem(new CurrencyChoice(null));
                    for (var currency : values) currencies.addItem(new CurrencyChoice(currency));
                    currencies.setSelectedIndex(0);
                    status.setText(text(values.isEmpty() ? "nocurrencies" : "instructions"));
                });
    }

    private void preview() {
        if (!available()) return;
        var currency = (CurrencyChoice) currencies.getSelectedItem();
        if (currency == null
                || currency.value() == null
                || periods.getSelectedIndex() == 0
                || rounding.getSelectedIndex() == 0) return;
        var draft =
                new CompanyDraft(
                        name.getText(),
                        corporate.getText(),
                        vat.getText(),
                        currency.value().code(),
                        Integer.parseInt((String) periods.getSelectedItem()),
                        rounding.getSelectedIndex() == 2);
        run(
                () -> owner.previewCompany(draft),
                value -> {
                    token = value;
                    var input = value.draft();
                    status.setText(
                            text("summary")
                                    + "\n"
                                    + literal(input.name())
                                    + "\n"
                                    + text("corporate")
                                    + ": "
                                    + literal(input.corporateId())
                                    + "\n"
                                    + text("vat")
                                    + ": "
                                    + literal(input.vatNumber())
                                    + "\n"
                                    + literal(input.currencyCode())
                                    + " : "
                                    + literal(value.currency().description())
                                    + "\n"
                                    + text("period")
                                    + ": "
                                    + input.vatPeriod()
                                    + "\n"
                                    + text("rounding")
                                    + ": "
                                    + text(input.roundingOff() ? "yes" : "no"));
                });
    }

    private void confirm() {
        if (!available() || token == null) return;
        var accepted = token;
        token = null;
        run(
                () -> owner.createCompany(accepted),
                value -> {
                    status.setText(text("created") + " " + value.id() + " : " + value.name());
                    created.accept(value.id());
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
                if (owner.isBlocked()) status.setText(text("blocked"));
                controls();
            }
        }.execute();
    }

    private void invalidatePreview() {
        token = null;
        controls();
    }

    private boolean available() {
        return !closed && !busy && hostAvailable && !owner.isBlocked();
    }

    private void controls() {
        boolean active = available();
        name.setEnabled(active);
        corporate.setEnabled(active);
        vat.setEnabled(active);
        currencies.setEnabled(active);
        periods.setEnabled(active);
        rounding.setEnabled(active);
        load.setEnabled(active);
        preview.setEnabled(
                active
                        && !name.getText().isBlank()
                        && currencies.getSelectedIndex() > 0
                        && periods.getSelectedIndex() > 0
                        && rounding.getSelectedIndex() > 0);
        confirm.setEnabled(active && token != null);
    }

    /** Fences callbacks without cancelling accepted storage work; requires EDT. */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revision++;
        token = null;
        status.setText(text(owner.isBlocked() ? "blocked" : "closed"));
        controls();
    }

    private static String literal(String value) {
        return value == null
                ? text("unset")
                : "\""
                        + value.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t")
                        + "\"";
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.companycreate." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Company creation requires EDT");
        }
    }
}
