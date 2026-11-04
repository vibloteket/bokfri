package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSInpaymentMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
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
public class SSInpaymentListPrinter extends SSPrinter {

    private SSInpaymentRowPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    private List<SSInpayment> iInpayments;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;

    /** Creates a payment-list report from explicit company/year and payment snapshots. */
    public SSInpaymentListPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSInpayment> payments) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        iInpayments = new ArrayList<>(payments);
        configureReport();
    }


    /**
     *
     */
    public SSInpaymentListPrinter() {
        this(SSDB.getInstance().getInpayments());
    }

    /**
     *
     * @param iInpayments
     */
    public SSInpaymentListPrinter(List<SSInpayment> iInpayments) {
        // Get all orders
        this.iInpayments = iInpayments;

        configureReport();
    }

    private void configureReport() {
        setPageHeader("header_period.jrxml");
        setColumnHeader("inpaymentlist.jrxml");
        setDetail("inpaymentlist.jrxml");
        setSummary("inpaymentlist.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("inpaymentlistreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        iPrinter = explicitCompany == null ? new SSInpaymentRowPrinter()
                : new SSInpaymentRowPrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        SSDefaultTableModel<SSInpayment> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSAccount.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSInpayment iInpayment = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iInpayment.getNumber();
                    break;

                case 1:
                    value = iInpayment.getLocalDate() == null
                            ? null
                            : iInpayment.getLocalDate().format(iLocalDateFormat);
                    break;

                case 2:
                    value = iInpayment.getText();
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
        iModel.addColumn("inpayment.date");
        iModel.addColumn("inpayment.text");
        iModel.addColumn("inpayment.sum");
        iModel.addColumn("inpayment.rows");

        Collections.sort(iInpayments, (o1, o2) -> o1.getNumber() - o2.getNumber());

        iModel.setObjects(iInpayments);

        return iModel;
    }

    private class SSInpaymentRowPrinter extends SSPrinter {

        private SSDefaultTableModel<SSInpaymentRow> iModel;

        /**
         *
         */
        public SSInpaymentRowPrinter() {
            configureRows();
        }

        private SSInpaymentRowPrinter(SSNewCompany company, SSNewAccountingYear year) {
            super(company, year);
            configureRows();
        }

        private void configureRows() {
            setMargins(0, 0, 0, 0);

            setDetail("inpaymentlist.row.jrxml");
            setSummary("inpaymentlist.row.jrxml");

            iModel = new SSDefaultTableModel<>() {

                @Override
                public Class<?> getType() {
                    return SSSaleRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSInpaymentRow iRow = getObject(rowIndex);

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
            iModel.addColumn("inpaymentrow.value");
            iModel.addColumn("inpaymentrow.currencyrate");
            iModel.addColumn("inpaymentrow.payed");
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
            iModel.setObjects(iInpayment.getRows());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSInpaymentListPrinter.SSInpaymentRowPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSInpaymentListPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iInpayments=").append(iInpayments);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
