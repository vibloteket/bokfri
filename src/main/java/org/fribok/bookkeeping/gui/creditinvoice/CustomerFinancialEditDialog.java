package org.fribok.bookkeeping.gui.creditinvoice;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.List;

import javax.swing.DefaultCellEditor;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineDetails;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAccounting;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.TaxValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ExchangeRateValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineDimensions;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ManualLine;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

/** Bounded draft editor. Blank dates/amounts mean absent values, not invented defaults or zero. */
public final class CustomerFinancialEditDialog extends JDialog {
    private final CustomerFinancialEditAdapter adapter;
    private final Consumer<CustomerFinancialEditAdapter.Saved> committed;
    private CustomerFinancialEditAdapter.Loaded loaded;
    private CustomerFinancialEditAdapter.Preview preview;
    private final JTextField date = new JTextField(10), due = new JTextField(10);
    private final JTextArea status = new JTextArea(4, 70);
    private final JTextArea accountingContext = new JTextArea(2, 70);
    private final JComboBox<Boolean> taxFree = new JComboBox<>(new Boolean[] {Boolean.FALSE, Boolean.TRUE});
    private final JTextField[] taxRates = {new JTextField(6), new JTextField(6), new JTextField(6)};
    private final JTextField exchangeRate = new JTextField(10);
    private final JLabel exchangeContext = new JLabel();
    private final JButton reload = new JButton(text("reload")),
            previewButton = new JButton(text("preview")),
            save = new JButton(text("save")),
            cancel = new JButton(text("cancel"));
    private final JCheckBox confirm = new JCheckBox(text("confirm"));
    private final JButton removeRow = new JButton(text("removerow"));
    private final JButton addRow = new JButton(text("addrow"));
    private final JButton addProduct = new JButton(text("addproduct"));
    private final JComboBox<NormalizedCustomerFinancialEditService.Product> products =
            new JComboBox<>();
    private final Lines lines = new Lines();
    private final JTable table = new JTable(lines);
    private boolean busy, invalid;
    private long revision;

    /**
     * Creates and starts loading an explicit draft editor on the EDT.
     *
     * @param owner nullable parent frame
     * @param adapter selection-bound workflow adapter
     * @param committed EDT notification only after a confirmed current-context save
     * @throws IllegalStateException if called outside the EDT
     */
    public CustomerFinancialEditDialog(
            JFrame owner,
            CustomerFinancialEditAdapter adapter,
            Consumer<CustomerFinancialEditAdapter.Saved> committed) {
        super(owner, text("title"), true);
        requireEdt();
        this.adapter = adapter;
        this.committed = committed;
        var target = adapter.target();
        setTitle(
                java.text.MessageFormat.format(
                        text("target"),
                        text(
                                target.kind() == NormalizedCustomerFinancialEditService.Kind.INVOICE
                                        ? "invoice"
                                        : "credit"),
                        Long.toString(target.id()),
                        Integer.toString(target.companyId()),
                        Integer.toString(target.yearId())));
        setName("customer-financial-edit");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        date.setName("financial-date");
        due.setName("financial-due");
        table.setName("financial-lines");
        status.setName("financial-status");
        reload.setName("financial-reload");
        previewButton.setName("financial-preview");
        save.setName("financial-save");
        cancel.setName("financial-cancel");
        confirm.setName("financial-confirm");
        removeRow.setName("financial-remove-row");
        addRow.setName("financial-add-row");
        JPanel header = new JPanel();
        header.add(new JLabel(text("date")));
        header.add(date);
        header.add(new JLabel(text("due")));
        header.add(due);
        JPanel actions = new JPanel();
        actions.add(reload);
        actions.add(previewButton);
        actions.add(save);
        actions.add(cancel);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JScrollPane(status));
        JPanel confirmationAndActions = new JPanel(new BorderLayout());
        confirmationAndActions.add(confirm, BorderLayout.NORTH);
        confirmationAndActions.add(actions, BorderLayout.SOUTH);
        bottom.add(confirmationAndActions, BorderLayout.SOUTH);
        accountingContext.setName("financial-accounting-context");
        accountingContext.setEditable(false);
        accountingContext.setLineWrap(true);
        accountingContext.setWrapStyleWord(true);
        JPanel taxHeader = new JPanel();
        taxHeader.setName("financial-header-tax");
        taxHeader.add(new JLabel(text("taxflag")));
        taxFree.setName("financial-taxfree");
        taxFree.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        text(Boolean.TRUE.equals(value) ? "exempt" : "taxable"),
                        index, selected, focus);
            }
        });
        taxHeader.add(taxFree);
        for (int i = 0; i < taxRates.length; i++) {
            taxRates[i].setName("financial-taxrate-" + (i + 1));
            taxHeader.add(new JLabel("TAXRATE_" + (i + 1) + " (%)"));
            taxHeader.add(taxRates[i]);
        }
        JPanel inputs = new JPanel(new BorderLayout());
        inputs.add(header, BorderLayout.NORTH);
        inputs.add(taxHeader);
        JPanel exchangeHeader = new JPanel();
        exchangeRate.setName("financial-exchange-rate");
        exchangeContext.setName("financial-exchange-context");
        exchangeHeader.add(new JLabel(text("exchangerate")));
        exchangeHeader.add(exchangeRate);
        exchangeHeader.add(exchangeContext);
        inputs.add(exchangeHeader, BorderLayout.SOUTH);
        JPanel top = new JPanel(new BorderLayout());
        top.add(inputs, BorderLayout.NORTH);
        top.add(new JScrollPane(accountingContext));
        add(top, BorderLayout.NORTH);
        JPanel rowPanel = new JPanel(new BorderLayout());
        JPanel rowActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        rowActions.add(addRow);
        products.setName("financial-products");
        products.setPreferredSize(new java.awt.Dimension(260, 28));
        products.setRenderer(
                new javax.swing.DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(
                            javax.swing.JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        return super.getListCellRendererComponent(
                                list,
                                value == null ? text("chooseproduct") : value,
                                index,
                                selected,
                                focus);
                    }
                });
        products.addActionListener(event -> controls());
        rowActions.add(products);
        addProduct.setName("financial-add-product");
        rowActions.add(addProduct);
        rowActions.add(removeRow);
        rowPanel.add(rowActions, BorderLayout.NORTH);
        rowPanel.add(new JScrollPane(table));
        add(rowPanel);
        add(bottom, BorderLayout.SOUTH);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(
                BigDecimal.class,
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    protected void setValue(Object value) {
                        setHorizontalAlignment(SwingConstants.RIGHT);
                        setText(
                                value == null
                                        ? ""
                                        : ((BigDecimal) value)
                                                .stripTrailingZeros()
                                                .toPlainString());
                    }
                });
        table.setDefaultEditor(
                BigDecimal.class,
                new DefaultCellEditor(new JTextField()) {
                    private BigDecimal parsed;

                    public boolean stopCellEditing() {
                        try {
                            String value = ((JTextField) getComponent()).getText().trim();
                            parsed =
                                    value.isEmpty()
                                            ? null
                                            : new BigDecimal(value.replace(',', '.'));
                            new LineAmounts(0, parsed, parsed);
                        } catch (IllegalArgumentException error) {
                            status.setText(text("invalid"));
                            return false;
                        }
                        return super.stopCellEditing();
                    }

                    public Object getCellEditorValue() {
                        return parsed;
                    }
                });
        var taxCodes =
                new JComboBox<String>(
                        new String[] {null, "TAXRATE_0", "TAXRATE_1", "TAXRATE_2", "TAXRATE_3"});
        taxCodes.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, taxLabel((String) value), index, selected, focus);
            }
        });
        table.getColumnModel().getColumn(6).setCellEditor(new DefaultCellEditor(taxCodes));
        table.getColumnModel().getColumn(6).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) { setText(taxLabel((String) value)); }
        });
        table.addPropertyChangeListener(
                "tableCellEditor",
                event -> {
                    if (table.isEditing()) changed();
                });
        DocumentListener edits =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        changed();
                    }
                };
        date.getDocument().addDocumentListener(edits);
        due.getDocument().addDocumentListener(edits);
        for (var rate : taxRates) rate.getDocument().addDocumentListener(edits);
        exchangeRate.getDocument().addDocumentListener(edits);
        taxFree.addActionListener(event -> changed());
        removeRow.addActionListener(
                event -> {
                    if (busy || invalid || loaded == null || adapter.isBlocked() || !finishEdit())
                        return;
                    int selected = table.getSelectedRow();
                    if (selected >= 0 && lines.canRemove(table.convertRowIndexToModel(selected))) {
                        lines.remove(table.convertRowIndexToModel(selected));
                        changed();
                    }
                });
        addRow.addActionListener(
                event -> {
                    if (busy
                            || invalid
                            || loaded == null
                            || adapter.isBlocked()
                            || !lines.canAdd()
                            || !finishEdit()) return;
                    int index = lines.add();
                    changed();
                    int view = table.convertRowIndexToView(index);
                    if (view >= 0) table.setRowSelectionInterval(view, view);
                });
        addProduct.addActionListener(
                event -> {
                    if (busy
                            || invalid
                            || loaded == null
                            || adapter.isBlocked()
                            || !lines.canAdd()
                            || !(products.getSelectedItem()
                                    instanceof
                                    NormalizedCustomerFinancialEditService.Product product)
                            || !product.selectable()
                            || !finishEdit()) return;
                    int index = lines.add(product);
                    changed();
                    int view = table.convertRowIndexToView(index);
                    if (view >= 0) table.setRowSelectionInterval(view, view);
                });
        table.getSelectionModel().addListSelectionListener(event -> controls());
        reload.addActionListener(event -> load());
        previewButton.addActionListener(event -> preview());
        save.addActionListener(event -> save());
        confirm.addActionListener(event -> controls());
        cancel.addActionListener(
                event -> {
                    if (!busy) dispose();
                });
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        if (!busy) dispose();
                    }
                });
        getRootPane()
                .registerKeyboardAction(
                        event -> {
                            if (!busy) dispose();
                        },
                        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        load();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Invalidates the dialog on the EDT; accepted work may finish without a stale notification.
     *
     * @throws IllegalStateException if called outside the EDT
     */
    public void invalidateSelection() {
        requireEdt();
        invalid = true;
        revision++;
        controls();
        if (!busy) dispose();
    }

    private void changed() {
        preview = null;
        revision++;
        confirm.setSelected(false);
        if (!busy) status.setText(text("ready"));
        table.repaint();
        controls();
    }

    private boolean finishEdit() {
        return !table.isEditing() || table.getCellEditor().stopCellEditing();
    }

    private void load() {
        if (busy || invalid || adapter.isBlocked()) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        loaded = null;
        changed();
        lines.set(List.of());
        products.setModel(new javax.swing.DefaultComboBoxModel<>());
        accountingContext.setText("");
        busy = true;
        status.setText(text("loading"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Loaded, Void>() {
            protected CustomerFinancialEditAdapter.Loaded doInBackground() throws Exception {
                return adapter.load();
            }

            protected void done() {
                busy = false;
                if (invalid) {
                    dispose();
                    return;
                }
                try {
                    loaded = get();
                    var data = loaded.data();
                    date.setText(
                            data.values().date() == null ? "" : data.values().date().toString());
                    due.setText(
                            data.values().dueDate() == null
                                    ? ""
                                    : data.values().dueDate().toString());
                    exchangeRate.setText(
                            data.exchangeRate() == null ? "" : data.exchangeRate().toPlainString());
                    exchangeContext.setText(
                            unit(data.currency())
                                    + " -> "
                                    + unit(data.baseCurrency())
                                    + " / "
                                    + text("readonlycodes"));
                    var storedTax = data.headerTax();
                    taxFree.setSelectedItem(storedTax.taxFree());
                    var rates =
                            java.util.Arrays.asList(
                                    storedTax.rate1(), storedTax.rate2(), storedTax.rate3());
                    for (int i = 0; i < taxRates.length; i++)
                        taxRates[i].setText(
                                rates.get(i) == null ? "" : rates.get(i).toPlainString());
                    lines.set(data.lines());
                    var choices =
                            new javax.swing.DefaultComboBoxModel<
                                    NormalizedCustomerFinancialEditService.Product>();
                    choices.addElement(null);
                    data.products().stream()
                            .filter(NormalizedCustomerFinancialEditService.Product::selectable)
                            .forEach(choices::addElement);
                    products.setModel(choices);
                    accountingContext.setText(
                            text("accounts")
                                    + " "
                                    + data.yearId()
                                    + ": "
                                    + data.accounts().stream()
                                            .filter(
                                                    a ->
                                                            a.number() >= 3000
                                                                    && a.number() <= 3999
                                                                    && a.active())
                                            .map(
                                                    a ->
                                                            a.number()
                                                                    + " "
                                                                    + (a.description() == null
                                                                            ? ""
                                                                            : a.description())
                                                                    + (a.projectRequired()
                                                                            ? " ["
                                                                                    + text(
                                                                                            "projectrequired")
                                                                                    + "]"
                                                                            : "")
                                                                    + (a.resultUnitRequired()
                                                                            ? " ["
                                                                                    + text(
                                                                                            "unitrequired")
                                                                                    + "]"
                                                                            : ""))
                                            .collect(java.util.stream.Collectors.joining(", "))
                                    + "\n"
                                    + text("taxfree")
                                    + " "
                                    + data.taxFree()
                                    + " / "
                                    + data.taxes().stream()
                                            .map(t -> taxLabel(t.code()))
                                            .collect(java.util.stream.Collectors.joining(", "))
                                    + "\n"
                                    + text("productnote")
                                    + " / "
                                    + text("stockinfluencing")
                                    + " "
                                    + data.stockInfluencing()
                                    + "\n"
                                    + text("projects")
                                    + " "
                                    + data.projects().stream()
                                            .filter(p -> !p.concluded())
                                            .map(
                                                    p ->
                                                            p.number()
                                                                    + " "
                                                                    + (p.name() == null
                                                                            ? ""
                                                                            : p.name()))
                                            .collect(java.util.stream.Collectors.joining(", "))
                                    + " / "
                                    + text("resultunits")
                                    + " "
                                    + data.resultUnits().stream()
                                            .map(
                                                    u ->
                                                            u.number()
                                                                    + " "
                                                                    + (u.name() == null
                                                                            ? ""
                                                                            : u.name()))
                                            .collect(java.util.stream.Collectors.joining(", ")));
                    status.setText(
                            text("ready")
                                    + " "
                                    + String.valueOf(data.number())
                                    + " / "
                                    + (data.customer() == null ? "" : data.customer())
                                    + " / "
                                    + text("total")
                                    + " "
                                    + data.total().toPlainString()
                                    + " "
                                    + (data.currency() == null ? "" : data.currency()));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void preview() {
        if (busy || invalid || loaded == null || adapter.isBlocked() || !finishEdit()) return;
        changed();
        FinancialValues values;
        try {
            values =
                    new FinancialValues(
                            parse(date.getText()),
                            parse(due.getText()),
                            lines.values(),
                            lines.details(),
                            lines.accounting(),
                            new TaxValues(
                                    (Boolean) taxFree.getSelectedItem(),
                                    parseRate(0),
                                    parseRate(1),
                                    parseRate(2)),
                            new ExchangeRateValues(parseDecimal(exchangeRate.getText())),
                            lines.dimensions(),
                            lines.removedRows(),
                            lines.addedRows());
        } catch (RuntimeException error) {
            status.setText(text("invalid"));
            controls();
            return;
        }
        long request = revision;
        var source = loaded;
        busy = true;
        status.setText(text("previewing"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Preview, Void>() {
            protected CustomerFinancialEditAdapter.Preview doInBackground() throws Exception {
                return adapter.preview(source, values);
            }

            protected void done() {
                busy = false;
                if (invalid) {
                    dispose();
                    return;
                }
                if (request != revision) {
                    status.setText(text("ready"));
                    controls();
                    return;
                }
                try {
                    preview = get();
                    status.setText(
                            (preview.values().addedRows().isEmpty()
                                            ? ""
                                            : text("addedrows")
                                                    + " "
                                                    + preview.values().addedRows().stream()
                                                            .map(ManualLine::rowNumber)
                                                            .toList()
                                                    + "\n")
                                    + (preview.values().removedRows().isEmpty()
                                            ? ""
                                            : text("removedrows")
                                                    + " "
                                                    + preview.values().removedRows()
                                                    + "\n")
                                    + text("totals")
                                    + " "
                                    + preview.before().toPlainString()
                                    + " -> "
                                    + preview.after().toPlainString()
                                    + " "
                                    + unit(loaded.data().currency())
                                    + "\n"
                                    + text("equivalent")
                                    + " "
                                    + amount(preview.beforeAccounting())
                                    + " -> "
                                    + amount(preview.afterAccounting())
                                    + " "
                                    + unit(loaded.data().baseCurrency())
                                    + ". "
                                    + text("equivalentnote"));
                    String productSummary =
                            preview.values().addedRows().stream()
                                    .filter(r -> r.product() != null)
                                    .map(
                                            r ->
                                                    r.rowNumber()
                                                            + ": "
                                                            + r.product().number()
                                                            + " / "
                                                            + text("productunit")
                                                            + " "
                                                            + String.valueOf(r.product().unit()))
                                    .collect(java.util.stream.Collectors.joining("\n"));
                    if (!productSummary.isEmpty()) {
                        status.setText(
                                productSummary
                                        + "\n"
                                        + text("stockinfluencing")
                                        + " "
                                        + loaded.data().stockInfluencing()
                                        + "\n"
                                        + status.getText());
                    }
                    status.setCaretPosition(0);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(text("failed") + " " + error.getCause().getMessage());
                }
                controls();
            }
        }.execute();
    }

    private void save() {
        if (busy
                || invalid
                || !finishEdit()
                || preview == null
                || !confirm.isSelected()
                || adapter.isBlocked()) return;
        var accepted = preview;
        busy = true;
        status.setText(text("saving"));
        controls();
        new SwingWorker<CustomerFinancialEditAdapter.Saved, Void>() {
            protected CustomerFinancialEditAdapter.Saved doInBackground() throws Exception {
                return adapter.save(accepted);
            }

            protected void done() {
                busy = false;
                try {
                    var saved = get();
                    if (!invalid) {
                        try {
                            committed.accept(saved);
                        } catch (RuntimeException error) {
                            org.slf4j.LoggerFactory.getLogger(CustomerFinancialEditDialog.class)
                                    .error(
                                            "Financial edit committed but refresh notification"
                                                + " failed; do not resave",
                                            error);
                        }
                    }
                    dispose();
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("failed"));
                } catch (ExecutionException error) {
                    status.setText(
                            adapter.isBlocked()
                                    ? text("uncertain")
                                    : text("failed") + " " + error.getCause().getMessage());
                }
                preview = null;
                confirm.setSelected(false);
                if (invalid) dispose();
                else controls();
            }
        }.execute();
    }

    private void controls() {
        boolean active = !busy && !invalid && !adapter.isBlocked();
        date.setEnabled(active && loaded != null);
        due.setEnabled(active && loaded != null);
        exchangeRate.setEnabled(active && loaded != null);
        taxFree.setEnabled(active && loaded != null);
        for (var rate : taxRates) rate.setEnabled(active && loaded != null);
        table.setEnabled(active && loaded != null);
        reload.setEnabled(active);
        removeRow.setEnabled(
                active
                        && loaded != null
                        && table.getSelectedRow() >= 0
                        && lines.canRemove(table.convertRowIndexToModel(table.getSelectedRow())));
        addRow.setEnabled(active && loaded != null && lines.canAdd());
        products.setEnabled(active && loaded != null);
        addProduct.setEnabled(
                active && loaded != null && lines.canAdd() && products.getSelectedItem() != null);
        previewButton.setEnabled(active && loaded != null);
        confirm.setEnabled(active && preview != null);
        save.setEnabled(active && preview != null && confirm.isSelected());
        cancel.setEnabled(!busy);
    }

    private String taxLabel(String code) {
        if (code == null) return text("absenttax");
        try {
            var rate =
                    code.equals("TAXRATE_0")
                            ? BigDecimal.ZERO
                            : parseRate(Integer.parseInt(code.substring(8)) - 1);
            return code
                    + " / "
                    + (rate == null
                            ? text("missingrate")
                            : rate.stripTrailingZeros().toPlainString() + " %");
        } catch (IllegalArgumentException error) {
            return code + " / " + text("invalidrate");
        }
    }

    private BigDecimal parseRate(int index) { return parseDecimal(taxRates[index].getText()); }

    private static String unit(String code) {
        return code == null || code.isBlank() ? text("unknowncurrency") : code;
    }
    private static String amount(BigDecimal value) {
        return value == null
                ? text("unknownequivalent")
                : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal parseDecimal(String text) {
        String value = text.trim();
        var parsed = value.isEmpty() ? null : new BigDecimal(value.replace(',', '.'));
        new LineAmounts(0, parsed, parsed);
        return parsed;
    }

    private final class Lines extends AbstractTableModel {
        private List<NormalizedCustomerFinancialEditService.Line> original = List.of();
        private final java.util.Set<Integer> removed = new java.util.LinkedHashSet<>();
        private final java.util.Set<Integer> added = new java.util.LinkedHashSet<>();
        private long nextKey;
        private final java.util.Map<
                        Integer,
                        org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ProductLink>
                productLinks = new java.util.HashMap<>();
        private java.util.Map<Integer, LineAmounts> edited = new java.util.HashMap<>();
        private java.util.Map<Integer, LineDetails> detailEdits = new java.util.HashMap<>();
        private java.util.Map<Integer, LineAccounting> accountingEdits = new java.util.HashMap<>();
        private java.util.Map<Integer, LineDimensions> dimensionEdits = new java.util.HashMap<>();

        void set(List<NormalizedCustomerFinancialEditService.Line> values) {
            original = List.copyOf(values);
            removed.clear();
            added.clear();
            productLinks.clear();
            nextKey =
                    (long)
                                    Math.max(
                                            -1,
                                            values.stream()
                                                    .mapToInt(
                                                            NormalizedCustomerFinancialEditService
                                                                            .Line
                                                                    ::rowNumber)
                                                    .max()
                                                    .orElse(-1))
                            + 1;
            edited.clear();
            detailEdits.clear();
            accountingEdits.clear();
            dimensionEdits.clear();
            for (var row : original) dimensionEdits.put(row.rowNumber(), new LineDimensions(row.rowNumber(), row.project(), row.resultUnit()));
            for (var row : original)
                accountingEdits.put(row.rowNumber(), new LineAccounting(row.rowNumber(), row.account(), row.taxCode()));
            for (var row : original)
                detailEdits.put(row.rowNumber(), new LineDetails(row.rowNumber(), row.description(), row.discount()));
            for (var row : original)
                edited.put(
                        row.rowNumber(),
                        new LineAmounts(row.rowNumber(), row.quantity(), row.unitPrice()));
            fireTableDataChanged();
        }

        List<LineAmounts> values() {
            return original.stream()
                    .filter(row -> !added.contains(row.rowNumber()))
                    .map(row -> edited.get(row.rowNumber()))
                    .toList();
        }

        List<LineDetails> details() {
            return original.stream()
                    .filter(row -> !added.contains(row.rowNumber()))
                    .map(row -> detailEdits.get(row.rowNumber()))
                    .toList();
        }

        List<LineAccounting> accounting() {
            return original.stream()
                    .filter(row -> !added.contains(row.rowNumber()))
                    .map(row -> accountingEdits.get(row.rowNumber()))
                    .toList();
        }

        List<LineDimensions> dimensions() {
            return original.stream()
                    .filter(row -> !added.contains(row.rowNumber()))
                    .map(row -> dimensionEdits.get(row.rowNumber()))
                    .toList();
        }

        boolean canAdd() {
            return nextKey <= Integer.MAX_VALUE;
        }

        boolean canRemove(int index) {
            return original.size() > 1 || added.contains(original.get(index).rowNumber());
        }

        int add() {
            return add(null);
        }

        int add(NormalizedCustomerFinancialEditService.Product product) {
            int key = Math.toIntExact(nextKey++);
            var copy = new java.util.ArrayList<>(original);
            copy.add(
                    new NormalizedCustomerFinancialEditService.Line(
                            key,
                            product == null ? "" : product.description(),
                            null,
                            BigDecimal.ONE,
                            product == null ? BigDecimal.ZERO : null,
                            null,
                            product == null ? "TAXRATE_0" : null,
                            null,
                            null,
                            product == null ? null : product.number(),
                            product == null ? null : product.unit()));
            original = List.copyOf(copy);
            added.add(key);
            if (product != null) {
                productLinks.put(
                        key,
                        new org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ProductLink(
                                product.id(), product.number(), product.unit()));
            }
            edited.put(
                    key,
                    new LineAmounts(key, BigDecimal.ONE, product == null ? BigDecimal.ZERO : null));
            detailEdits.put(
                    key, new LineDetails(key, product == null ? "" : product.description(), null));
            accountingEdits.put(
                    key, new LineAccounting(key, null, product == null ? "TAXRATE_0" : null));
            dimensionEdits.put(key, new LineDimensions(key, null, null));
            fireTableDataChanged();
            return original.size() - 1;
        }

        List<ManualLine> addedRows() {
            return original.stream()
                    .filter(row -> added.contains(row.rowNumber()))
                    .map(
                            row -> {
                                int key = row.rowNumber();
                                var amount = edited.get(key);
                                var detail = detailEdits.get(key);
                                var account = accountingEdits.get(key);
                                var dimension = dimensionEdits.get(key);
                                return new ManualLine(
                                        key,
                                        detail.description(),
                                        amount.quantity(),
                                        amount.unitPrice(),
                                        detail.discount(),
                                        account.account(),
                                        account.taxCode(),
                                        dimension.project(),
                                        dimension.resultUnit(),
                                        productLinks.get(key));
                            })
                    .toList();
        }

        List<Integer> removedRows() { return List.copyOf(removed); }

        void remove(int index) {
            int key = original.get(index).rowNumber();
            var remaining = new java.util.ArrayList<>(original);remaining.remove(index);original = List.copyOf(remaining);
            edited.remove(key);
            detailEdits.remove(key);
            accountingEdits.remove(key);
            dimensionEdits.remove(key);
            productLinks.remove(key);
            if (!added.remove(key)) removed.add(key);
            fireTableDataChanged();
        }

        public int getRowCount() {
            return original.size();
        }

        public int getColumnCount() {
            return 11;
        }

        public String getColumnName(int column) {
            return text(
                    new String[] {
                                "row",
                                "description",
                                "account",
                                "quantity",
                                "price",
                                "discount",
                                "taxcode",
                                "project",
                                "resultunit",
                                "productnumber",
                                "productunit"
                            }
                            [column]);
        }

        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0, 2 -> Integer.class;
                case 3, 4, 5 -> BigDecimal.class;
                default -> String.class;
            };
        }

        public boolean isCellEditable(int row, int column) {
            return column >= 1
                    && column <= 8
                    && loaded != null
                    && !busy
                    && !invalid
                    && !adapter.isBlocked();
        }

        public Object getValueAt(int row, int column) {
            var base = original.get(row);
            var value = edited.get(base.rowNumber());
            return switch (column) {
                case 0 -> base.rowNumber();
                case 1 -> detailEdits.get(base.rowNumber()).description();
                case 2 -> accountingEdits.get(base.rowNumber()).account();
                case 3 -> value.quantity();
                case 4 -> value.unitPrice();
                case 5 -> detailEdits.get(base.rowNumber()).discount();
                case 6 -> accountingEdits.get(base.rowNumber()).taxCode();
                case 7 -> dimensionEdits.get(base.rowNumber()).project();
                case 8 -> dimensionEdits.get(base.rowNumber()).resultUnit();
                case 9 -> base.productNumber();
                case 10 -> base.unit();
                default -> throw new IndexOutOfBoundsException();
            };
        }

        public void setValueAt(Object value, int row, int column) {
            if (column == 7 || column == 8) {
                int key = original.get(row).rowNumber();var old = dimensionEdits.get(key);
                String input = value == null || value.toString().isEmpty() ? null : value.toString();
                dimensionEdits.put(key, new LineDimensions(key, column == 7 ? input : old.project(), column == 8 ? input : old.resultUnit()));
                fireTableCellUpdated(row, column);changed();return;
            }
            if (column == 2 || column == 6) {
                int key = original.get(row).rowNumber();
                var old = accountingEdits.get(key);
                String input = value == null ? "" : value.toString().trim();
                var next = column == 2
                        ? new LineAccounting(key, input.isEmpty() ? null : Integer.valueOf(input), old.taxCode())
                        : new LineAccounting(key, old.account(), input.isEmpty() ? null : input);
                accountingEdits.put(key, next);
                fireTableCellUpdated(row, column);
                changed();
                return;
            }
            if (column == 1) {
                int key = original.get(row).rowNumber();
                var old = detailEdits.get(key);
                detailEdits.put(key, new LineDetails(key, value == null ? null : value.toString(), old.discount()));
                fireTableCellUpdated(row, column);
                changed();
                return;
            }
            if (column != 3 && column != 4 && column != 5)
                throw new IllegalArgumentException("Read-only field");
            BigDecimal amount =
                    value == null
                            ? null
                            : value instanceof BigDecimal d
                                    ? d
                                    : new BigDecimal(value.toString().replace(',', '.'));
            int key = original.get(row).rowNumber();
            if (column == 5) {
                var detail = detailEdits.get(key);
                detailEdits.put(key, new LineDetails(key, detail.description(), amount));
                fireTableCellUpdated(row, column);
                changed();
                return;
            }
            var old = edited.get(key);
            edited.put(
                    key,
                    new LineAmounts(
                            key,
                            column == 3 ? amount : old.quantity(),
                            column == 4 ? amount : old.unitPrice()));
            fireTableCellUpdated(row, column);
            changed();
        }
    }

    private static LocalDate parse(String value) {
        return value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("customer.financial." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Financial editor requires the EDT");
    }
}
