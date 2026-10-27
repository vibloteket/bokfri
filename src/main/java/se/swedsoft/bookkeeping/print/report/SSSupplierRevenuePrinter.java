package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.data.SSMonth;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSSupplierRevenuePrinter extends SSPrinter {

    private SSMonthlyDistributionPrinter iPrinter;

    private SSNewCompany iCompany;

    private SSNewAccountingYear iYear;

    private SSDefaultJasperDataSource iDataSource;

    private List<SSSupplier> iSuppliers;

    private LocalDate iDateFrom;

    private LocalDate iDateTo;

    private Map<String, Map<SSMonth, BigDecimal>> iSupplierRevenue;

    /**
     *
     * @param pSuppliers
     * @param pFrom
     * @param pTo
     */
    public SSSupplierRevenuePrinter(List<SSSupplier> pSuppliers, LocalDate pFrom, LocalDate pTo) {
        initialize(pSuppliers, pFrom, pTo, SSDB.getInstance().getSupplierInvoices(),
                SSDB.getInstance().getSupplierCreditInvoices());
    }

    /**
     * Creates a revenue PDF using only explicit company-scoped document snapshots.
     * @param company company for the report and subreport headers
     * @param year hydrated year for the last-voucher header
     * @param registers ordered register entries to display, including entries without activity
     * @param invoices company invoices, filtered by date during calculation
     * @param credits company credit invoices, subtracted using existing currency rules
     * @param from inclusive start
     * @param to inclusive end
     */
    public SSSupplierRevenuePrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSSupplier> registers, List<SSSupplierInvoice> invoices, List<SSSupplierCreditInvoice> credits,
            LocalDate from, LocalDate to) {
        super(company, year);
        iCompany = Objects.requireNonNull(company, "company");
        iYear = year;
        initialize(List.copyOf(registers), from, to, invoices, credits);
    }

    private void initialize(List<SSSupplier> pSuppliers, LocalDate pFrom, LocalDate pTo,
            List<SSSupplierInvoice> iSupplierInvoices, List<SSSupplierCreditInvoice> iSupplierCreditInvoices) {
        iSuppliers = pSuppliers;
        iDateFrom = pFrom;
        iDateTo = pTo;
        calculate(iSupplierInvoices, iSupplierCreditInvoices);
        setPageHeader("header_period.jrxml");
        setColumnHeader("supplierrevenue.jrxml");
        setDetail("supplierrevenue.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("supplierrevenue.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        addParameter("dateFrom", iDateFrom);
        addParameter("dateTo", iDateTo);

        iPrinter = iCompany == null ? new SSMonthlyDistributionPrinter(iDateFrom, iDateTo)
                : new SSMonthlyDistributionPrinter(iCompany, iYear, iDateFrom, iDateTo);
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
                    iPrinter.setSupplier(iSupplier,
                            iSupplierRevenue.get(iSupplier.getNumber()));
                    iDataSource.reset();
                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("supplier.number");
        iModel.addColumn("supplier.description");
        iModel.addColumn("month.data");

        iModel.setObjects(iSuppliers);

        return iModel;
    }

    private void calculate(List<SSSupplierInvoice> iSupplierInvoices, List<SSSupplierCreditInvoice> iSupplierCreditInvoices) {
        iSupplierRevenue = new HashMap<>();
        LocalDate localFrom = iDateFrom;
        LocalDate localTo = iDateTo;

        for (SSSupplierInvoice iSupplierInvoice : iSupplierInvoices) {
            LocalDate invoiceLocalDate = iSupplierInvoice.getLocalDate();
            if (invoiceLocalDate != null && localFrom != null && localTo != null
                    && !invoiceLocalDate.isBefore(localFrom) && !invoiceLocalDate.isAfter(localTo)) {
                LocalDate monthStart = invoiceLocalDate.withDayOfMonth(1);
                LocalDate monthEnd = invoiceLocalDate.withDayOfMonth(invoiceLocalDate.lengthOfMonth());
                SSMonth iMonth = new SSMonth(monthStart, monthEnd);

                if (iSupplierInvoice.getSupplierNr() != null
                        && SSSupplierInvoiceMath.getNetSum(iSupplierInvoice) != null) {
                    BigDecimal iSum = SSSupplierInvoiceMath.convertToLocal(
                            iSupplierInvoice,
                            SSSupplierInvoiceMath.getNetSum(iSupplierInvoice));
                    Map<SSMonth, BigDecimal> iRevenueInMonth;

                    if (iSupplierRevenue.containsKey(iSupplierInvoice.getSupplierNr())) {
                        iRevenueInMonth = iSupplierRevenue.get(
                                iSupplierInvoice.getSupplierNr());
                        if (iRevenueInMonth.containsKey(iMonth)) {
                            iRevenueInMonth.put(iMonth,
                                    iRevenueInMonth.get(iMonth).add(iSum));
                        } else {
                            iRevenueInMonth.put(iMonth, iSum);
                        }
                    } else {
                        iRevenueInMonth = new HashMap<>();
                        iRevenueInMonth.put(iMonth, iSum);
                    }
                    iSupplierRevenue.put(iSupplierInvoice.getSupplierNr(), iRevenueInMonth);
                }
            }
        }


        for (SSSupplierCreditInvoice iSupplierCreditInvoice : iSupplierCreditInvoices) {
            LocalDate creditLocalDate = iSupplierCreditInvoice.getLocalDate();
            if (creditLocalDate != null && localFrom != null && localTo != null
                    && !creditLocalDate.isBefore(localFrom) && !creditLocalDate.isAfter(localTo)) {
                LocalDate monthStart = creditLocalDate.withDayOfMonth(1);
                LocalDate monthEnd = creditLocalDate.withDayOfMonth(creditLocalDate.lengthOfMonth());
                SSMonth iMonth = new SSMonth(monthStart, monthEnd);

                if (iSupplierCreditInvoice.getSupplierNr() != null
                        && SSSupplierInvoiceMath.getNetSum(iSupplierCreditInvoice) != null) {
                    BigDecimal iSum = SSSupplierInvoiceMath.convertToLocal(
                            iSupplierCreditInvoice,
                            SSSupplierInvoiceMath.getNetSum(iSupplierCreditInvoice));
                    Map<SSMonth, BigDecimal> iRevenueInMonth;

                    if (iSupplierRevenue.containsKey(
                            iSupplierCreditInvoice.getSupplierNr())) {
                        iRevenueInMonth = iSupplierRevenue.get(
                                iSupplierCreditInvoice.getSupplierNr());
                        if (iRevenueInMonth.containsKey(iMonth)) {
                            iRevenueInMonth.put(iMonth,
                                    iRevenueInMonth.get(iMonth).subtract(iSum));
                        } else {
                            iRevenueInMonth.put(iMonth, iSum.negate());
                        }
                    } else {
                        iRevenueInMonth = new HashMap<>();
                        iRevenueInMonth.put(iMonth, iSum.negate());
                    }
                    iSupplierRevenue.put(iSupplierCreditInvoice.getSupplierNr(),
                            iRevenueInMonth);
                }
            }
        }
    }

    private class SSMonthlyDistributionPrinter extends SSPrinter {

        private SSDefaultTableModel<SSMonth> iModel;

        private SSSupplier iSupplier;
        private LocalDate iFrom;

        private LocalDate iTo;

        private LocalDate iLocalFrom;

        private LocalDate iLocalTo;

        private Map<SSMonth, BigDecimal> iRevenue;

        /**
         *
         * @param pFrom
         * @param pTo
         */
        public SSMonthlyDistributionPrinter(LocalDate pFrom, LocalDate pTo) {
            initialize(pFrom, pTo);
        }

        private SSMonthlyDistributionPrinter(SSNewCompany company, SSNewAccountingYear year,
                LocalDate from, LocalDate to) {
            super(company, year);
            initialize(from, to);
        }

        private void initialize(LocalDate pFrom, LocalDate pTo) {
            iFrom = pFrom;
            iTo = pTo;
            iLocalFrom = pFrom;
            iLocalTo = pTo;
            setMargins(0, 0, 0, 0);

            setDetail("supplierrevenue.monthly.jrxml");
            setSummary("supplierrevenue.monthly.jrxml");

            iModel = new SSDefaultTableModel<>(
                    SSMonth.splitYearIntoMonths(iLocalFrom, iLocalTo)) {

                @Override
                public Class<?> getType() {
                    return SSMonth.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSMonth iMonth = getObject(rowIndex);

                    switch (columnIndex) {
                    case 0:
                        value = iMonth.toString();
                        break;

                    case 1:
                        value = iMonth.getName();
                        break;

                    case 2:
                        if (iSupplier != null && iRevenue.containsKey(iMonth)) {
                            value = iRevenue.get(iMonth);
                        } else {
                            value = new BigDecimal(0);
                        }
                        break;

                    case 3:
                        value = iMonth.isBetween(iLocalFrom, iLocalTo);
                        break;
                    }

                    return value;
                }
            };

            iModel.addColumn("month.date");
            iModel.addColumn("month.description");
            iModel.addColumn("month.value");
            iModel.addColumn("month.visible");
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
         * @param pSupplier
         * @param iMap
         */
        public void setSupplier(SSSupplier pSupplier, Map<SSMonth, BigDecimal> iMap) {
            iSupplier = pSupplier;
            iRevenue = iMap;
            if (iRevenue == null) {
                iRevenue = new HashMap<>();
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSSupplierRevenuePrinter.SSMonthlyDistributionPrinter");
            sb.append("{iFrom=").append(iFrom);
            sb.append(", iModel=").append(iModel);
            sb.append(", iRevenue=").append(iRevenue);
            sb.append(", iSupplier=").append(iSupplier);
            sb.append(", iTo=").append(iTo);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSSupplierRevenuePrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iDateFrom=").append(iDateFrom);
        sb.append(", iDateTo=").append(iDateTo);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iSupplierRevenue=").append(iSupplierRevenue);
        sb.append(", iSuppliers=").append(iSuppliers);
        sb.append('}');
        return sb.toString();
    }
}
