package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.ReversalPreview;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Explicit exact reversal of eligible standalone vouchers. EDT UI, lease-owned background JDBC. */
public final class NormalizedVoucherReversalPanel extends JPanel implements AutoCloseable {
    private final JTextField source = new JTextField();
    private final JTextField date = new JTextField();
    private final JTextField description = new JTextField();
    private final JButton preview = new JButton(text("preview"));
    private final JButton post = new JButton(text("post"));
    private final JTextArea status = new JTextArea(18, 80);
    private Selection selection;
    private ReversalPreview token;
    private boolean busy;
    private boolean closed;
    private boolean hostAvailable = true;
    private long revision;

    /** Constructs without storage access, defaults or automatic journal selection. Requires EDT. */
    public NormalizedVoucherReversalPanel() {
        super(new BorderLayout(8, 8));
        requireEdt();
        setName("workspace-voucher-reversal");
        source.setName("voucher-reversal-source");
        date.setName("voucher-reversal-date");
        description.setName("voucher-reversal-description");
        preview.setName("voucher-reversal-preview");
        post.setName("voucher-reversal-post");
        status.setName("voucher-reversal-status");
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel fields = new JPanel(new GridLayout(2, 3, 8, 8));
        fields.add(new JLabel(text("source")));
        fields.add(new JLabel(text("date")));
        fields.add(new JLabel(text("description")));
        fields.add(source);
        fields.add(date);
        fields.add(description);
        JPanel actions = new JPanel();
        actions.add(preview);
        actions.add(post);
        add(fields, BorderLayout.NORTH);
        add(new JScrollPane(status));
        add(actions, BorderLayout.SOUTH);
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        invalidate();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        invalidate();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        invalidate();
                    }

                    private void invalidate() {
                        token = null;
                        controls();
                    }
                };
        source.getDocument().addDocumentListener(edits);
        date.getDocument().addDocumentListener(edits);
        description.getDocument().addDocumentListener(edits);
        preview.addActionListener(e -> preview());
        post.addActionListener(e -> post());
        status.setText(text("unselected"));
        controls();
    }

    /** Fences callbacks and clears draft/token on an explicitly applied lease. */
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

    /** State-only interlock, never polls SQL. */
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

    private void reset() {
        token = null;
        source.setText("");
        date.setText("");
        description.setText("");
    }

    private void preview() {
        if (!available()) return;
        try {
            long id = Long.parseLong(source.getText().trim());
            LocalDate day = LocalDate.parse(date.getText().trim());
            String label = description.getText();
            var commands = selection.vouchers();
            run(
                    () -> commands.previewReversal(id, day, label),
                    value -> {
                        token = value;
                        var original = value.source();
                        StringBuilder summary = new StringBuilder(text("summary"));
                        summary.append("\n")
                                .append(text("context"))
                                .append(" : ")
                                .append(value.companyId())
                                .append(" / ")
                                .append(value.yearId());
                        summary.append("\n")
                                .append(text("original"))
                                .append(" : ")
                                .append(original.id())
                                .append(" / ")
                                .append(original.number())
                                .append(" / ")
                                .append(original.date())
                                .append(" / ")
                                .append(raw(original.description()));
                        for (var row : original.rows()) {
                            summary.append("\n").append(row(value, row));
                        }
                        summary.append("\n")
                                .append(text("reversal"))
                                .append(" : ")
                                .append(value.number())
                                .append(" / ")
                                .append(value.draft().date())
                                .append(" / ")
                                .append(raw(value.draft().description()));
                        for (var row : value.draft().rows()) {
                            summary.append("\n").append(row(value, row));
                        }
                        summary.append("\n")
                                .append(text("total"))
                                .append(" : ")
                                .append(value.total());
                        status.setText(summary.toString());
                    });
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) {
            token = null;
            status.setText(text("failed") + " " + failure.getMessage());
            controls();
        }
    }

    private static String row(ReversalPreview value, Row row) {
        String account =
                value.accounts().stream()
                        .filter(a -> a.number() == row.account())
                        .findFirst()
                        .orElseThrow()
                        .description();
        String project =
                row.project() == null
                        ? null
                        : value.projects().stream()
                                .filter(p -> p.number().equals(row.project()))
                                .findFirst()
                                .orElseThrow()
                                .name();
        String unit =
                row.unit() == null
                        ? null
                        : value.units().stream()
                                .filter(u -> u.number().equals(row.unit()))
                                .findFirst()
                                .orElseThrow()
                                .name();
        return row.account()
                + " "
                + raw(account)
                + " : "
                + row.debit()
                + " / "
                + row.credit()
                + " | "
                + text("project")
                + " "
                + raw(row.project())
                + " "
                + raw(project)
                + " | "
                + text("unit")
                + " "
                + raw(row.unit())
                + " "
                + raw(unit);
    }

    private void post() {
        if (!available() || token == null) return;
        var accepted = token;
        token = null;
        var commands = selection.vouchers();
        run(
                () -> commands.postReversal(accepted),
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
                if (selection != null && selection.isBlocked()) {
                    reset();
                    status.setText(text("blocked"));
                }
                controls();
            }
        }.execute();
    }

    private void controls() {
        boolean active = available();
        source.setEnabled(active);
        date.setEnabled(active);
        description.setEnabled(active);
        preview.setEnabled(
                active
                        && !source.getText().isBlank()
                        && !date.getText().isBlank()
                        && !description.getText().isBlank());
        post.setEnabled(active && token != null);
    }

    /** Fences callbacks without cancelling accepted writes or owning the database lifetime. */
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
        return SSBundle.getBundle().getString("normalized.voucherreversal." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Voucher reversal requires EDT");
        }
    }
}
