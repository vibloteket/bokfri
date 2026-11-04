package se.swedsoft.bookkeeping.print.report.journals;


import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSInvoicejournalPrinter extends SSPrinter {

    private Integer iNumber;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;
    private SSJournalRowContext explicitRows;

    /** Creates a journal from explicit company/year, invoice snapshots and dimension lookups. */
    public SSInvoicejournalPrinter(SSNewCompany company, SSNewAccountingYear year, List<SSInvoice> invoices,
            Integer number, LocalDate date, List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitRows = new SSJournalRowContext(year, projects, resultUnits);
        iInvoices = new ArrayList<>(invoices);
        iNumber = number;
        configureReport(date);
    }

    private BigDecimal total(SSInvoice invoice) {
        return explicitCompany == null ? SSInvoiceMath.getTotalSum(invoice)
                : SSInvoiceMath.getTotalSum(invoice, explicitCompany.isRoundingOff());
    }

    private SSAccount account(SSVoucherRow row) {
        return explicitRows == null ? row.getAccount() : explicitRows.account(row);
    }


    private List<SSInvoice> iInvoices;

    private SSVoucherPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    /**
     *
     * @param iInvoices
     * @param iNumber
     * @param iDate
     */
    public SSInvoicejournalPrinter(List<SSInvoice> iInvoices, Integer iNumber, LocalDate iDate) {
        this.iInvoices = iInvoices;
        this.iNumber = iNumber;

        configureReport(iDate);
    }

    private void configureReport(LocalDate iDate) {
        setPageHeader("header_period.jrxml");
        setColumnHeader("journals/invoicejournal.jrxml");
        setDetail("journals/invoicejournal.jrxml");
        setSummary("journals/invoicejournal.jrxml");

        addParameter("periodTitle", iBundle.getString("invoicejournal.periodtitle"));
        addParameter("periodText", iDate);
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return String.format(iBundle.getString("invoicejournal.title"), iNumber);
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        iPrinter = explicitRows == null ? new SSVoucherPrinter()
                : new SSVoucherPrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        // Sort the invoices
        Collections.sort(iInvoices, (o1, o2) -> o1.getNumber() - o2.getNumber());

        SSDefaultTableModel<SSInvoice> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSInvoice iInvoice = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iInvoice.getNumber();
                    break;

                case 1:
                    value = iInvoice.getCustomerNr();
                    break;

                case 2:
                    value = iInvoice.getCustomerName();
                    break;

                case 3:
                    value = iInvoice.getLocalDate() == null
                            ? null
                            : iInvoice.getLocalDate().format(iLocalDateFormat);
                    break;

                case 4:
                    value = iInvoice.getCurrency() == null
                            ? ""
                            : iInvoice.getCurrency().getName();
                    break;

                case 5:
                    value = iInvoice.getCurrencyRate();
                    break;

                case 6:
                    value = total(iInvoice);
                    break;

                case 7:
                    value = SSInvoiceMath.getTotalTaxSum(iInvoice);
                    break;

                case 8:
                    BigDecimal iTotalSum = total(iInvoice);

                    value = SSInvoiceMath.convertToLocal(iInvoice, iTotalSum);
                    break;

                case 9:
                    iPrinter.setInvoice(iInvoice);

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("invoice.number");
        iModel.addColumn("invoice.customernr");
        iModel.addColumn("invoice.customername");
        iModel.addColumn("invoice.date");
        iModel.addColumn("invoice.currency");
        iModel.addColumn("invoice.currencyrate");
        iModel.addColumn("invoice.sum");
        iModel.addColumn("invoice.tax");
        iModel.addColumn("invoice.localsum");

        iModel.addColumn("journal.rows");

        iModel.setObjects(iInvoices);

        return iModel;
    }

    private class SSVoucherPrinter extends SSPrinter {

        private SSDefaultTableModel<SSVoucherRow> iModel;

        /**
         *
         */
        public SSVoucherPrinter() {
            configureRows();
        }

        private SSVoucherPrinter(SSNewCompany company, SSNewAccountingYear year) {
            super(company, year);
            configureRows();
        }

        private void configureRows() {
            setMargins(0, 0, 0, 0);

            setDetail("journals/invoicejournal.rows.jrxml");

            iModel = new SSDefaultTableModel<>() {

                @Override
                public Class<?> getType() {
                    return SSVoucherRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSVoucherRow iRow = getObject(rowIndex);

                    switch (columnIndex) {
                    case 0:
                        value = account(iRow) == null
                                ? null
                                : account(iRow).getNumber();
                        break;

                    case 1:
                        value = account(iRow) == null
                                ? null
                                : account(iRow).getDescription();
                        break;

                    case 2:
                        value = iRow.getDebet();
                        break;

                    case 3:
                        value = iRow.getCredit();
                        break;

                    case 4:
                        value = explicitRows == null
                                ? (iRow.getProject() == null ? null : iRow.getProject().getNumber())
                                : explicitRows.project(iRow);
                        break;

                    case 5:
                        value = explicitRows == null
                                ? (iRow.getResultUnit() == null ? null : iRow.getResultUnit().getNumber())
                                : explicitRows.resultUnit(iRow);
                        break;

                    }

                    return value;
                }
            };

            iModel.addColumn("row.account");
            iModel.addColumn("row.description");
            iModel.addColumn("row.debet");
            iModel.addColumn("row.credet");
            iModel.addColumn("row.project");
            iModel.addColumn("row.resultunit");
        }

        /**
         * Gets the data model for this report
         *
         * @return SSDefaultTableModel
         */
        @Override
        protected SSDefaultTableModel getModel() {
            return iModel;
        }

        /**
         * Gets the title for this report
         *
         * @return The title
         */
        @Override
        public String getTitle() {
            return null;
        }

        /**
         *
         * @param iInvoice
         */
        public void setInvoice(SSInvoice iInvoice) {

            iModel.setObjects(iInvoice.getVoucher().getRows());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.journals.SSInvoicejournalPrinter.SSVoucherPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.journals.SSInvoicejournalPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iInvoices=").append(iInvoices);
        sb.append(", iNumber=").append(iNumber);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
