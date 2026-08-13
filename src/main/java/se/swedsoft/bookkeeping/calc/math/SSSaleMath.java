package se.swedsoft.bookkeeping.calc.math;


import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSale;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.customer.SSCustomerFrame;
import se.swedsoft.bookkeeping.gui.product.SSProductFrame;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.Optional;


/**
 * User: Andreas Lago
 * Date: 2006-mar-27
 * Time: 15:42:39
 */
public class SSSaleMath {

    public static boolean inPeriod(SSSale iSale, LocalDate iFrom, LocalDate iTo) {
        LocalDate iDate = iSale.getLocalDate();

        return iDate != null && iFrom != null && iTo != null
                && !iDate.isBefore(iFrom) && !iDate.isAfter(iTo);
    }

    public static boolean inPeriod(SSSale iSale, LocalDate iTo) {
        LocalDate iDate = iSale.getLocalDate();

        return iDate != null && iTo != null && !iDate.isAfter(iTo);
    }

    /**
     * Rounds a value to whole kronor and returns both the value and the rounding sum
     *
     * @param iValue
     * @return the rounded value and the rounding
     */
    public static BigDecimal[] round(BigDecimal iValue) {
        BigDecimal iRounded = iValue.setScale(0, RoundingMode.HALF_UP);
        BigDecimal iRounding = iValue.subtract(iRounded);

        return new BigDecimal[] { iRounded, iRounding };
    }

    /**
     * Returns the net sum for the specified sale
     *
     * @param iSale
     * @return the sum
     */
    public static BigDecimal getNetSum(SSSale iSale) {

        BigDecimal iNetSum = new BigDecimal(0);

        for (SSSaleRow iRow: iSale.getRows()) {
            BigDecimal iRowSum = iRow.getSum().orElse(null);

            if (iRowSum != null) {
                iNetSum = iNetSum.add(iRowSum);
            }
        }

        return iNetSum;
    }

    /**
     * Get the tax sum for the specified sale, {0,0,0} if tax free
     *
     * @param iSale
     *
     * @return the sum for the tax codes
     */
    public static Map<SSTaxCode, BigDecimal> getTaxSum(SSSale iSale) {
        BigDecimal iTaxRate1 = iSale.getNormalizedTaxRate1();
        BigDecimal iTaxRate2 = iSale.getNormalizedTaxRate2();
        BigDecimal iTaxRate3 = iSale.getNormalizedTaxRate3();

        Map<SSTaxCode, BigDecimal> iTaxSum = new HashMap<>();

        iTaxSum.put(SSTaxCode.TAXRATE_0, new BigDecimal(0));
        iTaxSum.put(SSTaxCode.TAXRATE_1, new BigDecimal(0));
        iTaxSum.put(SSTaxCode.TAXRATE_2, new BigDecimal(0));
        iTaxSum.put(SSTaxCode.TAXRATE_3, new BigDecimal(0));

        if (iSale.getTaxFree()) {
            return iTaxSum;
        }

        for (SSSaleRow iRow: iSale.getRows()) {
            BigDecimal iRowSum = iRow.getSum().orElse(null);
            SSTaxCode  iRowTax = iRow.getTaxCode();

            if (iRowSum != null) {
                BigDecimal iSum = iTaxSum.get(iRowTax);

                if (iRowTax == SSTaxCode.TAXRATE_1) {
                    iSum = iSum.add(iRowSum.multiply(iTaxRate1));
                }
                if (iRowTax == SSTaxCode.TAXRATE_2) {
                    iSum = iSum.add(iRowSum.multiply(iTaxRate2));
                }
                if (iRowTax == SSTaxCode.TAXRATE_3) {
                    iSum = iSum.add(iRowSum.multiply(iTaxRate3));
                }

                iTaxSum.put(iRowTax, iSum);
            }
        }

        return iTaxSum;
    }

    /**
     * Get the tax sum for the specified sale, 0 if tax free
     *
     * @param iSale
     *
     * @return the sum
     */
    public static BigDecimal getTotalTaxSum(SSSale iSale) {
        Map<SSTaxCode, BigDecimal> iTaxSum = getTaxSum(iSale);

        BigDecimal iTax1 = iTaxSum.get(SSTaxCode.TAXRATE_1);
        BigDecimal iTax2 = iTaxSum.get(SSTaxCode.TAXRATE_2);
        BigDecimal iTax3 = iTaxSum.get(SSTaxCode.TAXRATE_3);

        return iTax1.add(iTax2).add(iTax3);
    }

    /**
     * Returns the rounded total sum for a sale
     *
     * if the tender is tax free this will return the same as getNetSum()
     *
     * @param iSale
     * @return the total sum
     */
    public static BigDecimal getTotalSum(SSSale iSale) {
        // Get the tax sum
        Map<SSTaxCode, BigDecimal> iTaxSum = getTaxSum(iSale);

        BigDecimal iNetSum = getNetSum(iSale);
        BigDecimal iTaxSum1 = iTaxSum.get(SSTaxCode.TAXRATE_1);
        BigDecimal iTaxSum2 = iTaxSum.get(SSTaxCode.TAXRATE_2);
        BigDecimal iTaxSum3 = iTaxSum.get(SSTaxCode.TAXRATE_3);

        BigDecimal iSum;

        if (iSale.getTaxFree()) {
            iSum = iNetSum;
        } else {
            iSum = iNetSum.add(iTaxSum1).add(iTaxSum2).add(iTaxSum3);
        }
        if (!SSDB.getInstance().getCurrentCompany().isRoundingOff()) {
            return iSum.setScale(0, RoundingMode.HALF_UP);
        } else {
            return iSum;
        }
    }

    /**
     * Returns the rounding for a sale
     *
     * if the tender is tax free this will return the same as getNetSum()
     *
     * @param iSale
     * @return the total sum
     */
    public static BigDecimal getRounding(SSSale iSale) {
        // Get the tax sum
        Map<SSTaxCode, BigDecimal> iTaxSum = getTaxSum(iSale);

        BigDecimal iNetSum = getNetSum(iSale);
        BigDecimal iTaxSum1 = iTaxSum.get(SSTaxCode.TAXRATE_1);
        BigDecimal iTaxSum2 = iTaxSum.get(SSTaxCode.TAXRATE_2);
        BigDecimal iTaxSum3 = iTaxSum.get(SSTaxCode.TAXRATE_3);

        BigDecimal iSum;

        if (iSale.getTaxFree()) {
            iSum = iNetSum;
        } else {
            iSum = iNetSum.add(iTaxSum1).add(iTaxSum2).add(iTaxSum3);
        }
        if (!SSDB.getInstance().getCurrentCompany().isRoundingOff()) {
            return iSum.setScale(0, RoundingMode.HALF_UP).subtract(iSum);
        } else {
            return new BigDecimal(0);
        }
        // return iSum.subtract( iSum.setScale(0, RoundingMode.HALF_UP) );
    }

    /**
     *
     * @param iSale
     * @return the new customer
     */
    public static Optional<SSCustomer> getNewCustomer(SSSale iSale) {
        if (iSale.getCustomer(SSDB.getInstance().getCustomers()) == null) {
            SSCustomer iCustomer = new SSCustomer();

            iCustomer.setNumber(iSale.getCustomerNr());
            iCustomer.setName(iSale.getCustomerName());
            iCustomer.setYourContactPerson(iSale.getYourContactPerson());
            iCustomer.setOurContactPerson(iSale.getOurContactPerson());
            iCustomer.setPaymentTerm(iSale.getPaymentTerm());
            iCustomer.setDeliveryTerm(iSale.getDeliveryTerm());
            iCustomer.setDeliveryWay(iSale.getDeliveryWay());
            iCustomer.setInvoiceCurrency(iSale.getCurrency());
            iCustomer.setTaxFree(iSale.getTaxFree());
            iCustomer.setEuSaleCommodity(iSale.getEuSaleCommodity());
            iCustomer.setEuSaleYhirdPartCommodity(iSale.getEuSaleThirdPartCommodity());
            iCustomer.setInvoiceAddress(new SSAddress(iSale.getInvoiceAddress()));
            iCustomer.setDeliveryAddress(new SSAddress(iSale.getDeliveryAddress()));

            return Optional.of(iCustomer);
        }
        return Optional.empty();
    }

    /**
     *
     * @param iSale
     * @return the new products for the tender
     */
    public static List<SSProduct> getNewProducts(SSSale iSale) {
        List<SSProduct> iProducts = new LinkedList<>();

        for (SSSaleRow iTenderRow: iSale.getRows()) {

            SSProduct iProduct = iTenderRow.getProduct(SSDB.getInstance().getProducts());

            // Get the product nr, trim any spaces
            String iProductNr = iTenderRow.getProductNr() == null
                    ? ""
                    : iTenderRow.getProductNr().trim();

            // This is a new product
            if (iProduct == null && iProductNr.length() > 0
                    && SSProductMath.getProduct(iProducts, iProductNr).isEmpty()) {
                iProduct = new SSProduct();

                iProduct.setNumber(iProductNr);
                iProduct.setDescription(iTenderRow.getDescription());
                iProduct.setSellingPrice(iTenderRow.getUnitprice());
                iProduct.setUnit(iTenderRow.getUnit());
                iProduct.setTaxCode(iTenderRow.getTaxCode());

                iProducts.add(iProduct);
            }
        }
        return iProducts;

    }

    /**
     *
     * @param iSale
     * @param iProduct
     * @return
     */
    public static Integer getProductCount(SSSale iSale, SSProduct iProduct) {
        Integer iCount = 0;

        for (SSSaleRow iRow : iSale.getRows()) {
            SSProduct iRowProduct = iRow.getProduct();

            // Skip if no product or no quantity
            if (iRowProduct == null || iRow.getQuantity() == null) {
                continue;
            }

            // This is the product we want to get the quantity for
            if (iRowProduct.equals(iProduct)) {
                iCount = iCount + iRow.getQuantity().intValueExact();
            }
            // Get the quantity if this is a parcel product
            if (iRowProduct.isParcel()) {
                Integer iQuantity = SSProductMath.getProductCount(iRowProduct, iProduct);

                iCount = iCount + iRow.getQuantity().intValueExact() * iQuantity;
            }

        }
        return iCount;
    }

    /**
     * Adds any new customer and all new products for the tender to the database
     *
     * @param iSale
     */
    public static void addCustomerAndProducts(SSSale iSale) {
        Optional<SSCustomer> iCustomer = getNewCustomer(iSale);

        if (iCustomer.isPresent()) {
            SSDB.getInstance().addCustomer(iCustomer.get());
        }

        List<SSProduct> iProducts = getNewProducts(iSale);

        for (SSProduct iProduct : iProducts) {
            SSDB.getInstance().addProduct(iProduct);

        }

        if (SSCustomerFrame.getInstance() != null) {
            SSCustomerFrame.getInstance().getModel().fireTableDataChanged();
        }
        if (SSProductFrame.getInstance() != null) {
            SSProductFrame.getInstance().getModel().fireTableDataChanged();
        }
    }

    /**
     * Returns true if the sale contains a product with the product nr specified
     *
     * @param iSale
     * @param iProductNr
     * @return if the sale contains the product
     */
    public static boolean containsProduct(SSSale iSale, String iProductNr) {

        for (SSSaleRow iRow : iSale.getRows()) {
            String iRowProductNr = iRow.getProductNr();

            if (iRowProductNr != null && iRowProductNr.equals(iProductNr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all the rows in this sale for the specifed product
     *
     * @param iSale the sale
     * @param iProduct the product
     * @return the rows for the product
     */
    public static List<SSSaleRow> getRowsForProduct(SSSale iSale, SSProduct iProduct) {
        List<SSSaleRow> iRows = new LinkedList<>();

        for (SSSaleRow iRow : iSale.getRows()) {
            if (iRow.hasProduct(iProduct)) {
                iRows.add(iRow);
            }
        }
        return iRows;
    }

    /**
     *
     * @param iSale
     * @param iRow
     * @return
     */
    public static Optional<SSSaleRow> getMatchingRow(SSSale iSale, SSSaleRow iRow) {

        String iProductNr = iRow.getProductNr();

        if (iProductNr == null) {
            return Optional.empty();
        }

        for (SSSaleRow iCurrent : iSale.getRows()) {

            if (iProductNr.equals(iCurrent.getProductNr())) {
                return Optional.of(iCurrent);
            }
        }
        return Optional.empty();
    }

    /**
     *
     * @param iSale
     * @return
     */
    public static boolean hasDiscount(SSSale iSale) {
        for (SSSaleRow iRow : iSale.getRows()) {
            if (iRow.getDiscount() != null) {
                return true;
            }
        }
        return false;
    }
}
