package se.swedsoft.bookkeeping.calc.math;


import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * User: Andreas Lago
 * Date: 2006-mar-27
 * Time: 15:42:39
 */
public class SSOrderMath extends SSTenderMath {

    /**
     * Returns all the customers for the selected orders
     *
     * @param iOrders
     * @param iCustomerNr
     * @return
     */
    public static List<SSOrder> getOrdersByCustomerNr(List<SSOrder> iOrders, String iCustomerNr) {

        return iOrders.stream()
                .filter(iOrder -> iCustomerNr.equals(iOrder.getCustomerNr()))
                .collect(Collectors.toList());
    }

    /**
     * Returns all the orders without any invoice
     *
     * @param iOrders
     * @return
     */
    public static List<SSOrder> getOrdersWithoutInvoice(List<SSOrder> iOrders) {

        return iOrders.stream()
                .filter(iOrder -> !iOrder.hasInvoice())
                .collect(Collectors.toList());
    }

    /**
     * Removes all references to the selected sales from the sales list
     * @param iInvoice
     */
    public static void removeReference(SSInvoice iInvoice) {
        removeInvoice(SSDB.getInstance().getOrders(), iInvoice);
    }

    /**
     * Removes all references to the selected sales from the sales list
     *
     * @param iOrders
     * @param iInvoice
     */
    public static void removeInvoice(List<SSOrder> iOrders, SSInvoice iInvoice) {

        for (SSOrder iOrder : iOrders) {
            if (iOrder.hasInvoice(iInvoice)) {
                iOrder.setInvoice(null);
                SSDB.getInstance().updateOrder(iOrder);
            }
        }
    }

    /**
     *
     * @param iOrder
     * @return
     */
    public static Map<SSProduct, Integer> getProductCount(SSOrder iOrder) {
        List<SSProduct> iProducts = SSDB.getInstance().getProducts();

        Map<SSProduct, Integer> iProductCount = new HashMap<>();

        for (SSSaleRow iRow : iOrder.getRows()) {
            // Get the product for the row
            SSProduct iProduct = iRow.getProduct(iProducts);

            if (iProduct != null) {
                Integer iCount = iRow.getQuantity().intValueExact();

                Integer iTotal = iProductCount.get(iProduct);

                if (iTotal == null) {
                    iProductCount.put(iProduct, iCount);
                } else {
                    iProductCount.put(iProduct, iCount + iTotal);
                }

            }
        }

        return iProductCount;
    }

    /**
     *
     * @param iOrders
     * @return
     */
    public static Map<SSProduct, Integer> getProductCount(List<SSOrder> iOrders) {

        Map<SSProduct, Integer> iProductCount = new HashMap<>();

        for (SSOrder iOrder : iOrders) {
            Map<SSProduct, Integer> iCounts = getProductCount(iOrder);

            for (Map.Entry<SSProduct, Integer> iEntry : iCounts.entrySet()) {
                SSProduct iProduct = iEntry.getKey();
                Integer   iValue = iEntry.getValue();

                Integer iTotal = iProductCount.containsKey(iProduct)
                        ? iProductCount.get(iProduct)
                        : 0;

                iProductCount.put(iProduct, iTotal + iValue);
            }

        }
        return iProductCount;
    }

    public static Map<String, Integer> getStockInfluencing(List<SSOrder> iOrders) {
        Map<String, Integer> iOrderCount = new HashMap<>();
        List<String> iParcelProducts = new LinkedList<>();
        List<SSProduct> iProducts = new LinkedList<>(
                SSDB.getInstance().getProducts());

        for (SSProduct iProduct : iProducts) {
            if (iProduct.isParcel() && iProduct.getNumber() != null) {
                iParcelProducts.add(iProduct.getNumber());
            }
        }
        for (SSOrder iOrder : iOrders) {
            for (SSSaleRow iRow : iOrder.getRows()) {
                if (iRow.getQuantity() == null) {
                    continue;
                }
                Integer iReserved;

                if (iParcelProducts.contains(iRow.getProductNr())) {
                    SSProduct iProduct = iRow.getProduct();

                    if (iProduct != null) {
                        for (SSProductRow iProductRow : iProduct.getParcelRows()) {
                            iReserved = iOrderCount.get(iProductRow.getProductNr())
                                    == null
                                            ? iProductRow.getQuantity()
                                                    * iRow.getQuantity().intValueExact()
                                                    : iOrderCount.get(
                                                            iProductRow.getProductNr())
                                                                    + (iProductRow.getQuantity()
                                                                            * iRow.getQuantity().intValueExact());
                            iOrderCount.put(iProductRow.getProductNr(), iReserved);
                        }
                    }
                } else {
                    iReserved = iOrderCount.get(iRow.getProductNr()) == null
                            ? iRow.getQuantity().intValueExact()
                            : iOrderCount.get(iRow.getProductNr())
                                    + iRow.getQuantity().intValueExact();
                    iOrderCount.put(iRow.getProductNr(), iReserved);
                }
            }
        }
        return iOrderCount;
    }

    public static HashMap<Integer, String> iInvoiceForOrders;

    public static void setInvoiceForOrders() {/* if(iInvoiceForOrders == null) iInvoiceForOrders = new HashMap<>();

         List<SSOrder> iOrders = SSDB.getInstance().getOrders();

         for(SSOrder iOrder : iOrders){
         SSInvoice iInvoice = iOrder.getInvoice();
         if(iInvoice != null){
         iInvoiceForOrders.put(iOrder.getNumber(), iInvoice.getNumber().toString());
         continue;
         }

         SSPeriodicInvoice iPeriodicInvoice = iOrder.getPeriodicInvoice();
         if(iPeriodicInvoice != null){
         iInvoiceForOrders.put(iOrder.getNumber(), "P" + iPeriodicInvoice.getNumber().toString());
         }
         } */}

}
