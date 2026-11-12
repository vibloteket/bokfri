package org.fribok.bookkeeping.gui.creditinvoice;

import se.swedsoft.bookkeeping.gui.util.SSBundle;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Read-only normalized invoice/credit tables with an explicit owner and selection. This view never
 * constructs legacy frames/models or refreshes SSDB caches. Production startup remains separate.
 * All UI/lifecycle methods require the EDT; each load uses the session's dedicated gated transaction.
 */
public final class CustomerDocumentView extends JPanel implements AutoCloseable {
    private final JFrame owner;
    private CustomerDocumentGuiSession session;
    private final Rows invoices = new Rows(false), credits = new Rows(true);
    private final JTable invoiceTable = new JTable(invoices), creditTable = new JTable(credits);
    private final JLabel context = new JLabel(), status = new JLabel();
    private final JButton refresh = new JButton(text("refresh")), credit = new JButton(text("credit"));
    private long revision;
    private boolean closed;

    public CustomerDocumentView(JFrame owner, CustomerDocumentGuiSession session) {
        requireEdt(); this.owner = Objects.requireNonNull(owner, "owner");
        setLayout(new BorderLayout(8, 8));
        invoiceTable.setName("normalized-invoices"); creditTable.setName("normalized-credits");
        invoiceTable.setAutoCreateRowSorter(true); creditTable.setAutoCreateRowSorter(true);
        context.setName("document-context"); status.setName("document-status");
        refresh.setName("document-refresh"); credit.setName("document-credit");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(text("invoices"), new JScrollPane(invoiceTable));
        tabs.addTab(text("credits"), new JScrollPane(creditTable));
        JPanel actions = new JPanel(); actions.add(refresh); actions.add(credit);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(status); bottom.add(actions, BorderLayout.EAST);
        add(context, BorderLayout.NORTH); add(tabs); add(bottom, BorderLayout.SOUTH);
        refresh.addActionListener(event -> refresh());
        credit.addActionListener(event -> CustomerCreditMenuBinding.openIfInstalled(owner));
        replaceSelection(session);
    }

    /** Clears old-company rows immediately; a superseded worker can never repopulate them. */
    public void replaceSelection(CustomerDocumentGuiSession selected) {
        requireEdt(); if (closed) throw new IllegalStateException("Document view is closed");
        Objects.requireNonNull(selected, "session");
        CustomerCreditMenuBinding.install(owner, selected, this::creditSaved);
        session = selected; revision++;
        setTables(List.of(), List.of());
        context.setText(text("loading")); refresh();
    }

    /** Accept only confirmed commits for this company/year; source row indices are never used. */
    public void creditSaved(CustomerCreditMenuBinding.Saved saved) {
        requireEdt(); Objects.requireNonNull(saved, "saved");
        if (!closed && session.isActive() && saved.companyId() == session.companyId()
                && saved.yearId() == session.yearId()) refresh();
    }

    /** Explicit reload; both tables are replaced from the same successful snapshot on the EDT. */
    public void refresh() {
        requireEdt();
        if (closed || !session.isActive()) return;
        long request = ++revision;
        CustomerDocumentGuiSession selected = session;
        Long invoiceId = selection(invoiceTable, invoices), creditId = selection(creditTable, credits);
        status.setText(text("loading")); refresh.setEnabled(false);
        new SwingWorker<CustomerDocumentGuiSession.Documents, Void>() {
            @Override protected CustomerDocumentGuiSession.Documents doInBackground() throws Exception {
                return selected.documents();
            }
            @Override protected void done() {
                if (closed || request != revision || session != selected || !selected.isActive()) return;
                refresh.setEnabled(true);
                try {
                    var snapshot = get();
                    setTables(snapshot.invoices(), snapshot.credits());
                    restore(invoiceTable, invoices, invoiceId); restore(creditTable, credits, creditId);
                    context.setText(MessageFormat.format(text("context"), snapshot.companyName(),
                            Integer.toString(snapshot.companyId()), String.valueOf(snapshot.from()),
                            String.valueOf(snapshot.to()), Integer.toString(snapshot.yearId())));
                    status.setText(text("ready"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); status.setText(text("interrupted"));
                } catch (ExecutionException failure) {
                    // Retain the last confirmed table snapshot; never represent load failure as empty data.
                    status.setText(MessageFormat.format(text("failed"), failure.getCause().getMessage()));
                }
            }
        }.execute();
    }

    @Override public void close() {
        requireEdt(); if (closed) return;
        closed = true; revision++;
        CustomerCreditMenuBinding.closeIfInstalled(owner, session);
        setTables(List.of(), List.of()); refresh.setEnabled(false); credit.setEnabled(false);
        status.setText(text("closed"));
    }

    private void setTables(List<CustomerDocumentGuiSession.Document> invoiceRows,
                           List<CustomerDocumentGuiSession.Document> creditRows) {
        invoices.values = List.copyOf(invoiceRows); credits.values = List.copyOf(creditRows);
        invoices.fireTableDataChanged(); credits.fireTableDataChanged();
    }

    private static Long selection(JTable table, Rows rows) {
        int row = table.getSelectedRow();
        return row < 0 ? null : rows.values.get(table.convertRowIndexToModel(row)).id();
    }
    private static void restore(JTable table, Rows rows, Long id) {
        table.clearSelection(); if (id == null) return;
        for (int i = 0; i < rows.values.size(); i++) if (rows.values.get(i).id() == id) {
            int view = table.convertRowIndexToView(i);
            if (view >= 0) table.setRowSelectionInterval(view, view);
            return;
        }
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Document view requires the EDT");
    }
    private static String text(String key) { return SSBundle.getBundle().getString("customer.documents." + key); }

    private static final class Rows extends AbstractTableModel {
        private final boolean credit;
        private List<CustomerDocumentGuiSession.Document> values = List.of();
        private final String[] columns = {"id", "number", "date", "customer", "currency", "total", "balance", "entered"};
        Rows(boolean credit) { this.credit = credit; }
        public int getRowCount() { return values.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return text(credit && column == 6 ? "crediting" : columns[column]); }
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Long.class;
                case 1 -> Integer.class;
                case 2 -> java.time.LocalDate.class;
                case 5 -> java.math.BigDecimal.class;
                case 6 -> credit ? Integer.class : java.math.BigDecimal.class;
                case 7 -> Boolean.class;
                default -> String.class;
            };
        }
        public Object getValueAt(int row, int column) {
            var value = values.get(row);
            return switch (column) {
                case 0 -> value.id(); case 1 -> value.number(); case 2 -> value.date();
                case 3 -> value.customer(); case 4 -> value.currency(); case 5 -> value.total();
                case 6 -> credit ? value.creditingNumber() : value.balance(); case 7 -> value.entered();
                default -> throw new IndexOutOfBoundsException(column);
            };
        }
    }
}
