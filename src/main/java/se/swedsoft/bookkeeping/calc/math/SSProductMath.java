package se.swedsoft.bookkeeping.calc.math;


import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * User: Andreas Lago
 * Date: 2006-jul-24
 * Time: 11:40:06
 */
public class SSProductMath {
    private SSProductMath() {}

    /**
     * Gets the product with the specific nr from the list, if any
     *
     * @param iProducts
     * @param iProductNr
     * @return
     */
    public static Optional<SSProduct> getProduct(List<SSProduct> iProducts, String iProductNr) {
        for (SSProduct iCurrent: iProducts) {
            String iNumber = iCurrent.getNumber();

            if (iNumber != null && iNumber.equals(iProductNr)) {
                return Optional.of(iCurrent);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the products that isnt a parcel
     *
     * @return
     */
    public static List<SSProduct> getNormalProducts() {
        List<SSProduct> iProducts = SSDB.getInstance().getProducts();
        List<SSProduct> iFiltered = new LinkedList<>();

        for (SSProduct iProduct : iProducts) {
            if (!iProduct.isParcel()) {
                iFiltered.add(iProduct);
            }
        }
        return iFiltered;
    }

    /**
     *
     * @param iProduct
     * @param iDate
     * @return
     */
    public static BigDecimal getLastPurchasePrice(SSProduct iProduct, LocalDate iDate) {
        List<SSSupplierInvoice> iSupplierInvoices = new LinkedList<>(
                SSDB.getInstance().getSupplierInvoices());

        Collections.sort(iSupplierInvoices,
                Comparator.comparing(SSSupplierInvoice::getLocalDate,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        for (SSSupplierInvoice iSupplierInvoice : iSupplierInvoices) {
            if (iDate != null) {
                LocalDate supplierInvoiceDate = iSupplierInvoice.getLocalDate();
                if (supplierInvoiceDate != null && supplierInvoiceDate.isAfter(iDate)) {
                    continue;
                }
            }
            List<SSSupplierInvoiceRow> iRows = iSupplierInvoice.getRows();

            for (SSSupplierInvoiceRow iRow : iRows) {
                if (iRow.hasProduct(iProduct)) {
                    return iRow.getUnitprice();
                }
            }
        }

        return iProduct.getPurchasePrice();
    }

    /**
     *
     * @param iProducts
     * @return
     */
    public static List<SSProduct> getStockProducts(List<SSProduct> iProducts) {
        List<SSProduct> iFiltered = new LinkedList<>();

        for (SSProduct iProduct : iProducts) {

            if (iProduct.isParcel()) {
                continue;
            }

            if (iProduct.isStockProduct()) {
                iFiltered.add(iProduct);
            }
        }
        return iFiltered;
    }

    /**
     *
     * @param iParcel
     * @param iProduct
     * @return
     */
    public static Integer getProductCount(SSProduct iParcel, SSProduct iProduct) {
        Integer iCount = 0;

        for (SSProductRow iRow : iParcel.getParcelRows()) {
            if (iRow.hasProduct(iProduct)) {

                Integer iQuantity = iRow.getQuantity();

                if (iQuantity != null) {
                    iCount = iCount + iQuantity;
                }
            }

        }
        return iCount;
    }

    /**
     *
     * @param iProducts
     * @return
     */
    public static Map<SSProduct, BigDecimal> getInprices(List<SSProduct> iProducts) {
        Map<SSProduct, BigDecimal> IInprices = new HashMap<>();

        for (SSProduct iProduct : iProducts) {
            IInprices.put(iProduct, getInprice(iProduct).orElse(null));
        }
        return IInprices;
    }

    /**
     *
     * @param iProducts
     * @param iDate
     * @return
     */
    public static Map<SSProduct, BigDecimal> getInprices(List<SSProduct> iProducts, LocalDate iDate) {
        Map<SSProduct, BigDecimal> IInprices = new HashMap<>();

        for (SSProduct iProduct : iProducts) {
            IInprices.put(iProduct, getInprice(iProduct, iDate).orElse(null));
        }
        return IInprices;
    }

    /**
     *
     * @param iProduct
     * @return
     */
    public static Optional<BigDecimal> getInprice(SSProduct iProduct) {
        return getInprice(iProduct, (LocalDate) null);
    }

    /**
     *
     * @param iProduct
     * @param iDate
     * @return
     */
    public static Optional<BigDecimal> getInprice(SSProduct iProduct, LocalDate iDate) {
        // Paket produkt
        if (iProduct.isParcel()) {

            BigDecimal iInpriceSum = new BigDecimal(0);

            for (SSProductRow iRow : iProduct.getParcelRows()) {
                SSProduct iRowProduct = iRow.getProduct();

                // Undvik inf loop
                if (iProduct.equals(iRowProduct)) {
                    continue;
                }

                Integer iQuantity = iRow.getQuantity();

                // Lägg till inpriset för produkten multiplicerat med antalet
                if (iRowProduct == null || iQuantity == null) {
                    continue;
                }

                Optional<BigDecimal> iInprice = getInprice(iRowProduct, iDate);

                // Om endast en rad saknar inpris så kan vi inte räkna ut något inpris
                if (iInprice.isEmpty()) {
                    return Optional.empty();
                }

                iInpriceSum = iInpriceSum.add(iInprice.get().multiply(new BigDecimal(iQuantity)));

            }
            return Optional.of(iInpriceSum);
        }
        List<SSSupplierInvoice> iSupplierInvoices = SSDB.getInstance().getSupplierInvoices();

        List<SSSupplierInvoice> iFiltered = new LinkedList<>(
                iSupplierInvoices);

        // Sortera efter datum i fallande ordning
        Collections.sort(iFiltered,
                Comparator.comparing(SSSupplierInvoice::getLocalDate,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        for (SSSupplierInvoice iSupplierInvoice : iFiltered) {

            if (iDate == null || SSSupplierInvoiceMath.inPeriod(iSupplierInvoice, iDate)) {

                List<SSSupplierInvoiceRow> iRows = iSupplierInvoice.getRows();

                for (SSSupplierInvoiceRow iRow : iRows) {
                    if (iRow.hasProduct(iProduct)) {

                        BigDecimal iUnitPrice = iRow.getUnitprice();
                        BigDecimal iUnitFreight = iRow.getUnitFreight();

                        if (iUnitPrice == null) {
                            continue;
                        }

                        BigDecimal iValue;

                        if (iUnitFreight != null) {
                            iValue = iUnitPrice.add(iUnitFreight);
                        } else {
                            iValue = iUnitPrice;
                        }
                        BigDecimal iLocalValue = SSSupplierInvoiceMath.convertToLocal(
                                iSupplierInvoice, iValue);

                        return Optional.ofNullable(iLocalValue == null
                                ? iProduct.getStockPrice()
                                : iLocalValue);
                    }
                }
            }
        }
        return Optional.ofNullable(iProduct.getStockPrice());
    }

    /**
     *
     * @param iProduct
     * @return
     */
    public static Optional<BigDecimal> getContribution(SSProduct iProduct) {
        Optional<BigDecimal> iInprice = getInprice(iProduct);

        if (iInprice.isEmpty() || iProduct.getSellingPrice() == null) {
            return Optional.empty();
        }

        // Enhetspros - Senaste inpris
        return Optional.of(iProduct.getSellingPrice().subtract(iInprice.get()));
    }

    /**
     *
     * @param iProduct
     * @param iDate
     * @return
     */
    public static Optional<BigDecimal> getContribution(SSProduct iProduct, LocalDate iDate) {
        Optional<BigDecimal> iInprice = getInprice(iProduct, iDate);

        if (iInprice.isEmpty() || iProduct.getSellingPrice() == null) {
            return Optional.empty();
        }

        // Enhetspros - Senaste inpris
        return Optional.of(iProduct.getSellingPrice().subtract(iInprice.get()));
    }

    /**
     *
     * @param iProduct
     * @return
     */
    public static Optional<BigDecimal> getContributionRate(SSProduct iProduct) {
        Optional<BigDecimal> iContribution = getContribution(iProduct);
        BigDecimal iSellingPrice = iProduct.getSellingPrice();

        if (iContribution.isEmpty() || iSellingPrice == null) {
            return Optional.empty();
        }

        if (iSellingPrice.signum() == 0) {
            return Optional.empty();
        }

        // TB / Enhetspris
        return Optional.of(iContribution.get().divide(iSellingPrice, 20, RoundingMode.HALF_UP).scaleByPowerOfTen(
                2));
    }

    /**
     *
     * @param iProduct
     * @param iDate
     * @return
     */
    public static Optional<BigDecimal> getContributionRate(SSProduct iProduct, LocalDate iDate) {
        Optional<BigDecimal> iContribution = getContribution(iProduct, iDate);
        BigDecimal iSellingPrice = iProduct.getSellingPrice();

        if (iContribution.isEmpty() || iSellingPrice == null) {
            return Optional.empty();
        }

        if (iSellingPrice.signum() == 0) {
            return Optional.empty();
        }

        // TB / Enhetspris
        return Optional.of(iContribution.get().divide(iSellingPrice, 20, RoundingMode.HALF_UP).scaleByPowerOfTen(
                2));
    }

    public static Optional<BigDecimal> getContributionRate(SSProduct iProduct, LocalDate iDate, BigDecimal iContribution) {
        // BigDecimal iContribution = getContribution(iProduct, iDate);
        BigDecimal iSellingPrice = iProduct.getSellingPrice();

        if (iContribution == null || iSellingPrice == null) {
            return Optional.empty();
        }

        if (iSellingPrice.signum() == 0) {
            return Optional.empty();
        }

        // TB / Enhetspris
        return Optional.of(iContribution.divide(iSellingPrice, 20, RoundingMode.HALF_UP).scaleByPowerOfTen(
                2));
    }

    /**
     *
     * @param iProduct
     * @return
     */
    public static Integer getSaleCount(SSProduct iProduct) {
        return getSaleCount(iProduct, (LocalDate) null, null);
    }

    /**
     *
     * @param iProduct
     * @param iFrom
     * @param iTo
     * @return
     */
    public static Integer getSaleCount(SSProduct iProduct, LocalDate iFrom, LocalDate iTo) {
        List<SSInvoice>       iInvoices = SSDB.getInstance().getInvoices();
        List<SSCreditInvoice> iCreditInvoices = SSDB.getInstance().getCreditInvoices();

        Integer iSaleCount = 0;

        for (SSInvoice iInvoice : iInvoices) {

            if (SSInvoiceMath.inPeriod(iInvoice, iFrom, iTo)) {
                Integer iCount = SSInvoiceMath.getProductCount(iInvoice, iProduct);

                if (iCount != null) {
                    iSaleCount += iCount;
                }
            }
        }

        for (SSCreditInvoice iCreditInvoice : iCreditInvoices) {
            if (SSCreditInvoiceMath.inPeriod(iCreditInvoice, iFrom, iTo)) {
                Integer iCount = SSCreditInvoiceMath.getProductCount(iCreditInvoice,
                        iProduct);

                if (iCount != null) {
                    iSaleCount -= iCount;
                }
            }
        }
        return iSaleCount;
    }

    /**
     *
     * @param iProduct
     * @return
     */
    public static BigDecimal getAverageSellingPrice(SSProduct iProduct) {
        return getAverageSellingPrice(iProduct, (LocalDate) null, null);
    }

    /**
     *
     * @param iProduct
     * @param iFrom
     * @param iTo
     * @return
     */
    public static BigDecimal getAverageSellingPrice(SSProduct iProduct, LocalDate iFrom, LocalDate iTo) {
        List<SSInvoice>       iInvoices = SSDB.getInstance().getInvoices();
        List<SSCreditInvoice> iCreditInvoices = SSDB.getInstance().getCreditInvoices();

        BigDecimal iSum = new BigDecimal(0);
        BigDecimal iCount = BigDecimal.ZERO;

        for (SSInvoice iInvoice : iInvoices) {
            if (iFrom == null || iTo == null
                    || SSInvoiceMath.inPeriod(iInvoice, iFrom, iTo)) {

                List<SSSaleRow> iRows = SSInvoiceMath.getRowsForProduct(iInvoice, iProduct);

                for (SSSaleRow iRow : iRows) {
                    BigDecimal iQuantity = iRow.getQuantity();
                    BigDecimal iUnitprice = iRow.getUnitprice();
                    BigDecimal iDiscount = iRow.getNormalizedDiscount();

                    if (iQuantity == null || iUnitprice == null) {
                        continue;
                    }

                    BigDecimal iValue = iUnitprice.multiply(iQuantity);

                    if (iDiscount != null) {
                        iValue = iValue.subtract(iValue.multiply(iDiscount));
                    }

                    iValue = SSInvoiceMath.convertToLocal(iInvoice, iValue);

                    iSum = iSum.add(iValue);

                    iCount = iCount.add(iQuantity);
                }

            }
        }
        for (SSCreditInvoice iCreditInvoice : iCreditInvoices) {
            if (iFrom == null || iTo == null
                    || SSCreditInvoiceMath.inPeriod(iCreditInvoice, iFrom, iTo)) {

                List<SSSaleRow> iRows = SSCreditInvoiceMath.getRowsForProduct(
                        iCreditInvoice, iProduct);

                for (SSSaleRow iRow : iRows) {
                    BigDecimal iQuantity = iRow.getQuantity();
                    BigDecimal iUnitprice = iRow.getUnitprice();
                    BigDecimal iDiscount = iRow.getNormalizedDiscount();

                    if (iQuantity == null || iUnitprice == null) {
                        continue;
                    }

                    BigDecimal iValue = iUnitprice.multiply(iQuantity);

                    if (iDiscount != null) {
                        iValue = iValue.subtract(iValue.multiply(iDiscount));
                    }

                    iValue = SSCreditInvoiceMath.convertToLocal(iCreditInvoice, iValue);

                    iSum = iSum.subtract(iValue);

                    iCount = iCount.subtract(iQuantity);
                }
            }
        }

        if (iCount.signum() == 0) {
            return iProduct.getSellingPrice();
        }

        if (iSum.signum() == 0) {
            return iSum;
        }

        return iSum.divide(iCount, 20, RoundingMode.HALF_UP);
    }

}
