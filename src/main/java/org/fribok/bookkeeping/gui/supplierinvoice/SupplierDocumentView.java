package org.fribok.bookkeeping.gui.supplierinvoice;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * Explicit normalized supplier tables and journal actions; never a legacy frame/model/cache
 * refresh.
 */
public final class SupplierDocumentView extends JPanel implements AutoCloseable {
    private final JFrame owner;
    private final Rows invoices = new Rows(false);
    private final Rows credits = new Rows(true);
    private final JTable invoiceTable = new JTable(invoices);
    private final JTable creditTable = new JTable(credits);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel context = new JLabel();
    private final JLabel status = new JLabel();
    private final JButton refresh = new JButton(text("refresh"));
    private final JButton invoiceJournal = new JButton(text("invoicejournal"));
    private final JButton creditJournal = new JButton(text("creditjournal"));
    private final List<SupplierJournalDialog> dialogs = new ArrayList<>();
    private final WindowAdapter ownerClosed =
            new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    close();
                }
            };
    private SupplierDocumentGuiSession session;
    private LocalDate yearFrom;
    private LocalDate yearTo;
    private long revision;
    private boolean closed;
    private boolean loading;
    private boolean loaded;

    /**
     * Constructs on the EDT with an explicit selection; initial loading runs in a background
     * worker.
     */
    public SupplierDocumentView(JFrame owner, SupplierDocumentGuiSession session) {
        super(new BorderLayout(8, 8));
        requireEdt();
        this.owner = Objects.requireNonNull(owner, "owner");
        setName("normalized-supplier-documents");
        context.setName("supplier-document-context");
        status.setName("supplier-document-status");
        invoiceTable.setName("normalized-supplier-invoices");
        creditTable.setName("normalized-supplier-credits");
        invoiceTable.setAutoCreateRowSorter(true);
        creditTable.setAutoCreateRowSorter(true);
        invoiceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        creditTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabs.setName("supplier-document-tabs");
        tabs.addTab(text("invoices"), new JScrollPane(invoiceTable));
        tabs.addTab(text("credits"), new JScrollPane(creditTable));
        refresh.setName("supplier-document-refresh");
        invoiceJournal.setName("supplier-invoice-journal");
        creditJournal.setName("supplier-credit-journal");
        JPanel actions = new JPanel();
        actions.add(refresh);
        actions.add(invoiceJournal);
        actions.add(creditJournal);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status);
        bottom.add(actions, BorderLayout.SOUTH);
        add(context, BorderLayout.NORTH);
        add(tabs);
        add(bottom, BorderLayout.SOUTH);
        refresh.addActionListener(event -> refresh());
        invoiceJournal.addActionListener(event -> journal(SupplierJournalAdapter.Kind.INVOICE));
        creditJournal.addActionListener(event -> journal(SupplierJournalAdapter.Kind.CREDIT));
        replaceSelection(session);
        owner.addWindowListener(ownerClosed);
    }

    /** Changes family without consulting legacy row selection; used by owner-bound menu routing. */
    public void showFamily(SupplierJournalAdapter.Kind kind) {
        requireEdt();
        Objects.requireNonNull(kind, "kind");
        if (!closed && session.isActive() && !session.isBlocked()) {
            tabs.setSelectedIndex(kind == SupplierJournalAdapter.Kind.INVOICE ? 0 : 1);
        }
    }

    /**
     * Replaces the explicit binding, immediately clearing old rows/dates and invalidating old
     * dialogs.
     */
    public void replaceSelection(SupplierDocumentGuiSession next) {
        requireEdt();
        Objects.requireNonNull(next, "session");
        if (closed) {
            throw new IllegalStateException("Supplier document view is closed");
        }
        if (!next.isActive()) {
            throw new IllegalArgumentException("Cannot bind a closed supplier selection");
        }
        if (session == next) {
            refresh();
            return;
        }
        var previous = session;
        session = next;
        revision++;
        for (var dialog : List.copyOf(dialogs)) {
            dialog.invalidateSelection();
        }
        if (previous != null) {
            previous.close();
        }
        setTables(List.of(), List.of());
        yearFrom = null;
        yearTo = null;
        loaded = false;
        loading = false;
        context.setText(text("loading"));
        SupplierDocumentMenuBinding.install(
                owner,
                next,
                this::showFamily,
                () -> {
                    if (session == next) {
                        close();
                    }
                });
        refresh();
    }

    /** Loads both immutable company-wide tables in one dedicated transaction, never on the EDT. */
    public void refresh() {
        requireEdt();
        if (closed || !session.isActive() || session.isBlocked()) {
            controls();
            return;
        }
        var selected = session;
        long request = ++revision;
        Long invoiceId = selection(invoiceTable, invoices);
        Long creditId = selection(creditTable, credits);
        loading = true;
        loaded = false;
        status.setText(text("loading"));
        controls();
        new SwingWorker<SupplierDocumentGuiSession.Documents, Void>() {
            @Override
            protected SupplierDocumentGuiSession.Documents doInBackground() throws Exception {
                return selected.documents();
            }

            @Override
            protected void done() {
                if (closed || request != revision || session != selected || !selected.isActive()) {
                    return;
                }
                loading = false;
                try {
                    var snapshot = get();
                    setTables(snapshot.invoices(), snapshot.credits());
                    yearFrom = snapshot.from();
                    yearTo = snapshot.to();
                    loaded = true;
                    restore(invoiceTable, invoices, invoiceId);
                    restore(creditTable, credits, creditId);
                    context.setText(
                            MessageFormat.format(
                                    text("context"),
                                    snapshot.companyName(),
                                    Integer.toString(snapshot.companyId()),
                                    String.valueOf(snapshot.from()),
                                    String.valueOf(snapshot.to()),
                                    Integer.toString(snapshot.yearId())));
                    status.setText(text("ready"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("interrupted"));
                } catch (ExecutionException error) {
                    // Keep the last confirmed snapshot, but require a successful reload before
                    // journal actions.
                    status.setText(
                            MessageFormat.format(text("failed"), error.getCause().getMessage()));
                }
                controls();
            }
        }.execute();
    }

    private void journal(SupplierJournalAdapter.Kind kind) {
        requireEdt();
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || yearFrom == null
                || yearTo == null) {
            return;
        }
        var selected = session;
        try {
            var dialog =
                    new SupplierJournalDialog(
                            owner,
                            selected.journal(kind),
                            yearFrom,
                            yearTo,
                            saved -> {
                                if (!closed
                                        && session == selected
                                        && selected.isActive()
                                        && !selected.isBlocked()
                                        && saved.companyId() == selected.companyId()
                                        && saved.yearId() == selected.yearId()) {
                                    refresh();
                                }
                            });
            dialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            dialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void controls() {
        boolean active = !closed && session != null && session.isActive() && !session.isBlocked();
        refresh.setEnabled(active && !loading);
        invoiceJournal.setEnabled(
                active && !loading && loaded && yearFrom != null && yearTo != null);
        creditJournal.setEnabled(invoiceJournal.isEnabled());
        if (!closed && session != null && session.isBlocked()) {
            status.setText(text("uncertain"));
        }
        SupplierDocumentMenuBinding.refreshControls(owner);
    }

    /**
     * Invalidates this view, its owner binding and future leases; never shuts down the application
     * database.
     */
    @Override
    public void close() {
        requireEdt();
        if (closed) {
            return;
        }
        closed = true;
        revision++;
        for (var dialog : List.copyOf(dialogs)) {
            dialog.invalidateSelection();
        }
        session.close();
        SupplierDocumentMenuBinding.closeIfInstalled(owner, session);
        owner.removeWindowListener(ownerClosed);
        setTables(List.of(), List.of());
        loaded = false;
        yearFrom = null;
        yearTo = null;
        status.setText(text("closed"));
        controls();
    }

    private void setTables(
            List<SupplierDocumentGuiSession.Document> invoiceRows,
            List<SupplierDocumentGuiSession.Document> creditRows) {
        invoices.values = List.copyOf(invoiceRows);
        credits.values = List.copyOf(creditRows);
        invoices.fireTableDataChanged();
        credits.fireTableDataChanged();
    }

    private static Long selection(JTable table, Rows rows) {
        int selected = table.getSelectedRow();
        if (selected < 0 || selected >= table.getRowCount()) {
            return null;
        }
        return rows.values.get(table.convertRowIndexToModel(selected)).id();
    }

    private static void restore(JTable table, Rows rows, Long id) {
        table.clearSelection();
        if (id == null) {
            return;
        }
        for (int i = 0; i < rows.values.size(); i++) {
            if (rows.values.get(i).id() == id) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) {
                    table.setRowSelectionInterval(view, view);
                }
                return;
            }
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier view requires the EDT");
        }
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("supplier.documents." + key);
    }

    private static final class Rows extends AbstractTableModel {
        private final boolean credit;
        private List<SupplierDocumentGuiSession.Document> values = List.of();
        private final String[] columns = {
            "id",
            "number",
            "date",
            "supplier",
            "currency",
            "total",
            "balance",
            "entered",
            "bankexported"
        };

        Rows(boolean credit) {
            this.credit = credit;
        }

        @Override
        public int getRowCount() {
            return values.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return text(credit && column == 6 ? "crediting" : columns[column]);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Long.class;
                case 1 -> Integer.class;
                case 2 -> LocalDate.class;
                case 5 -> java.math.BigDecimal.class;
                case 6 -> credit ? Integer.class : java.math.BigDecimal.class;
                case 7, 8 -> Boolean.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            var value = values.get(row);
            return switch (column) {
                case 0 -> value.id();
                case 1 -> value.number();
                case 2 -> value.date();
                case 3 -> value.supplier();
                case 4 -> value.currency();
                case 5 -> value.total();
                case 6 -> credit ? value.creditingNumber() : value.balance();
                case 7 -> value.entered();
                case 8 -> value.bankExported();
                default -> throw new IndexOutOfBoundsException(column);
            };
        }
    }
}
