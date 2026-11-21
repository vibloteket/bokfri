package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.AccountPlan;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.YearPreview;

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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Explicit empty-year creation for a workspace lease; all database work runs off the EDT. */
public final class NormalizedYearCreationPanel extends JPanel implements AutoCloseable {
    private record PlanChoice(AccountPlan value) {
        public String toString() {
            return value == null ? text("chooseplan") : value.id() + " : " + value.name();
        }
    }

    private final NormalizedApplicationOwner owner;
    private final Consumer<Integer> created;
    private final JComboBox<PlanChoice> plans = new JComboBox<>();
    private final JTextField from = new JTextField();
    private final JTextField to = new JTextField();
    private final JButton load = new JButton(text("loadplans"));
    private final JButton preview = new JButton(text("preview"));
    private final JButton confirm = new JButton(text("confirm"));
    private final JTextArea status = new JTextArea();
    private Selection selection;
    private YearPreview token;
    private boolean busy;
    private boolean closed;
    private long revision;

    /** Constructs on the EDT; templates and dates have no defaults. */
    public NormalizedYearCreationPanel(
            NormalizedApplicationOwner owner, Consumer<Integer> created) {
        super(new BorderLayout(8, 8));
        this.owner = owner;
        this.created = created;
        setName("workspace-year-creation");
        from.setName("year-create-from");
        to.setName("year-create-to");
        plans.setName("year-create-plan");
        plans.setEditable(false);
        load.setName("year-create-load");
        preview.setName("year-create-preview");
        confirm.setName("year-create-confirm");
        status.setName("year-create-status");
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel fields = new JPanel(new GridLayout(2, 3, 8, 8));
        fields.add(new JLabel(text("from")));
        fields.add(new JLabel(text("to")));
        fields.add(new JLabel(text("plan")));
        fields.add(from);
        fields.add(to);
        fields.add(plans);
        JPanel actions = new JPanel();
        actions.add(load);
        actions.add(preview);
        actions.add(confirm);
        add(fields, BorderLayout.NORTH);
        add(status);
        add(actions, BorderLayout.SOUTH);
        plans.addItem(new PlanChoice(null));
        plans.addActionListener(event -> invalidatePreview());
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
        from.getDocument().addDocumentListener(edits);
        to.getDocument().addDocumentListener(edits);
        load.addActionListener(event -> loadPlans());
        preview.addActionListener(event -> preview());
        confirm.addActionListener(event -> confirm());
        status.setText(text("unselected"));
        controls();
    }

    /** Retires pending callbacks and preview when the workspace lease changes. Requires EDT. */
    public void replaceSelection(Selection replacement) {
        requireEdt();
        revision++;
        selection = replacement;
        busy = false;
        token = null;
        plans.removeAllItems();
        plans.addItem(new PlanChoice(null));
        from.setText("");
        to.setText("");
        status.setText(text(replacement == null ? "unselected" : "instructions"));
        controls();
    }

    private void loadPlans() {
        if (!available()) return;
        var lease = selection;
        run(
                () -> owner.accountPlans(lease),
                values -> {
                    plans.removeAllItems();
                    plans.addItem(new PlanChoice(null));
                    for (var value : values) plans.addItem(new PlanChoice(value));
                    plans.setSelectedIndex(0);
                    status.setText(text("instructions"));
                });
    }

    private void preview() {
        if (!available()) return;
        var choice = (PlanChoice) plans.getSelectedItem();
        if (choice == null || choice.value() == null) return;
        try {
            var first = LocalDate.parse(from.getText().trim());
            var last = LocalDate.parse(to.getText().trim());
            var lease = selection;
            run(
                    () -> owner.previewYear(lease, first, last, choice.value().id()),
                    value -> {
                        token = value;
                        status.setText(
                                text("summary")
                                        + "\n"
                                        + value.company().id()
                                        + " : "
                                        + value.company().name()
                                        + "\n"
                                        + value.from()
                                        + " - "
                                        + value.to()
                                        + "\n"
                                        + value.plan().id()
                                        + " : "
                                        + value.plan().name());
                    });
        } catch (java.time.format.DateTimeParseException failure) {
            token = null;
            status.setText(text("invaliddates"));
            controls();
        }
    }

    private void confirm() {
        if (!available() || token == null) return;
        var accepted = token;
        token = null;
        run(
                () -> owner.createYear(accepted),
                value -> {
                    status.setText(text("created") + " " + value.id());
                    created.accept(accepted.company().id());
                });
    }

    private <T> void run(Callable<T> action, Consumer<T> success) {
        token = null;
        busy = true;
        long request = ++revision;
        status.setText(text("working"));
        controls();
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                return action.call();
            }

            protected void done() {
                if (closed || request != revision) return;
                busy = false;
                try {
                    var result = get();
                    if (selection != null && selection.isActive() && !owner.isBlocked()) {
                        success.accept(result);
                    }
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
        return !closed && !busy && selection != null && selection.isActive() && !owner.isBlocked();
    }

    private void controls() {
        boolean enabled = available();
        load.setEnabled(enabled);
        from.setEnabled(enabled);
        to.setEnabled(enabled);
        plans.setEnabled(enabled);
        preview.setEnabled(
                enabled
                        && plans.getSelectedItem() instanceof PlanChoice choice
                        && choice.value() != null
                        && !from.getText().isBlank()
                        && !to.getText().isBlank());
        confirm.setEnabled(enabled && token != null);
    }

    /**
     * Fences callbacks without cancelling an already accepted database transaction. Requires EDT.
     */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revision++;
        token = null;
        status.setText(text(owner.isBlocked() ? "blocked" : "closed"));
        controls();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.yearcreate." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Year creation requires EDT");
        }
    }
}
