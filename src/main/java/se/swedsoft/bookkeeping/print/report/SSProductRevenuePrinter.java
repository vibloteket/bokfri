package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSCreditInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSMonth;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSProductRevenuePrinter extends SSPrinter {

    private SSMonthlyDistributionPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    private List<SSProduct> iProducts;

    private SSNewCompany iCompany;

    private SSNewAccountingYear iYear;

    private LocalDate iDateFrom;

    private LocalDate iDateTo;

    Map<String, Map<SSMonth, BigDecimal>> iProductRevenue;

    /**
     *
     * @param pProducts
     * @param pFrom
     * @param pTo
     */
    public SSProductRevenuePrinter(List<SSProduct> pProducts, LocalDate pFrom, LocalDate pTo) {
        initialize(pProducts, SSDB.getInstance().getInvoices(), SSDB.getInstance().getCreditInvoices(), pFrom, pTo);
    }

    /**
     * Creates a product revenue report from explicit company/year and document snapshots.
     *
     * @param company company used for report headers
     * @param year accounting year with hydrated vouchers for report headers
     * @param products ordered register entries, including products without activity
     * @param invoices company invoices, filtered by the inclusive date bounds
     * @param credits company credit invoices, subtracted from revenue
     * @param from inclusive first date
     * @param to inclusive last date
     */
    public SSProductRevenuePrinter(SSNewCompany company, SSNewAccountingYear year, List<SSProduct> products,
            List<SSInvoice> invoices, List<SSCreditInvoice> credits, LocalDate from, LocalDate to) {
        super(company, year);
        iCompany = company;
        iYear = year;
        initialize(List.copyOf(products), invoices, credits, from, to);
    }

    private void initialize(List<SSProduct> products, List<SSInvoice> invoices, List<SSCreditInvoice> credits,
            LocalDate from, LocalDate to) {
        iProducts = products;
        iDateFrom = from;
        iDateTo = to;
        calculate(invoices, credits);
        setPageHeader("header_period.jrxml");
        setColumnHeader("productrevenue.jrxml");
        setDetail("productrevenue.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("productrevenue.title");
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

        SSDefaultTableModel<SSProduct> iModel = new SSDefaultTableModel<>() {
            @Override
            public Class<?> getType() {
                return SSProduct.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSProduct iProduct = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iProduct.getNumber();
                    break;

                case 1:
                    value = iProduct.getDescription();
                    break;

                case 2:
                    iPrinter.setProduct(iProduct,
                            iProductRevenue.get(iProduct.getNumber()));
                    iDataSource.reset();
                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("product.number");
        iModel.addColumn("product.description");
        iModel.addColumn("month.data");

        iModel.setObjects(iProducts);

        return iModel;
    }

    private void calculate(List<SSInvoice> iInvoices, List<SSCreditInvoice> iCreditInvoices) {
        iProductRevenue = new HashMap<>();
        LocalDate localFrom = iDateFrom;
        LocalDate localTo = iDateTo;

        for (SSInvoice iInvoice : iInvoices) {
            LocalDate invoiceLocalDate = iInvoice.getLocalDate();
            if (invoiceLocalDate != null && localFrom != null && localTo != null
                    && !invoiceLocalDate.isBefore(localFrom) && !invoiceLocalDate.isAfter(localTo)) {
                LocalDate monthStart = invoiceLocalDate.withDayOfMonth(1);
                LocalDate monthEnd = invoiceLocalDate.withDayOfMonth(invoiceLocalDate.lengthOfMonth());
                SSMonth iMonth = new SSMonth(monthStart, monthEnd);

                for (SSSaleRow iRow : iInvoice.getRows()) {
                    if (iRow.getProductNr() != null && iRow.getSum().isPresent()) {
                        BigDecimal iSum = SSInvoiceMath.convertToLocal(iInvoice,
                                iRow.getSum().get());
                        Map<SSMonth, BigDecimal> iRevenueInMonth;

                        if (iProductRevenue.containsKey(iRow.getProductNr())) {
                            iRevenueInMonth = iProductRevenue.get(iRow.getProductNr());
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
                        iProductRevenue.put(iRow.getProductNr(), iRevenueInMonth);
                    }
                }
            }
        }

        for (SSCreditInvoice iCreditInvoice : iCreditInvoices) {
            LocalDate creditLocalDate = iCreditInvoice.getLocalDate();
            if (creditLocalDate != null && localFrom != null && localTo != null
                    && !creditLocalDate.isBefore(localFrom) && !creditLocalDate.isAfter(localTo)) {
                LocalDate monthStart = creditLocalDate.withDayOfMonth(1);
                LocalDate monthEnd = creditLocalDate.withDayOfMonth(creditLocalDate.lengthOfMonth());
                SSMonth iMonth = new SSMonth(monthStart, monthEnd);

                for (SSSaleRow iRow : iCreditInvoice.getRows()) {
                    if (iRow.getProductNr() != null && iRow.getSum().isPresent()) {
                        BigDecimal iSum = SSCreditInvoiceMath.convertToLocal(
                                iCreditInvoice, iRow.getSum().get());
                        Map<SSMonth, BigDecimal> iRevenueInMonth;

                        if (iProductRevenue.containsKey(iRow.getProductNr())) {
                            iRevenueInMonth = iProductRevenue.get(iRow.getProductNr());
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
                        iProductRevenue.put(iRow.getProductNr(), iRevenueInMonth);
                    }
                }
            }
        }
    }

    private class SSMonthlyDistributionPrinter extends SSPrinter {

        private SSDefaultTableModel<SSMonth> iModel;

        private SSProduct iProduct;
        private LocalDate iFrom;
        private LocalDate iTo;

        private LocalDate iLocalFrom;

        private LocalDate iLocalTo;

        Map<SSMonth, BigDecimal> iMonthRevenue;

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

            setDetail("productrevenue.monthly.jrxml");
            setSummary("productrevenue.monthly.jrxml");

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
                        if (iProduct != null && iMonthRevenue.containsKey(iMonth)) {
                            value = iMonthRevenue.get(iMonth);
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
         * @param pProduct
         * @param iMap
         */
        public void setProduct(SSProduct pProduct, Map<SSMonth, BigDecimal> iMap) {
            iProduct = pProduct;
            iMonthRevenue = iMap;
            if (iMonthRevenue == null) {
                iMonthRevenue = new HashMap<>();
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSProductRevenuePrinter.SSMonthlyDistributionPrinter");
            sb.append("{iFrom=").append(iFrom);
            sb.append(", iModel=").append(iModel);
            sb.append(", iMonthRevenue=").append(iMonthRevenue);
            sb.append(", iProduct=").append(iProduct);
            sb.append(", iTo=").append(iTo);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSProductRevenuePrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iDateFrom=").append(iDateFrom);
        sb.append(", iDateTo=").append(iDateTo);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iProductRevenue=").append(iProductRevenue);
        sb.append(", iProducts=").append(iProducts);
        sb.append('}');
        return sb.toString();
    }
}
