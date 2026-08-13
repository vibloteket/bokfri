package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSDateMath;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSProductMath;
import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;


/**
 * Date: 2007-mar-21
 * Time: 15:32:42
 */
public class SSSaleReportPrinter extends SSPrinter {

    public enum SortingMode {
        Product             (SSBundle.getBundle().getString("salereport.dialog.sort.product")), Period              (SSBundle.getBundle().getString("salereport.dialog.sort.period")), ContributionRate    (SSBundle.getBundle().getString("salereport.dialog.sort.contributionrate")), AverageSellingPrice (SSBundle.getBundle().getString("salereport.dialog.sort.averagesellingprice"));

        private String iDescription;

        SortingMode(String iDescription) {
            this.iDescription = iDescription;
        }

        public String toString() {
            return iDescription;
        }
    }

    private LocalDate   iFrom;
    private LocalDate   iTo;
    private SortingMode iSortingMode;
    private boolean     iAscending;

    private List<SSProduct> iProducts;

    // The number of days in the period
    private Integer iDays;
    // The number of sold products in the period
    private Map<String, BigDecimal> iCount;

    private Map<String, BigDecimal> iContribution;
    private Map<String, BigDecimal> iContributionRate;
    private Map<String, BigDecimal> iAverageSellingPrice;
    private Map<String, BigDecimal> iInprices;

    /**
     *
     * @param iFrom
     * @param iTo
     * @param iSortingMode
     * @param iAscending
     */
    public SSSaleReportPrinter(LocalDate iFrom, LocalDate iTo, SortingMode iSortingMode, boolean iAscending) {
        this.iFrom = iFrom;
        this.iTo = iTo;
        this.iSortingMode = iSortingMode;
        this.iAscending = iAscending;
        iProducts = SSDB.getInstance().getProducts();

        setPageHeader("header_period.jrxml");
        setColumnHeader("salereport.jrxml");
        setDetail("salereport.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("salereport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        addParameter("dateFrom", iFrom);
        addParameter("dateTo", iTo);

        calculate();

        SSDefaultTableModel<SSProduct> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSProduct.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSProduct iProduct = getObject(rowIndex);

                BigDecimal iProductCount = iCount.get(iProduct.getNumber());
                BigDecimal iProductContribution = iContribution.get(iProduct.getNumber());
                BigDecimal iProductContributionRate = iContributionRate.get(
                        iProduct.getNumber());

                switch (columnIndex) {
                case 0:
                    value = iProduct.getNumber();
                    break;

                case 1:
                    value = iProduct.getDescription();
                    break;

                case 2:
                    value = iProductCount;
                    break;

                case 3:
                    if (iDays == null || iDays == 0) {
                        return null;
                    }

                    value = iProductCount.multiply(new BigDecimal("30.436875"))
                            .divide(BigDecimal.valueOf(iDays), MathContext.DECIMAL64);
                    break;

                case 4:
                    if (iDays == null || iDays == 0) {
                        return null;
                    }

                    value = iProductCount.multiply(new BigDecimal("7.00"))
                            .divide(BigDecimal.valueOf(iDays), MathContext.DECIMAL64);
                    break;

                case 5:
                    value = iProduct.getSellingPrice();
                    break;

                case 6:
                    value = iInprices.get(iProduct.getNumber()); // SSProductMath.getInprice(iProduct, iTo);
                    break;

                case 7:
                    value = iProductContribution;
                    break;

                case 8:
                    value = iProductContributionRate;
                    break;

                case 9:
                    if (iProductContribution == null || iProductCount == null) {
                        return null;
                    }

                    value = iProductContribution.multiply(iProductCount);
                    break;

                case 10:
                    value = iAverageSellingPrice.get(iProduct.getNumber());
                    break;

                }

                return value;
            }
        };

        iModel.addColumn("product.number");
        iModel.addColumn("product.description");
        iModel.addColumn("product.count");
        iModel.addColumn("product.monthly");
        iModel.addColumn("product.weekly");

        iModel.addColumn("product.unitprice");
        iModel.addColumn("product.inprice");
        iModel.addColumn("product.contribution");
        iModel.addColumn("product.contributionrate");
        iModel.addColumn("product.grossprofit");
        iModel.addColumn("product.averagesellingprice");

        if (iSortingMode == SortingMode.Product) {
            Collections.sort(iProducts, (iProduct1, iProduct2) -> {

                    String iNumber1 = iProduct1.getNumber();
                    String iNumber2 = iProduct2.getNumber();

                    if (iNumber1 == null || iNumber2 == null) {
                        return 0;
                    }

                    if (iAscending) {
                        return iNumber1.compareTo(iNumber2);
                    } else {
                        return iNumber2.compareTo(iNumber1);
                    }

                });
        }

        if (iSortingMode == SortingMode.Period) {
            Collections.sort(iProducts, (iProduct1, iProduct2) -> {

                    BigDecimal iCount1 = iCount.get(iProduct1.getNumber());
                    BigDecimal iCount2 = iCount.get(iProduct2.getNumber());

                    if (iCount1 == null) {
                        iCount1 = BigDecimal.ZERO;
                    }
                    if (iCount2 == null) {
                        iCount2 = BigDecimal.ZERO;
                    }

                    return iAscending ? iCount1.compareTo(iCount2) : iCount2.compareTo(iCount1);

                });
        }

        if (iSortingMode == SortingMode.ContributionRate) {
            Collections.sort(iProducts,
                    (iProduct1, iProduct2) -> {

                            BigDecimal iContributionRate1 = iContributionRate.get(
                                    iProduct1.getNumber());
                            BigDecimal iContributionRate2 = iContributionRate.get(
                                    iProduct2.getNumber());

                            if (iContributionRate1 == null) {
                                iContributionRate1 = new BigDecimal(0);
                            }
                            if (iContributionRate2 == null) {
                                iContributionRate2 = new BigDecimal(0);
                            }

                            if (iAscending) {
                                return iContributionRate1.compareTo(iContributionRate2);
                            } else {
                                return iContributionRate2.compareTo(iContributionRate1);
                            }


                        });
        }

        // Lite udda. Sorterar på Totalt Täckningsbidrag. Är felbenämt.
        if (iSortingMode == SortingMode.AverageSellingPrice) {
            Collections.sort(iProducts,
                    (iProduct1, iProduct2) -> {

                            BigDecimal iAverageSellingPrice1 = iContribution.get(
                                    iProduct1.getNumber());
                            BigDecimal iAverageSellingPrice2 = iContribution.get(
                                    iProduct2.getNumber());

                            if (iAverageSellingPrice1 == null) {
                                iAverageSellingPrice1 = new BigDecimal(0);
                            }
                            if (iAverageSellingPrice2 == null) {
                                iAverageSellingPrice2 = new BigDecimal(0);
                            }

                            if (iAscending) {
                                return iAverageSellingPrice1.compareTo(iAverageSellingPrice2);
                            } else {
                                return iAverageSellingPrice2.compareTo(iAverageSellingPrice1);
                            }

                        });
        }
        iModel.setObjects(iProducts);

        return iModel;
    }

    /**
     *
     */
    private void calculate() {
        iDays = SSDateMath.getDaysBetween(iFrom, iTo);
        iCount = new HashMap<>();
        iContribution = new HashMap<>();
        iContributionRate = new HashMap<>();
        iAverageSellingPrice = new HashMap<>();
        iInprices = new HashMap<>();

        List<SSSupplierInvoice> iSupplierInvoices = new LinkedList<>(
                SSDB.getInstance().getSupplierInvoices());

        Collections.sort(iSupplierInvoices,
                Comparator.comparing(SSSupplierInvoice::getLocalDate,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        for (SSSupplierInvoice iSupplierInvoice : iSupplierInvoices) {
            if (SSSupplierInvoiceMath.inPeriod(iSupplierInvoice, iTo)) {
                for (SSSupplierInvoiceRow iRow : iSupplierInvoice.getRows()) {
                    if (iRow.getProductNr() != null) {
                        SSProduct iProduct = new SSProduct();

                        iProduct.setNumber(iRow.getProductNr());
                        iProduct = SSDB.getInstance().getProduct(iProduct).orElse(null);

                        if (iProduct == null || iProduct.isParcel()) {
                            continue;
                        }

                        if (!iInprices.containsKey(iProduct.getNumber())) {
                            BigDecimal iUnitPrice = iRow.getUnitprice() == null
                                    ? new BigDecimal(0)
                                    : iRow.getUnitprice();
                            BigDecimal iUnitFreight = iRow.getUnitFreight() == null
                                    ? new BigDecimal(0)
                                    : iRow.getUnitFreight();
                            BigDecimal iInprice = SSSupplierInvoiceMath.convertToLocal(
                                    iSupplierInvoice, iUnitPrice.add(iUnitFreight));

                            iInprices.put(iProduct.getNumber(), iInprice);
                        }
                    }
                }
            }
        }

        List<SSInvoice> iInvoices = SSDB.getInstance().getInvoices();

        for (SSInvoice iInvoice : iInvoices) {
            if (SSInvoiceMath.inPeriod(iInvoice, iFrom, iTo)) {
                for (SSSaleRow iRow : iInvoice.getRows()) {
                    if (iRow.getProductNr() != null) {
                        SSProduct iProduct = SSDB.getInstance().getProduct(
                                iRow.getProductNr()).orElse(null);

                        if (iProduct == null || iRow.getQuantity() == null) {
                            continue;
                        }
                        if (iRow.getSum().isPresent()) {
                            if (iAverageSellingPrice.containsKey(iProduct.getNumber())) {
                                iAverageSellingPrice.put(iProduct.getNumber(),
                                        iAverageSellingPrice.get(iProduct.getNumber()).add(
                                        SSInvoiceMath.convertToLocal(iInvoice,
                                        iRow.getSum().get())));
                            } else {
                                iAverageSellingPrice.put(iProduct.getNumber(),
                                        SSInvoiceMath.convertToLocal(iInvoice,
                                        iRow.getSum().get()));
                            }
                        }

                        if (iCount.containsKey(iProduct.getNumber())) {
                            iCount.put(iProduct.getNumber(),
                                    iCount.get(iProduct.getNumber()).add(iRow.getQuantity()));
                        } else {
                            iCount.put(iProduct.getNumber(), iRow.getQuantity());
                        }
                    }
                }
            }
        }

        List<SSCreditInvoice> iCreditInvoices = SSDB.getInstance().getCreditInvoices();

        for (SSCreditInvoice iCreditInvoice : iCreditInvoices) {
            if (SSInvoiceMath.inPeriod(iCreditInvoice, iFrom, iTo)) {
                for (SSSaleRow iRow : iCreditInvoice.getRows()) {
                    if (iRow.getProductNr() != null) {
                        SSProduct iProduct = SSDB.getInstance().getProduct(
                                iRow.getProductNr()).orElse(null);

                        if (iProduct == null || iRow.getQuantity() == null) {
                            continue;
                        }

                        if (iRow.getSum().isPresent()) {
                            if (iAverageSellingPrice.containsKey(iProduct.getNumber())) {
                                iAverageSellingPrice.put(iProduct.getNumber(),
                                        iAverageSellingPrice.get(iProduct.getNumber()).subtract(
                                        iRow.getSum().get()));
                            } else {
                                iAverageSellingPrice.put(iProduct.getNumber(),
                                        iRow.getSum().get().negate());
                            }
                        }

                        if (iCount.containsKey(iProduct.getNumber())) {
                            iCount.put(iProduct.getNumber(),
                                    iCount.get(iProduct.getNumber()).subtract(iRow.getQuantity()));
                        } else {
                            iCount.put(iProduct.getNumber(), iRow.getQuantity().negate());
                        }
                    }
                }
            }
        }

        for (SSProduct iProduct : iProducts) {

            if (iProduct.isParcel()) {
                BigDecimal iInpriceSum = new BigDecimal(0);

                for (SSProductRow iRow : iProduct.getParcelRows()) {
                    SSProduct iRowProduct = SSDB.getInstance().getProduct(
                            iRow.getProductNr()).orElse(null);

                    // Undvik inf loop
                    if (iRowProduct == null || iProduct.equals(iRowProduct)) {
                        continue;
                    }

                    Integer iQuantity = iRow.getQuantity();

                    if (iRowProduct == null || iQuantity == null) {
                        continue;
                    }

                    BigDecimal iInprice = iInprices.get(iRowProduct.getNumber());

                    // Om endast en rad saknar inpris så kan vi inte räkna ut något inpris
                    if (iInprice == null) {
                        break;
                    }
                    iInpriceSum = iInpriceSum.add(
                            iInprice.multiply(new BigDecimal(iQuantity)));
                }
                if (!iInprices.containsKey(iProduct.getNumber())) {
                    iInprices.put(iProduct.getNumber(), iInpriceSum);
                }
            }

            if (!iInprices.containsKey(iProduct.getNumber())) {
                iInprices.put(iProduct.getNumber(), iProduct.getStockPrice());
            }
            if (!iCount.containsKey(iProduct.getNumber())) {
                iCount.put(iProduct.getNumber(), BigDecimal.ZERO);
            }

            if (iAverageSellingPrice.containsKey(iProduct.getNumber())
                    && iCount.containsKey(iProduct.getNumber())) {
                BigDecimal iSoldCount = iCount.get(iProduct.getNumber());
                BigDecimal iTotalSellingPrice = iAverageSellingPrice.get(
                        iProduct.getNumber());

                if (iSoldCount == null || iSoldCount.signum() == 0) {
                    iAverageSellingPrice.put(iProduct.getNumber(),
                            iProduct.getSellingPrice());
                } else {
                    BigDecimal iAverage = iTotalSellingPrice.divide(iSoldCount, new MathContext(10)).setScale(
                            2, RoundingMode.HALF_UP);

                    iAverageSellingPrice.put(iProduct.getNumber(), iAverage);
                }
            } else {
                iAverageSellingPrice.put(iProduct.getNumber(), iProduct.getSellingPrice());
            }
            if (iProduct.getSellingPrice() == null
                    || iInprices.get(iProduct.getNumber()) == null) {
                iContribution.put(iProduct.getNumber(), null);
            } else {
                iContribution.put(iProduct.getNumber(),
                        iProduct.getSellingPrice().subtract(
                        iInprices.get(iProduct.getNumber())));
            }

            iContributionRate.put(iProduct.getNumber(),
                    SSProductMath.getContributionRate(iProduct, iTo,
                    iContribution.get(iProduct.getNumber())).orElse(null));
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSSaleReportPrinter");
        sb.append("{iAscending=").append(iAscending);
        sb.append(", iAverageSellingPrice=").append(iAverageSellingPrice);
        sb.append(", iContribution=").append(iContribution);
        sb.append(", iContributionRate=").append(iContributionRate);
        sb.append(", iCount=").append(iCount);
        sb.append(", iDays=").append(iDays);
        sb.append(", iFrom=").append(iFrom);
        sb.append(", iInprices=").append(iInprices);
        sb.append(", iProducts=").append(iProducts);
        sb.append(", iSortingMode=").append(iSortingMode);
        sb.append(", iTo=").append(iTo);
        sb.append('}');
        return sb.toString();
    }
}
