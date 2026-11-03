package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.*;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
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
public class SSAccountsPayablePrinter extends SSPrinter {

    private SSInvoicePrinter iPrinter;
    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;
    private boolean explicitInputs;

    /** Creates a report from explicit company/year, supplier register and dated invoice/payment inputs. */
    public SSAccountsPayablePrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSSupplier> suppliers, List<SSSupplierInvoice> invoices,
            List<SSSupplierCreditInvoice> credits, List<SSOutpayment> payments, LocalDate date) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitInputs = true;
        iSuppliers = new ArrayList<>(suppliers);
        iDate = date;
        SSSupplierBalanceMath balances = new SSSupplierBalanceMath(payments, credits, date);
        iOutpaymentSum = balances.paymentSums();
        iSupplierCreditInvoiceSum = balances.creditSums();
        iSupplierInvoicesMap = new HashMap<>();
        for (SSSupplier supplier : suppliers) {
            iSupplierInvoicesMap.put(supplier.getNumber(), new ArrayList<>());
        }
        for (SSSupplierInvoice invoice : invoices) {
            if (SSSupplierBalanceMath.included(invoice, date)) {
                iSupplierInvoicesMap.computeIfAbsent(invoice.getSupplierNr(), key -> new ArrayList<>())
                        .add(invoice);
            }
        }
        configureReport();
    }


    private SSDefaultJasperDataSource iDataSource;

    private List<SSSupplier> iSuppliers;

    private LocalDate iDate;

    Map<String, List<SSSupplierInvoice>> iSupplierInvoicesMap;

    HashMap<Integer, BigDecimal> iOutpaymentSum;

    HashMap<Integer, BigDecimal> iSupplierCreditInvoiceSum;

    /**
     *
     * @param iDate
     */
    public SSAccountsPayablePrinter(LocalDate iDate) {
        this(iDate, SSDB.getInstance().getSuppliers());
    }

    /**
     *
     * @param iDate
     * @param iSuppliers
     */
    public SSAccountsPayablePrinter(LocalDate iDate, List<SSSupplier> iSuppliers) {
        // Get all customers
        this.iSuppliers = iSuppliers;
        this.iDate = iDate;

        iOutpaymentSum = SSOutpaymentMath.getSumsForSupplierInvoices(iDate);

        iSupplierCreditInvoiceSum = SSSupplierCreditInvoiceMath.getSumsForSupplierInvoices(
                iDate);

        iSupplierInvoicesMap = new HashMap<>();

        for (String iSupplierNumber : SSSupplierMath.iInvoicesForSuppliers.keySet()) {
            List<SSSupplierInvoice> iInvoicesForCustomer = new LinkedList<>();

            for (SSSupplierInvoice iInvoice : SSSupplierMath.iInvoicesForSuppliers.get(
                    iSupplierNumber)) {
                if (iInvoice.getLocalDate() != null && !iInvoice.getLocalDate().isAfter(iDate)) {
                    iInvoicesForCustomer.add(iInvoice);
                }
            }
            iSupplierInvoicesMap.put(iSupplierNumber, iInvoicesForCustomer);
        }
        configureReport();
    }

    private void configureReport() {
        addParameter("periodTitle", iBundle.getString("accountspayablereport.periodtitle"));
        addParameter("periodText", iDate);

        setPageHeader("header_period.jrxml");
        setColumnHeader("accountspayable.jrxml");
        setDetail("accountspayable.jrxml");
        setSummary("accountspayable.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("accountspayablereport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        iPrinter = explicitInputs ? new SSInvoicePrinter(explicitCompany, explicitYear)
                : new SSInvoicePrinter();
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());

        SSDefaultTableModel<SSSupplier> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSSupplier.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSSupplier iSupplier = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iSupplier.getNumber();
                    break;

                case 1:
                    value = iSupplier.getName();
                    break;

                case 2:
                    List<SSSupplierInvoice> iInvoices = iSupplierInvoicesMap.get(
                            iSupplier.getNumber());

                    BigDecimal iSum = new BigDecimal(0);

                    for (SSSupplierInvoice iInvoice : iInvoices) {
                        BigDecimal iSaldo = SSSupplierInvoiceMath.getTotalSum(iInvoice);

                        if (iOutpaymentSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iOutpaymentSum.get(iInvoice.getNumber()));
                        }
                        if (iSupplierCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iSupplierCreditInvoiceSum.get(iInvoice.getNumber()));
                        }

                        iSum = iSum.add(
                                SSSupplierInvoiceMath.convertToLocal(iInvoice, iSaldo));
                    }
                    value = iSum;
                    break;

                // List<SSSupplierInvoice> iInvoices = SSSupplierInvoiceMath.getInvoicesForSupplier(iSupplier, iDate);

                // value = SSSupplierInvoiceMath.getSaldoSum(iInvoices, iDate);
                case 3:
                    iPrinter.setSupplier(iSupplier);

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("supplier.number");
        iModel.addColumn("supplier.name");
        iModel.addColumn("supplier.saldosum");
        iModel.addColumn("supplier.invoices");

        Collections.sort(iSuppliers, (o1, o2) -> o1.getNumber().compareTo(o2.getNumber()));

        iModel.setObjects(iSuppliers);

        return iModel;
    }

    private class SSInvoicePrinter extends SSPrinter {

        private SSDefaultTableModel<SSSupplierInvoice> iModel;

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

            setDetail("accountspayable.row.jrxml");
            setSummary("accountspayable.row.jrxml");

            iModel = new SSDefaultTableModel<>() {

                DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

                @Override
                public Class<?> getType() {
                    return SSSaleRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSSupplierInvoice iInvoice = getObject(rowIndex);

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
                        value = SSSupplierInvoiceMath.getTotalSum(iInvoice);
                        break;

                    case 4:
                        value = iSupplierCreditInvoiceSum.get(iInvoice.getNumber())
                                == null
                                        ? new BigDecimal(0)
                                        : iSupplierCreditInvoiceSum.get(
                                                iInvoice.getNumber()); // value = SSSupplierCreditInvoiceMath.getSumForInvoice(iInvoice, iDate);
                        break;

                    case 5:
                        value = iOutpaymentSum.get(iInvoice.getNumber()) == null
                                ? new BigDecimal(0)
                                : iOutpaymentSum.get(iInvoice.getNumber()); // value = SSOutpaymentMath    .getSumForInvoice(iInvoice, iDate);
                        break;

                    case 6:
                        value = iInvoice.getCurrencyRate();
                        break;

                    case 7:
                        // BigDecimal iSaldo = SSSupplierInvoiceMath.getSaldo(iInvoice, iDate);

                        // value = SSSupplierInvoiceMath.convertToLocal(iInvoice, iSaldo);
                        // break;
                        BigDecimal iSaldo = SSSupplierInvoiceMath.getTotalSum(iInvoice); // SSInvoiceMath.getSaldo(iInvoice, iDate);

                        if (iOutpaymentSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iOutpaymentSum.get(iInvoice.getNumber()));
                        }
                        if (iSupplierCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                            iSaldo = iSaldo.subtract(
                                    iSupplierCreditInvoiceSum.get(iInvoice.getNumber()));
                        }

                        value = SSSupplierInvoiceMath.convertToLocal(iInvoice, iSaldo);
                    }

                    return value;
                }
            };

            iModel.addColumn("supplierinvoice.number");
            iModel.addColumn("supplierinvoice.date");
            iModel.addColumn("supplierinvoice.currency");
            iModel.addColumn("supplierinvoice.value");
            iModel.addColumn("supplierinvoice.credited");
            iModel.addColumn("supplierinvoice.payed");
            iModel.addColumn("supplierinvoice.currencyrate");
            iModel.addColumn("supplierinvoice.localsaldo");
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
         * @param iSupplier
         */
        public void setSupplier(SSSupplier iSupplier) {
            List<SSSupplierInvoice> iInvoices = iSupplierInvoicesMap.get(
                    iSupplier.getNumber()); // SSSupplierInvoiceMath.getInvoicesForSupplier(iSupplier, iDate);

            iModel.setObjects(iInvoices);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSAccountsPayablePrinter.SSInvoicePrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSAccountsPayablePrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iDate=").append(iDate);
        sb.append(", iOutpaymentSum=").append(iOutpaymentSum);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iSupplierCreditInvoiceSum=").append(iSupplierCreditInvoiceSum);
        sb.append(", iSupplierInvoicesMap=").append(iSupplierInvoicesMap);
        sb.append(", iSuppliers=").append(iSuppliers);
        sb.append('}');
        return sb.toString();
    }
}
