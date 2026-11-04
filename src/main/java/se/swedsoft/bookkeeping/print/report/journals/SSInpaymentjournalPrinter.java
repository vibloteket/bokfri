package se.swedsoft.bookkeeping.print.report.journals;


import se.swedsoft.bookkeeping.calc.math.SSInpaymentMath;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSInpaymentjournalPrinter extends SSPrinter {

    private Integer iNumber;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;
    private SSJournalRowContext explicitRows;

    /** Creates a journal from explicit company/year, payment snapshots and dimension lookups. */
    public SSInpaymentjournalPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSInpayment> payments, Integer number, LocalDate date,
            List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitRows = new SSJournalRowContext(year, projects, resultUnits);
        iInpayments = new ArrayList<>(payments);
        iNumber = number;
        configureReport(date);
    }


    private List<SSInpayment> iInpayments;

    private SSVoucherPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    /**
     *
     * @param iInpayments
     * @param iNumber
     * @param iDate
     */
    public SSInpaymentjournalPrinter(List<SSInpayment> iInpayments, Integer iNumber, LocalDate iDate) {
        this.iInpayments = iInpayments;
        this.iNumber = iNumber;

        configureReport(iDate);
    }

    private void configureReport(LocalDate iDate) {
        setPageHeader("header_period.jrxml");
        setColumnHeader("journals/inpaymentjournal.jrxml");
        setDetail("journals/inpaymentjournal.jrxml");
        setSummary("journals/inpaymentjournal.jrxml");

        addParameter("periodTitle", iBundle.getString("inpaymentjournal.periodtitle"));
        addParameter("periodText", iDate);
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return String.format(iBundle.getString("inpaymentjournal.title"), iNumber);
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        // Get all invoices
        // List<SSInvoice> iInvoices = new LinkedList<>( iSaldos.keySet() );

        iPrinter = explicitRows == null ? new SSVoucherPrinter()
                : new SSVoucherPrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        // Sort the invoices
        Collections.sort(iInpayments, (o1, o2) -> o1.getNumber() - o2.getNumber());

        SSDefaultTableModel<SSInpayment> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSInpayment iInpayment = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iInpayment.getNumber();
                    break;

                case 1:
                    value = iInpayment.getText();
                    break;

                case 2:
                    value = iInpayment.getLocalDate() == null
                            ? null
                            : iInpayment.getLocalDate().format(iLocalDateFormat);
                    break;

                case 3:
                    value = SSInpaymentMath.getSum(iInpayment);
                    break;

                case 4:
                    iPrinter.setInpayment(iInpayment);

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("inpayment.number");
        iModel.addColumn("inpayment.text");
        iModel.addColumn("inpayment.date");
        iModel.addColumn("inpayment.sum");

        iModel.addColumn("journal.rows");

        iModel.setObjects(iInpayments);

        return iModel;
    }

    private SSAccount account(SSVoucherRow row) {
        return explicitRows == null ? row.getAccount() : explicitRows.account(row);
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

            setDetail("journals/inpaymentjournal.rows.jrxml");

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
         * @param iInpayment
         */
        public void setInpayment(SSInpayment iInpayment) {

            iModel.setObjects(iInpayment.getVoucher().getRows());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter.SSVoucherPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append(
                "se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iInpayments=").append(iInpayments);
        sb.append(", iNumber=").append(iNumber);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
