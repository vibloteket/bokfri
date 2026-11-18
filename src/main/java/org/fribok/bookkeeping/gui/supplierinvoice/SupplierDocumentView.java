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
    private final JButton invoiceDelete = new JButton(text("deleteinvoice"));
    private final JButton creditDelete = new JButton(text("deletecredit"));
    private final JButton invoiceMetadata = new JButton(text("metadatainvoice"));
    private final JButton creditMetadata = new JButton(text("metadatacredit"));
    private final JButton invoiceAmounts = new JButton(text("amountsinvoice"));
    private final JButton creditAmounts = new JButton(text("amountscredit"));
    private final JButton invoiceDates =
            new JButton(SSBundle.getBundle().getString("supplier.dates.actioninvoice"));
    private final JButton creditDates =
            new JButton(SSBundle.getBundle().getString("supplier.dates.actioncredit"));
    private final List<SupplierDatesDialog> dateDialogs = new ArrayList<>();
    private final JButton invoiceTaxRounding =
            new JButton(SSBundle.getBundle().getString("supplier.taxrounding.actioninvoice"));
    private final JButton creditTaxRounding =
            new JButton(SSBundle.getBundle().getString("supplier.taxrounding.actioncredit"));
    private final List<SupplierTaxRoundingDialog> taxRoundingDialogs = new ArrayList<>();
    private final List<SupplierExchangeRateDialog> exchangeRateDialogs = new ArrayList<>();
    private final List<SupplierCurrencyDialog> currencyDialogs = new ArrayList<>();
    private final JButton invoiceCurrency =
            new JButton(SSBundle.getBundle().getString("supplier.currency.actioninvoice"));
    private final JButton creditCurrency =
            new JButton(SSBundle.getBundle().getString("supplier.currency.actioncredit"));
    private final JButton invoiceExchangeRate =
            new JButton(SSBundle.getBundle().getString("supplier.exchange.actioninvoice"));
    private final JButton creditExchangeRate =
            new JButton(SSBundle.getBundle().getString("supplier.exchange.actioncredit"));
    private final List<SupplierRowAmountsDialog> amountDialogs = new ArrayList<>();
    private final List<SupplierRowDescriptionDialog> rowDescriptionDialogs = new ArrayList<>();
    private final List<SupplierRowAccountingDialog> rowAccountingDialogs = new ArrayList<>();
    private final JButton invoiceRowAccounting =
            new JButton(SSBundle.getBundle().getString("supplier.rowaccounting.actioninvoice"));
    private final JButton creditRowAccounting =
            new JButton(SSBundle.getBundle().getString("supplier.rowaccounting.actioncredit"));
    private final JButton invoiceRowDescriptions =
            new JButton(SSBundle.getBundle().getString("supplier.rowdescription.actioninvoice"));
    private final JButton creditRowDescriptions =
            new JButton(SSBundle.getBundle().getString("supplier.rowdescription.actioncredit"));
    private final List<SupplierDocumentMetadataDialog> metadataDialogs = new ArrayList<>();
    private final List<SupplierDocumentDeletionDialog> deletions = new ArrayList<>();
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
        JPanel actions = new JPanel(new java.awt.GridLayout(0, 4, 8, 4));
        actions.add(refresh);
        actions.add(invoiceJournal);
        actions.add(creditJournal);
        invoiceDelete.setName("supplier-invoice-delete");
        creditDelete.setName("supplier-credit-delete");
        actions.add(invoiceDelete);
        actions.add(creditDelete);
        invoiceMetadata.setName("supplier-invoice-metadata");
        creditMetadata.setName("supplier-credit-metadata");
        actions.add(invoiceMetadata);
        actions.add(creditMetadata);
        invoiceAmounts.setName("supplier-invoice-amounts");
        creditAmounts.setName("supplier-credit-amounts");
        actions.add(invoiceAmounts);
        actions.add(creditAmounts);
        invoiceRowDescriptions.setName("supplier-invoice-row-descriptions");
        creditRowDescriptions.setName("supplier-credit-row-descriptions");
        actions.add(invoiceRowDescriptions);
        actions.add(creditRowDescriptions);
        invoiceRowAccounting.setName("supplier-invoice-row-accounting");
        creditRowAccounting.setName("supplier-credit-row-accounting");
        actions.add(invoiceRowAccounting);
        actions.add(creditRowAccounting);
        invoiceDates.setName("supplier-invoice-dates");
        creditDates.setName("supplier-credit-dates");
        actions.add(invoiceDates);
        actions.add(creditDates);
        invoiceTaxRounding.setName("supplier-invoice-tax-rounding");
        creditTaxRounding.setName("supplier-credit-tax-rounding");
        actions.add(invoiceTaxRounding);
        actions.add(creditTaxRounding);
        invoiceExchangeRate.setName("supplier-invoice-exchange-rate");
        creditExchangeRate.setName("supplier-credit-exchange-rate");
        actions.add(invoiceExchangeRate);
        actions.add(creditExchangeRate);
        invoiceCurrency.setName("supplier-invoice-currency");
        creditCurrency.setName("supplier-credit-currency");
        actions.add(invoiceCurrency);
        actions.add(creditCurrency);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status);
        bottom.add(actions, BorderLayout.SOUTH);
        add(context, BorderLayout.NORTH);
        add(tabs);
        add(bottom, BorderLayout.SOUTH);
        refresh.addActionListener(event -> refresh());
        invoiceJournal.addActionListener(event -> journal(SupplierJournalAdapter.Kind.INVOICE));
        creditJournal.addActionListener(event -> journal(SupplierJournalAdapter.Kind.CREDIT));
        invoiceAmounts.addActionListener(event -> rowAmounts(false));
        creditAmounts.addActionListener(event -> rowAmounts(true));
        invoiceRowDescriptions.addActionListener(event -> rowDescriptions(false));
        creditRowDescriptions.addActionListener(event -> rowDescriptions(true));
        invoiceRowAccounting.addActionListener(event -> rowAccounting(false));
        creditRowAccounting.addActionListener(event -> rowAccounting(true));
        invoiceDates.addActionListener(event -> dates(false));
        creditDates.addActionListener(event -> dates(true));
        invoiceTaxRounding.addActionListener(event -> taxRounding(false));
        creditTaxRounding.addActionListener(event -> taxRounding(true));
        invoiceExchangeRate.addActionListener(event -> exchangeRate(false));
        creditExchangeRate.addActionListener(event -> exchangeRate(true));
        invoiceCurrency.addActionListener(event -> currency(false));
        creditCurrency.addActionListener(event -> currency(true));
        invoiceMetadata.addActionListener(event -> metadata(false));
        creditMetadata.addActionListener(event -> metadata(true));
        invoiceDelete.addActionListener(event -> deletion(false));
        creditDelete.addActionListener(event -> deletion(true));
        invoiceTable.getSelectionModel().addListSelectionListener(event -> controls());
        creditTable.getSelectionModel().addListSelectionListener(event -> controls());
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
        for (var dialog : List.copyOf(deletions)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(metadataDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(amountDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(rowDescriptionDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(rowAccountingDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(dateDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(taxRoundingDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(exchangeRateDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(currencyDialogs)) {
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

    private void deletion(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentDeletionService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentDeletionService.Kind.INVOICE;
            var dialog =
                    new SupplierDocumentDeletionDialog(
                            owner,
                            selected.deletion(kind, id),
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
            deletions.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            deletions.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void metadata(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.INVOICE;
            var dialog =
                    new SupplierDocumentMetadataDialog(
                            owner,
                            selected.metadata(kind, id),
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
            metadataDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            metadataDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void rowAmounts(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierRowAmountsService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierRowAmountsService.Kind.INVOICE;
            var dialog =
                    new SupplierRowAmountsDialog(
                            owner,
                            selected.rowAmounts(kind, id),
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
            amountDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            amountDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void rowDescriptions(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.INVOICE;
            var dialog =
                    new SupplierRowDescriptionDialog(
                            owner,
                            selected.rowDescriptions(kind, id),
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
            rowDescriptionDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            rowDescriptionDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void rowAccounting(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDocumentMetadataService.Kind.INVOICE;
            var dialog =
                    new SupplierRowAccountingDialog(
                            owner,
                            selected.rowAccounting(kind, id),
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
            rowAccountingDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            rowAccountingDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void dates(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDatesService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierDatesService.Kind.INVOICE;
            var dialog =
                    new SupplierDatesDialog(
                            owner,
                            selected.dates(kind, id),
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
            dateDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            dateDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void taxRounding(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierTaxRoundingService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierTaxRoundingService.Kind.INVOICE;
            var dialog =
                    new SupplierTaxRoundingDialog(
                            owner,
                            selected.taxRounding(kind, id),
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
            taxRoundingDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            taxRoundingDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void exchangeRate(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierExchangeRateService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierExchangeRateService.Kind.INVOICE;
            var dialog =
                    new SupplierExchangeRateDialog(
                            owner,
                            selected.exchangeRate(kind, id),
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
            exchangeRateDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            exchangeRateDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private void currency(boolean credit) {
        requireEdt();
        JTable table = credit ? creditTable : invoiceTable;
        Rows rows = credit ? credits : invoices;
        if (closed
                || loading
                || !loaded
                || !session.isActive()
                || session.isBlocked()
                || !unbookedUnexportedSelection(table, rows)) {
            return;
        }
        Long id = selection(table, rows);
        var selected = session;
        try {
            var kind =
                    credit
                            ? org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierExchangeRateService.Kind.CREDIT
                            : org.fribok.bookkeeping.service.supplierinvoice
                                    .NormalizedSupplierExchangeRateService.Kind.INVOICE;
            var dialog =
                    new SupplierCurrencyDialog(
                            owner,
                            selected.currency(kind, id),
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
            currencyDialogs.add(dialog);
            dialog.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            currencyDialogs.remove(dialog);
                            controls();
                        }
                    });
            dialog.setVisible(true);
        } catch (java.sql.SQLException failure) {
            status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            controls();
        }
    }

    private static boolean unbookedUnexportedSelection(JTable table, Rows rows) {
        int selected = table.getSelectedRow();
        if (selected < 0 || selected >= table.getRowCount()) {
            return false;
        }
        int model = table.convertRowIndexToModel(selected);
        if (model < 0 || model >= rows.values.size()) {
            return false;
        }
        var document = rows.values.get(model);
        return !document.entered() && !document.bankExported();
    }

    private void controls() {
        boolean active = !closed && session != null && session.isActive() && !session.isBlocked();
        refresh.setEnabled(active && !loading);
        invoiceJournal.setEnabled(
                active && !loading && loaded && yearFrom != null && yearTo != null);
        creditJournal.setEnabled(invoiceJournal.isEnabled());
        invoiceDelete.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditDelete.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceMetadata.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditMetadata.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceAmounts.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditAmounts.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceRowDescriptions.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditRowDescriptions.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceRowAccounting.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditRowAccounting.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceDates.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditDates.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceTaxRounding.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditTaxRounding.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceExchangeRate.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditExchangeRate.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
        invoiceCurrency.setEnabled(
                active
                        && !loading
                        && loaded
                        && unbookedUnexportedSelection(invoiceTable, invoices));
        creditCurrency.setEnabled(
                active && !loading && loaded && unbookedUnexportedSelection(creditTable, credits));
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
        for (var dialog : List.copyOf(deletions)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(metadataDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(amountDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(rowDescriptionDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(rowAccountingDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(dateDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(taxRoundingDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(exchangeRateDialogs)) {
            dialog.invalidateSelection();
        }
        for (var dialog : List.copyOf(currencyDialogs)) {
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
