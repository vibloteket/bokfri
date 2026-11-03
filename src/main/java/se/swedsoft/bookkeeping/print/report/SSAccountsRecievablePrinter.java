package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.*;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSAccountsRecievablePrinter extends SSPrinter {

    private SSInvoicePrinter iPrinter;
    private SSCustomerBalanceMath explicitBalances;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;

    /** Creates an isolated report with explicit company/year, register and dated sale/payment inputs. */
    public SSAccountsRecievablePrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSCustomer> customers, List<SSInvoice> invoices, List<SSCreditInvoice> credits,
            List<SSInpayment> payments, LocalDate date) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitBalances = new SSCustomerBalanceMath(company.isRoundingOff(), payments, credits, date);
        iCustomers = new ArrayList<>(customers);
        iDate = date;
        iInpaymentSum = explicitBalances.paymentSums();
        iCreditInvoiceSum = explicitBalances.creditSums();
        iCustomerInvoicesMap = new HashMap<>();
        for (SSCustomer customer : customers) {
            iCustomerInvoicesMap.put(customer.getNumber(), new ArrayList<>());
        }
        for (SSInvoice invoice : invoices) {
            if (SSCustomerBalanceMath.included(invoice, date)) {
                iCustomerInvoicesMap.computeIfAbsent(invoice.getCustomerNr(), key -> new ArrayList<>())
                        .add(invoice);
            }
        }
        configureReport();
    }

    private BigDecimal total(SSInvoice invoice) {
        return explicitBalances == null ? SSInvoiceMath.getTotalSum(invoice) : explicitBalances.total(invoice);
    }


    private SSDefaultJasperDataSource iDataSource;

    private List<SSCustomer> iCustomers;

    private LocalDate iDate;

    Map<String, List<SSInvoice>> iCustomerInvoicesMap;

    HashMap<Integer, BigDecimal> iInpaymentSum;

    HashMap<Integer, BigDecimal> iCreditInvoiceSum;

    /**
     *
     * @param iDate
     */
    public SSAccountsRecievablePrinter(LocalDate iDate) {
        this(iDate, SSDB.getInstance().getCustomers());
    }

    /**
     *
     * @param iDate
     * @param iCustomers
     */
    public SSAccountsRecievablePrinter(LocalDate iDate, List<SSCustomer> iCustomers) {
        // Get all customers
        this.iCustomers = iCustomers;
        this.iDate = iDate;

        iInpaymentSum = SSInpaymentMath.getSumsForInvoices(iDate);

        iCreditInvoiceSum = SSCreditInvoiceMath.getSumsForInvoices(iDate);

        iCustomerInvoicesMap = new HashMap<>();

        for (String iCustomerNumber : SSCustomerMath.iInvoicesForCustomers.keySet()) {
            List<SSInvoice> iInvoicesForCustomer = new LinkedList<>();

            for (SSInvoice iInvoice : SSCustomerMath.iInvoicesForCustomers.get(
                    iCustomerNumber)) {
                if (iInvoice.getLocalDate() != null && !iInvoice.getLocalDate().isAfter(iDate)
                        && iInvoice.getType() != SSInvoiceType.CASH) {
                    iInvoicesForCustomer.add(iInvoice);
                }
            }
            iCustomerInvoicesMap.put(iCustomerNumber, iInvoicesForCustomer);
        }

        configureReport();
    }

    private void configureReport() {
        addParameter("periodTitle",
                iBundle.getString("accountsrecievablereport.periodtitle"));
        addParameter("periodText", iDate);

        setPageHeader("header_period.jrxml");
        setColumnHeader("accountsrecievable.jrxml");
        setDetail("accountsrecievable.jrxml");
        setSummary("accountsrecievable.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("accountsrecievablereport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        iPrinter = explicitBalances == null ? new SSInvoicePrinter()
                : new SSInvoicePrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        SSDefaultTableModel<SSCustomer> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSCustomer.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSCustomer iCustomer = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iCustomer.getNumber();
                    break;

                case 1:
                    value = iCustomer.getName();
                    break;

                case 2:
                    List<SSInvoice> iInvoices = iCustomerInvoicesMap.get(
                            iCustomer.getNumber()); // SSInvoiceMath.getInvoicesForCustomer(iCustomer, iDate);

                    BigDecimal iSum = new BigDecimal(0);

                    for (SSInvoice iInvoice : iInvoices) {
                        BigDecimal iSaldo = total(iInvoice); // SSInvoiceMath.getSaldo(iInvoice, iDate);

                        if (iInpaymentSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iInpaymentSum.get(iInvoice.getNumber()));
                        }
                        if (iCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iCreditInvoiceSum.get(iInvoice.getNumber()));
                        }

                        iSum = iSum.add(SSInvoiceMath.convertToLocal(iInvoice, iSaldo));
                    }
                    value = iSum;
                    break;

                case 3:
                    iPrinter.setCustomer(iCustomer);

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("customer.number");
        iModel.addColumn("customer.name");
        iModel.addColumn("customer.saldosum");
        iModel.addColumn("customer.invoices");

        Collections.sort(iCustomers, (o1, o2) -> o1.getNumber().compareTo(o2.getNumber()));

        iModel.setObjects(iCustomers);

        return iModel;
    }

    private class SSInvoicePrinter extends SSPrinter {

        private SSDefaultTableModel<SSInvoice> iModel;

        /**
         *
         */
        public SSInvoicePrinter() {
            configureRows();
        }

        private SSInvoicePrinter(SSNewCompany company, SSNewAccountingYear year) {
            super(company, year);
            configureRows();
        }

        private void configureRows() {
            setMargins(0, 0, 0, 0);

            setDetail("accountsrecievable.row.jrxml");
            setSummary("accountsrecievable.row.jrxml");

            iModel = new SSDefaultTableModel<>() {

                DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

                @Override
                public Class<?> getType() {
                    return SSSaleRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSInvoice iInvoice = getObject(rowIndex);

                    switch (columnIndex) {
                    case 0:
                        value = iInvoice.getNumber();
                        break;

                    case 1:
                        value = iInvoice.getLocalDate() == null
                                ? null
                                : iInvoice.getLocalDate().format(iFormat);
                        break;

                    case 2:
                        value = iInvoice.getCurrency() == null
                                ? null
                                : iInvoice.getCurrency().getName();
                        break;

                    case 3:
                        value = total(iInvoice);
                        break;

                    case 4:
                        value = iCreditInvoiceSum.get(iInvoice.getNumber()) == null
                                ? new BigDecimal(0)
                                : iCreditInvoiceSum.get(iInvoice.getNumber()); // SSCreditInvoiceMath.getSumForInvoice(iInvoice, iDate);
                        break;

                    case 5:
                        value = iInpaymentSum.get(iInvoice.getNumber()) == null
                                ? new BigDecimal(0)
                                : iInpaymentSum.get(iInvoice.getNumber()); // SSInpaymentMath.getSumForInvoice(iInvoice, iDate);
                        break;

                    case 6:
                        value = iInvoice.getCurrencyRate();
                        break;

                    case 7:
                        BigDecimal iSaldo = total(iInvoice); // SSInvoiceMath.getSaldo(iInvoice, iDate);

                        if (iInpaymentSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iInpaymentSum.get(iInvoice.getNumber()));
                        }
                        if (iCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iCreditInvoiceSum.get(iInvoice.getNumber()));
                        }

                        value = SSInvoiceMath.convertToLocal(iInvoice, iSaldo);
                        break;
                    }

                    return value;
                }
            };

            iModel.addColumn("invoice.number");
            iModel.addColumn("invoice.date");
            iModel.addColumn("invoice.currency");
            iModel.addColumn("invoice.value");
            iModel.addColumn("invoice.credited");
            iModel.addColumn("invoice.payed");
            iModel.addColumn("invoice.currencyrate");
            iModel.addColumn("invoice.localsaldo");
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
         * @param iCustomer
         */
        public void setCustomer(SSCustomer iCustomer) {
            List<SSInvoice> iInvoices = iCustomerInvoicesMap.get(iCustomer.getNumber()); // SSInvoiceMath.getInvoicesForCustomer(iCustomer, iDate);

            iModel.setObjects(iInvoices);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSAccountsRecievablePrinter.SSInvoicePrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSAccountsRecievablePrinter");
        sb.append("{iCreditInvoiceSum=").append(iCreditInvoiceSum);
        sb.append(", iCustomerInvoicesMap=").append(iCustomerInvoicesMap);
        sb.append(", iCustomers=").append(iCustomers);
        sb.append(", iDataSource=").append(iDataSource);
        sb.append(", iDate=").append(iDate);
        sb.append(", iInpaymentSum=").append(iInpaymentSum);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append('}');
        return sb.toString();
    }
}
