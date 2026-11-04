package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSOutpaymentMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

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
public class SSOutpaymentListPrinter extends SSPrinter {

    private SSOutpaymentRowPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    private List<SSOutpayment> iOutpayments;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;

    /** Creates a payment-list report from explicit company/year and payment snapshots. */
    public SSOutpaymentListPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSOutpayment> payments) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        iOutpayments = new ArrayList<>(payments);
        configureReport();
    }


    /**
     *
     */
    public SSOutpaymentListPrinter() {
        this(SSDB.getInstance().getOutpayments());
    }

    /**
     *
     * @param iInpayments
     */
    public SSOutpaymentListPrinter(List<SSOutpayment> iInpayments) {
        // Get all orders
        iOutpayments = iInpayments;

        configureReport();
    }

    private void configureReport() {
        setPageHeader("header_period.jrxml");
        setColumnHeader("outpaymentlist.jrxml");
        setDetail("outpaymentlist.jrxml");
        setSummary("outpaymentlist.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("outpaymentlistreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        iPrinter = explicitCompany == null ? new SSOutpaymentRowPrinter()
                : new SSOutpaymentRowPrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        SSDefaultTableModel<SSOutpayment> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSAccount.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSOutpayment iOutpayment = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iOutpayment.getNumber();
                    break;

                case 1:
                    value = iOutpayment.getLocalDate() == null
                            ? null
                            : iOutpayment.getLocalDate().format(iLocalDateFormat);
                    break;

                case 2:
                    value = iOutpayment.getText();
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
        iModel.addColumn("outpayment.date");
        iModel.addColumn("outpayment.text");
        iModel.addColumn("outpayment.sum");
        iModel.addColumn("outpayment.rows");

        Collections.sort(iOutpayments, (o1, o2) -> o1.getNumber() - o2.getNumber());

        iModel.setObjects(iOutpayments);

        return iModel;
    }

    private class SSOutpaymentRowPrinter extends SSPrinter {

        private SSDefaultTableModel<SSOutpaymentRow> iModel;

        /**
         *
         */
        public SSOutpaymentRowPrinter() {
            configureRows();
        }

        private SSOutpaymentRowPrinter(SSNewCompany company, SSNewAccountingYear year) {
            super(company, year);
            configureRows();
        }

        private void configureRows() {
            setMargins(0, 0, 0, 0);

            setDetail("outpaymentlist.row.jrxml");
            setSummary("outpaymentlist.row.jrxml");

            iModel = new SSDefaultTableModel<>() {

                @Override
                public Class<?> getType() {
                    return SSOutpaymentRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSOutpaymentRow iRow = getObject(rowIndex);

                    switch (columnIndex) {
                    case 0:
                        value = iRow.getInvoiceNr();
                        break;

                    case 1:
                        value = iRow.getInvoiceCurrencyRate();
                        break;

                    case 2:
                        value = iRow.getInvoiceCurrency() == null
                                ? null
                                : iRow.getInvoiceCurrency().getName();
                        break;

                    case 3:
                        value = iRow.getValue();
                        break;

                    case 4:
                        value = iRow.getCurrencyRate();
                        break;

                    case 5:
                        value = iRow.getLocalValue().orElse(null);
                        break;
                    }

                    return value;
                }
            };

            iModel.addColumn("invoice.number");
            iModel.addColumn("invoice.currencyrate");
            iModel.addColumn("invoice.currency");
            iModel.addColumn("row.value");
            iModel.addColumn("row.currencyrate");
            iModel.addColumn("row.payed");
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
        public void setOutpayment(SSOutpayment iInpayment) {
            iModel.setObjects(iInpayment.getRows());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSOutpaymentListPrinter.SSOutpaymentRowPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSOutpaymentListPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iOutpayments=").append(iOutpayments);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
