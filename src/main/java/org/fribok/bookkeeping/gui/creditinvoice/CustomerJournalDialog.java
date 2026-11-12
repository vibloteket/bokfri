package org.fribok.bookkeeping.gui.creditinvoice;

import se.swedsoft.bookkeeping.gui.util.SSBundle;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Explicit whole-journal preview/confirmation; never posts automatically or edits financial rows. */
public final class CustomerJournalDialog extends JDialog {
    private final CustomerJournalAdapter adapter;
    private final Consumer<CustomerJournalAdapter.Saved> committed;
    private final JTextField from = new JTextField(10), to = new JTextField(10);
    private final JButton previewButton = new JButton(text("preview")), post = new JButton(text("post")), cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private final JTextArea status = new JTextArea(4, 72);
    private final Values documents = new Values(new String[]{"id", "number", "date", "customer"},
            new Class<?>[]{Long.class, Integer.class, LocalDate.class, String.class});
    private final Values rows = new Values(new String[]{"account", "project", "resultunit", "debit", "credit"},
            new Class<?>[]{Integer.class, String.class, String.class, java.math.BigDecimal.class, java.math.BigDecimal.class});
    private CustomerJournalAdapter.Preview preview;
    private long revision;
    private boolean busy, invalid;
    public CustomerJournalDialog(JFrame owner, CustomerJournalAdapter adapter, LocalDate initialFrom, LocalDate initialTo,
                                 Consumer<CustomerJournalAdapter.Saved> committed) {
        super(owner, text("title"), true); requireEdt(); this.adapter = adapter; this.committed = committed;
        var target = adapter.target();
        setTitle(MessageFormat.format(text("target"), text(target.kind() == CustomerJournalAdapter.Kind.INVOICE ? "invoices" : "credits"),
                Integer.toString(target.companyId()), Integer.toString(target.yearId())));
        setName("customer-journal"); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        from.setText(initialFrom.toString()); to.setText(initialTo.toString()); from.setName("journal-from"); to.setName("journal-to");
        previewButton.setName("journal-preview"); post.setName("journal-post"); cancel.setName("journal-cancel"); confirm.setName("journal-confirm");
        status.setName("journal-status"); status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true); status.setText(text("ready"));
        JPanel range = new JPanel(); range.add(new JLabel(text("from"))); range.add(from); range.add(new JLabel(text("to"))); range.add(to); range.add(previewButton);
        JTable documentTable = new JTable(documents), rowTable = new JTable(rows);
        documentTable.setName("journal-documents"); rowTable.setName("journal-rows");
        documentTable.setAutoCreateRowSorter(true); rowTable.setAutoCreateRowSorter(true);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab(text("documents"), new JScrollPane(documentTable)); tabs.addTab(text("voucher"), new JScrollPane(rowTable));
        JPanel buttons = new JPanel(); buttons.add(confirm); buttons.add(post); buttons.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(status); bottom.add(buttons, BorderLayout.SOUTH);
        add(range, BorderLayout.NORTH); add(tabs); add(bottom, BorderLayout.SOUTH);
        DocumentListener changed = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() { invalidatePreview(); if (!busy) status.setText(text("ready")); controls(); }
        };
        from.getDocument().addDocumentListener(changed); to.getDocument().addDocumentListener(changed);
        previewButton.addActionListener(event -> load()); post.addActionListener(event -> post()); confirm.addActionListener(event -> controls());
        cancel.addActionListener(event -> { if (!busy) dispose(); });
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { if (!busy) dispose(); } });
        getRootPane().registerKeyboardAction(event -> { if (!busy) dispose(); }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        controls(); pack(); setLocationRelativeTo(owner);
    }
    public void invalidateSelection() { requireEdt(); invalid = true; revision++; controls(); if (!busy) dispose(); }
    private void invalidatePreview() {
        preview = null; revision++; confirm.setSelected(false); documents.set(List.of()); rows.set(List.of());
    }
    private void load() {
        if (busy || invalid || adapter.isBlocked()) return;
        invalidatePreview(); LocalDate start, end;
        try { start = LocalDate.parse(from.getText()); end = LocalDate.parse(to.getText()); if (end.isBefore(start)) throw new IllegalArgumentException(); }
        catch (java.time.format.DateTimeParseException | IllegalArgumentException error) { status.setText(text("invalid")); controls(); return; }
        long request = revision; busy = true; status.setText(text("loading")); controls();
        new SwingWorker<CustomerJournalAdapter.Preview, Void>() {
            protected CustomerJournalAdapter.Preview doInBackground() throws Exception { return adapter.preview(start, end); }
            protected void done() {
                busy = false; if (invalid) { dispose(); return; }
                if (request != revision) { status.setText(text("ready")); controls(); return; }
                try {
                    preview = get(); var s = preview.summary();
                    documents.set(s.documents().stream().map(d -> new Object[]{d.id(), d.number(), d.date(), d.customer()}).toList());
                    rows.set(s.rows().stream().map(r -> new Object[]{r.account(), r.project(), r.resultUnit(), r.debit(), r.credit()}).toList());
                    status.setText(MessageFormat.format(text("summary"), Integer.toString(s.journalNumber()), Integer.toString(s.voucherNumber()),
                            String.valueOf(s.voucherDate()), Integer.toString(s.documents().size()), s.debit().toPlainString(), s.credit().toPlainString()));
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException error) { status.setText(text("failed") + " " + error.getCause().getMessage()); }
                controls();
            }
        }.execute();
    }
    private void post() {
        if (busy || invalid || preview == null || !confirm.isSelected() || adapter.isBlocked()) return;
        var accepted = preview; busy = true; status.setText(text("posting")); controls();
        new SwingWorker<CustomerJournalAdapter.Saved, Void>() {
            protected CustomerJournalAdapter.Saved doInBackground() throws Exception { return adapter.post(accepted); }
            protected void done() {
                busy = false;
                try {
                    var result = get();
                    if (!invalid) {
                        try { committed.accept(result); }
                        catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(CustomerJournalDialog.class)
                                .error("Journal committed but refresh notification failed; do not repost", error); }
                    }
                    dispose(); return;
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); status.setText(text("failed")); }
                catch (ExecutionException error) { status.setText(adapter.isBlocked() ? text("uncertain") : text("failed") + " " + error.getCause().getMessage()); }
                invalidatePreview(); if (invalid) dispose(); else controls();
            }
        }.execute();
    }
    private void controls() {
        boolean active = !busy && !invalid && !adapter.isBlocked();
        from.setEnabled(active); to.setEnabled(active); previewButton.setEnabled(active);
        confirm.setEnabled(active && preview != null); post.setEnabled(active && preview != null && confirm.isSelected()); cancel.setEnabled(!busy);
    }
    private static final class Values extends AbstractTableModel {
        private final String[] columns; private final Class<?>[] types; private List<Object[]> values = List.of();
        Values(String[] columns, Class<?>[] types) { this.columns = columns; this.types = types; }
        void set(List<Object[]> values) { this.values = List.copyOf(values); fireTableDataChanged(); }
        public int getRowCount() { return values.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return text(columns[column]); }
        public Class<?> getColumnClass(int column) { return types[column]; }
        public Object getValueAt(int row, int column) { return values.get(row)[column]; }
    }
    private static String text(String key) { return SSBundle.getBundle().getString("customer.journal." + key); }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Journal dialog requires the EDT"); }
}
