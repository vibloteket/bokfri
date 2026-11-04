package se.swedsoft.bookkeeping.print.report.journals;


import se.swedsoft.bookkeeping.calc.math.SSOutpaymentMath;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
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
public class SSOutpaymentjournalPrinter extends SSPrinter {

    private Integer iNumber;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;
    private SSJournalRowContext explicitRows;

    /** Creates a journal from explicit company/year, payment snapshots and dimension lookups. */
    public SSOutpaymentjournalPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSOutpayment> payments, Integer number, LocalDate date,
            List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitRows = new SSJournalRowContext(year, projects, resultUnits);
        iOutpayments = new ArrayList<>(payments);
        iNumber = number;
        configureReport(date);
    }


    private List<SSOutpayment> iOutpayments;

    private SSVoucherPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    /**
     *
     * @param iOutpayments
     * @param iNumber
     * @param iDate
     */
    public SSOutpaymentjournalPrinter(List<SSOutpayment> iOutpayments, Integer iNumber, LocalDate iDate) {
        this.iOutpayments = iOutpayments;
        this.iNumber = iNumber;

        configureReport(iDate);
    }

    private void configureReport(LocalDate iDate) {
        setPageHeader("header_period.jrxml");
        setColumnHeader("journals/outpaymentjournal.jrxml");
        setDetail("journals/outpaymentjournal.jrxml");
        setSummary("journals/outpaymentjournal.jrxml");

        addParameter("periodTitle", iBundle.getString("outpaymentjournal.periodtitle"));
        addParameter("periodText", iDate);
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return String.format(iBundle.getString("outpaymentjournal.title"), iNumber);
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
        Collections.sort(iOutpayments, (o1, o2) -> o1.getNumber() - o2.getNumber());

        SSDefaultTableModel<SSOutpayment> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSOutpayment iOutpayment = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iOutpayment.getNumber();
                    break;

                case 1:
                    value = iOutpayment.getText();
                    break;

                case 2:
                    value = iOutpayment.getLocalDate() == null
                            ? null
                            : iOutpayment.getLocalDate().format(iLocalDateFormat);
                    break;

                case 3:
                    value = SSOutpaymentMath.getSum(iOutpayment);
                    break;

                case 4:
                    iPrinter.setOutpayment(iOutpayment);

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("outpayment.number");
        iModel.addColumn("outpayment.text");
        iModel.addColumn("outpayment.date");
        iModel.addColumn("outpayment.sum");

        iModel.addColumn("journal.rows");

        iModel.setObjects(iOutpayments);

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

            setDetail("journals/outpaymentjournal.rows.jrxml");

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
         * @param iOutpayment
         */
        public void setOutpayment(SSOutpayment iOutpayment) {

            iModel.setObjects(iOutpayment.getVoucher().getRows());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter.SSVoucherPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append(
                "se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iNumber=").append(iNumber);
        sb.append(", iOutpayments=").append(iOutpayments);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
