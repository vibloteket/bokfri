package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedVoucherRegister.Header;
import org.fribok.bookkeeping.application.NormalizedVoucherRegister.Page;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Explicit read-only journal browsing; EDT UI, background lease-scoped JDBC, no model defaults. */
public final class NormalizedVoucherRegisterPanel extends JPanel implements AutoCloseable {
    private final JButton refresh = new JButton(text("refresh"));
    private final JButton next = new JButton(text("next"));
    private final JButton open = new JButton(text("open"));
    private final JTextArea status = new JTextArea(3, 70);
    private final JTextArea header = new JTextArea(6, 70);
    private final DefaultTableModel listModel = model("number", "date", "description");
    private final DefaultTableModel rowModel =
            model(
                    "row",
                    "accountid",
                    "account",
                    "accountlabel",
                    "debit",
                    "credit",
                    "project",
                    "projectlabel",
                    "projectexists",
                    "unit",
                    "unitlabel",
                    "unitexists",
                    "edited",
                    "signature",
                    "crossed",
                    "added");
    private final JTable list = new JTable(listModel);
    private final JTable rows = new JTable(rowModel);
    private Selection selection;
    private List<Header> headers = List.of();
    private Page page;
    private boolean busy;
    private boolean hostAvailable = true;
    private boolean closed;
    private long revision;

    /** Constructs without reads, auto-selection, or writes; requires EDT. */
    public NormalizedVoucherRegisterPanel() {
        super(new BorderLayout(8, 8));
        requireEdt();
        setName("workspace-voucher-register");
        refresh.setName("voucher-register-refresh");
        next.setName("voucher-register-next");
        open.setName("voucher-register-open");
        list.setName("voucher-register-list");
        rows.setName("voucher-register-rows");
        header.setName("voucher-register-header");
        status.setName("voucher-register-status");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rows.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        status.setEditable(false);
        header.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel actions = new JPanel();
        actions.add(refresh);
        actions.add(next);
        actions.add(open);
        JPanel top = new JPanel(new BorderLayout());
        top.add(actions, BorderLayout.NORTH);
        top.add(new JScrollPane(list));
        JPanel detail = new JPanel(new BorderLayout());
        detail.add(new JScrollPane(header), BorderLayout.NORTH);
        detail.add(new JScrollPane(rows));
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detail);
        split.setResizeWeight(0.4);
        add(split);
        add(new JScrollPane(status), BorderLayout.SOUTH);
        list.getSelectionModel()
                .addListSelectionListener(
                        event -> {
                            clearDetail();
                            controls();
                        });
        refresh.addActionListener(event -> load(null));
        next.addActionListener(
                event -> {
                    if (page != null && page.nextBefore() != null) load(page.nextBefore());
                });
        open.addActionListener(event -> open());
        status.setText(text("unselected"));
        controls();
    }

    /** Clears data and fences old callbacks on context replacement. Requires EDT. */
    public void replaceSelection(Selection value) {
        requireEdt();
        if (closed) return;
        revision++;
        selection = value;
        setBusy(false);
        clear();
        status.setText(text("instructions"));
        controls();
    }

    public boolean isBusy() {
        requireEdt();
        return busy;
    }

    /** State-only host interlock, with no storage polling. */
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

    private void clearDetail() {
        header.setText("");
        rowModel.setRowCount(0);
    }

    private void clear() {
        page = null;
        headers = List.of();
        listModel.setRowCount(0);
        clearDetail();
    }

    private void load(Integer before) {
        if (!available()) return;
        var reader = selection.voucherRegister();
        clear();
        run(
                () -> reader.page(before),
                value -> {
                    page = value;
                    headers = value.headers();
                    for (var item : headers) {
                        listModel.addRow(
                                new Object[] {
                                    item.number(), raw(item.date()), raw(item.description())
                                });
                    }
                    status.setText(
                            text(headers.isEmpty() ? "empty" : "loaded")
                                    + " "
                                    + headers.size()
                                    + (value.nextBefore() == null ? "" : " / " + text("more")));
                });
    }

    private void open() {
        if (!available() || list.getSelectedRow() < 0) return;
        var chosen = headers.get(list.getSelectedRow());
        var reader = selection.voucherRegister();
        clearDetail();
        run(
                () -> reader.detail(chosen.id()),
                detail -> {
                    var item = detail.header();
                    header.setText(
                            text("readonly")
                                    + "\n"
                                    + text("identity")
                                    + " : "
                                    + item.id()
                                    + " / "
                                    + text("number")
                                    + " : "
                                    + item.number()
                                    + "\n"
                                    + text("date")
                                    + " : "
                                    + raw(item.date())
                                    + "\n"
                                    + text("description")
                                    + " : "
                                    + raw(item.description())
                                    + "\n"
                                    + text("corrects")
                                    + " : "
                                    + raw(item.correctsId())
                                    + " / "
                                    + raw(item.correctsNumber())
                                    + "\n"
                                    + text("correctedby")
                                    + " : "
                                    + raw(item.correctedById())
                                    + " / "
                                    + raw(item.correctedByNumber()));
                    for (var row : detail.rows()) {
                        rowModel.addRow(
                                new Object[] {
                                    row.number(),
                                    raw(row.accountId()),
                                    raw(row.accountNumber()),
                                    raw(row.accountDescription()),
                                    raw(row.debit()),
                                    raw(row.credit()),
                                    raw(row.project()),
                                    raw(row.projectName()),
                                    row.projectExists(),
                                    raw(row.unit()),
                                    raw(row.unitName()),
                                    row.unitExists(),
                                    raw(row.editedAt()),
                                    raw(row.signature()),
                                    row.crossed(),
                                    row.added()
                                });
                    }
                    status.setText(text("opened") + " " + detail.rows().size());
                });
    }

    private <T> void run(Callable<T> action, Consumer<T> success) {
        setBusy(true);
        long request = ++revision;
        status.setText(text("working"));
        controls();
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                return action.call();
            }

            protected void done() {
                if (closed || revision != request) return;
                setBusy(false);
                try {
                    T result = get();
                    if (available()) success.accept(result);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException failure) {
                    status.setText(text("failed") + " " + failure.getCause().getMessage());
                }
                if (selection != null && selection.isBlocked()) {
                    clear();
                    status.setText(text("blocked"));
                }
                controls();
            }
        }.execute();
    }

    private void controls() {
        boolean enabled = available();
        refresh.setEnabled(enabled);
        next.setEnabled(enabled && page != null && page.nextBefore() != null);
        open.setEnabled(enabled && list.getSelectedRow() >= 0);
        list.setEnabled(enabled);
        rows.setEnabled(enabled);
    }

    private static DefaultTableModel model(String... keys) {
        var names =
                java.util.Arrays.stream(keys).map(NormalizedVoucherRegisterPanel::text).toArray();
        return new DefaultTableModel(names, 0) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static String raw(Object value) {
        if (value == null) return "null";
        if (!(value instanceof String input)) return value.toString();
        return "\""
                + input.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    /** Fences callbacks and clears displayed data; does not own the reader or database lifetime. */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revision++;
        clear();
        status.setText(text(selection != null && selection.isBlocked() ? "blocked" : "closed"));
        controls();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.voucherregister." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Voucher register requires EDT");
        }
    }
}
