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
    private final JButton invoiceMetadata = new JButton(text("invoicemetadata")), creditMetadata = new JButton(text("creditmetadata"));
    private final java.util.List<CustomerDocumentMetadataDialog> metadataDialogs = new java.util.ArrayList<>();
    private final JButton invoiceDelete = new JButton(text("invoicedelete")), creditDelete = new JButton(text("creditdelete"));
    private final java.util.List<CustomerDocumentDeletionDialog> deletionDialogs = new java.util.ArrayList<>();
    private final JButton invoiceJournal = new JButton(text("invoicejournal")), creditJournal = new JButton(text("creditjournal"));
    private final java.util.List<CustomerJournalDialog> journalDialogs = new java.util.ArrayList<>();
    private java.time.LocalDate yearFrom, yearTo;
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
        invoiceMetadata.setName("invoice-metadata"); creditMetadata.setName("credit-metadata");
        invoiceDelete.setName("invoice-delete"); creditDelete.setName("credit-delete");
        JPanel actions = new JPanel(new java.awt.GridLayout(0, 3, 4, 4));
        actions.add(refresh); actions.add(credit); actions.add(invoiceMetadata); actions.add(creditMetadata);
        actions.add(invoiceDelete); actions.add(creditDelete);
        invoiceJournal.setName("invoice-journal"); creditJournal.setName("credit-journal");
        actions.add(invoiceJournal); actions.add(creditJournal);
        invoiceJournal.addActionListener(event -> journal(CustomerJournalAdapter.Kind.INVOICE));
        creditJournal.addActionListener(event -> journal(CustomerJournalAdapter.Kind.CREDIT));
        invoiceDelete.addActionListener(event -> deletion(false)); creditDelete.addActionListener(event -> deletion(true));
        invoiceTable.getSelectionModel().addListSelectionListener(event -> selectionEnablement());
        creditTable.getSelectionModel().addListSelectionListener(event -> selectionEnablement());
        invoiceMetadata.addActionListener(event -> metadata(false)); creditMetadata.addActionListener(event -> metadata(true));
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
        for (var dialog : List.copyOf(metadataDialogs)) dialog.invalidateSelection();
        for (var dialog : List.copyOf(deletionDialogs)) dialog.invalidateSelection();
        for (var dialog : List.copyOf(journalDialogs)) dialog.invalidateSelection();
        session = selected; revision++; yearFrom = null; yearTo = null;
        setTables(List.of(), List.of()); selectionEnablement();
        context.setText(text("loading")); refresh();
    }

    /** Accept only confirmed commits for this company/year; source row indices are never used. */
    public void creditSaved(CustomerCreditMenuBinding.Saved saved) {
        requireEdt(); Objects.requireNonNull(saved, "saved");
        if (!closed && session.isActive() && saved.companyId() == session.companyId()
                && saved.yearId() == session.yearId()) refresh();
    }

    private void selectionEnablement() {
        boolean active = !closed && session != null && session.isActive();
        invoiceMetadata.setEnabled(active && invoiceTable.getSelectedRow() >= 0);
        creditMetadata.setEnabled(active && creditTable.getSelectedRow() >= 0);
        invoiceDelete.setEnabled(active && invoiceTable.getSelectedRow() >= 0);
        creditDelete.setEnabled(active && creditTable.getSelectedRow() >= 0);
        invoiceJournal.setEnabled(active && yearFrom != null && yearTo != null);
        creditJournal.setEnabled(active && yearFrom != null && yearTo != null);
    }

    private void metadata(boolean isCredit) {
        requireEdt(); if (closed || !session.isActive()) return;
        Long id = selection(isCredit ? creditTable : invoiceTable, isCredit ? credits : invoices);
        if (id == null) return;
        CustomerDocumentGuiSession selected = session;
        try {
            var kind = isCredit ? CustomerDocumentMetadataAdapter.Kind.CREDIT : CustomerDocumentMetadataAdapter.Kind.INVOICE;
            var dialog = new CustomerDocumentMetadataDialog(owner, selected.metadata(kind, id), saved -> {
                if (!closed && session == selected && selected.isActive() && saved.companyId() == selected.companyId()
                        && saved.yearId() == selected.yearId()) refresh();
            });
            metadataDialogs.add(dialog);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { metadataDialogs.remove(dialog); }
            });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
        }
    }

    private void deletion(boolean isCredit) {
        requireEdt(); if (closed || !session.isActive()) return;
        Long id = selection(isCredit ? creditTable : invoiceTable, isCredit ? credits : invoices);
        if (id == null) return;
        CustomerDocumentGuiSession selected = session;
        try {
            var kind = isCredit ? org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind.CREDIT
                    : org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind.INVOICE;
            var dialog = new CustomerDocumentDeletionDialog(owner, selected.deletion(kind, id), saved -> {
                if (!closed && session == selected && selected.isActive() && saved.companyId() == selected.companyId()
                        && saved.yearId() == selected.yearId()) refresh();
            });
            deletionDialogs.add(dialog);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { deletionDialogs.remove(dialog); }
            });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
        }
    }

    private void journal(CustomerJournalAdapter.Kind kind) {
        requireEdt(); if (closed || !session.isActive() || yearFrom == null || yearTo == null) return;
        CustomerDocumentGuiSession selected = session;
        try {
            var dialog = new CustomerJournalDialog(owner, selected.journal(kind), yearFrom, yearTo, saved -> {
                if (!closed && session == selected && selected.isActive() && saved.companyId() == selected.companyId()
                        && saved.yearId() == selected.yearId()) refresh();
            });
            journalDialogs.add(dialog);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { journalDialogs.remove(dialog); }
            });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
        }
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
                    yearFrom = snapshot.from(); yearTo = snapshot.to();
                    restore(invoiceTable, invoices, invoiceId); restore(creditTable, credits, creditId); selectionEnablement();
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
        for (var dialog : List.copyOf(metadataDialogs)) dialog.invalidateSelection();
        for (var dialog : List.copyOf(deletionDialogs)) dialog.invalidateSelection();
        for (var dialog : List.copyOf(journalDialogs)) dialog.invalidateSelection();
        CustomerCreditMenuBinding.closeIfInstalled(owner, session);
        setTables(List.of(), List.of()); refresh.setEnabled(false); credit.setEnabled(false); selectionEnablement();
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
