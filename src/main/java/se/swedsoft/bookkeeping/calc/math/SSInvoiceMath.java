package se.swedsoft.bookkeeping.calc.math;


import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.system.SSDB;

import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * User: Andreas Lago
 * Date: 2006-mar-27
 * Time: 15:42:39
 */
public class SSInvoiceMath extends SSSaleMath {    private static final Logger LOG = LoggerFactory.getLogger(SSInvoiceMath.class);


    /**
     *
     * @param iInvoice
     * @return
     */
    public static boolean expired(SSInvoice iInvoice) {
        LocalDate dueDate = iInvoice.getLocalDueDate();

        return dueDate != null && SSDateUtil.today().isAfter(dueDate);

    }

    /**
     * Convers a value from a sales currency to the company currency
     *
     * @param iInvoice
     * @param iValue
     * @return the converted value
     */
    public static BigDecimal convertToLocal(SSInvoice iInvoice, BigDecimal iValue) {
        BigDecimal iCurrencyRate = iInvoice.getCurrencyRate();

        if (iCurrencyRate != null) {
            iValue = iValue.multiply(iCurrencyRate);
        }

        return iValue;
    }

    public static BigDecimal convertToLocal(Integer iInvoiceNr, BigDecimal iValue) {
        SSInvoice iInvoice = new SSInvoice();

        iInvoice.setNumber(iInvoiceNr);
        iInvoice = SSDB.getInstance().getInvoice(iInvoice).orElse(null);

        BigDecimal iCurrencyRate = iInvoice.getCurrencyRate();

        if (iCurrencyRate != null) {
            iValue = iValue.multiply(iCurrencyRate);
        }

        return iValue;
    }

    /**
     * Returns the saldo for the sales, in the sales currency
     *
     * @param iInvoice
     *
     * @return  the saldo
     */
    public static BigDecimal getSaldo(SSInvoice iInvoice) {
        // a cash sales cant have any saldo
        if (iInvoice.getType() == SSInvoiceType.CASH) {
            return new BigDecimal(0);
        }

        BigDecimal iTotalSum = getTotalSum(iInvoice);

        BigDecimal iCreditingSum = SSCreditInvoiceMath.getSumForInvoice(iInvoice);
        BigDecimal iInpaymentSum = SSInpaymentMath.getSumForInvoice(iInvoice);

        iTotalSum = iTotalSum.subtract(iCreditingSum);
        iTotalSum = iTotalSum.subtract(iInpaymentSum);

        return iTotalSum.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal getSaldo(Integer iInvoiceNumber) {
        if (iSaldoMap.containsKey(iInvoiceNumber)) {
            return iSaldoMap.get(iInvoiceNumber);
        } else {
            return new BigDecimal(0);
        }
    }

    public static HashMap<Integer, BigDecimal> iSaldoMap;

    public static void calculateSaldos() {
        if (iSaldoMap == null) {
            iSaldoMap = new HashMap<>();
        }
        HashMap<Integer, BigDecimal> iInpaymentSum = SSInpaymentMath.getSumsForInvoices();

        HashMap<Integer, BigDecimal> iCreditInvoiceSum = SSCreditInvoiceMath.getSumsForInvoices();

        List<SSInvoice> iInvoices = SSDB.getInstance().getInvoices();

        for (SSInvoice iInvoice : iInvoices) {
            if (iInvoice.getType() == SSInvoiceType.CASH) {
                continue;
            }

            BigDecimal iTotalSum = getTotalSum(iInvoice);

            if (iInpaymentSum.containsKey(iInvoice.getNumber())) {
                iTotalSum = iTotalSum.subtract(iInpaymentSum.get(iInvoice.getNumber()));
            }

            if (iCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                iTotalSum = iTotalSum.subtract(iCreditInvoiceSum.get(iInvoice.getNumber()));
            }

            iSaldoMap.put(iInvoice.getNumber(), iTotalSum);
        }
    }

    public static Map<Integer, BigDecimal> getSaldos(LocalDate iDate) {
        Map<Integer, BigDecimal> iSaldos = new HashMap<>();

        HashMap<Integer, BigDecimal> iInpaymentSum = SSInpaymentMath.getSumsForInvoices(iDate);

        HashMap<Integer, BigDecimal> iCreditInvoiceSum = SSCreditInvoiceMath.getSumsForInvoices(iDate);

        List<SSInvoice> iInvoices = SSDB.getInstance().getInvoices();

        for (SSInvoice iInvoice : iInvoices) {
            if (iInvoice.getType() == SSInvoiceType.CASH) {
                continue;
            }

            BigDecimal iTotalSum = getTotalSum(iInvoice);

            if (iInpaymentSum.containsKey(iInvoice.getNumber())) {
                iTotalSum = iTotalSum.subtract(iInpaymentSum.get(iInvoice.getNumber()));
            }

            if (iCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                iTotalSum = iTotalSum.subtract(iCreditInvoiceSum.get(iInvoice.getNumber()));
            }

            iSaldos.put(iInvoice.getNumber(), iTotalSum);
        }
        return iSaldos;
    }

    /**
     * Returns the partial saldo for the sales, in the sales currency up
     * to and including the selected date
     *
     * @param iInvoice
     * @param iDate The end date to calculate up to
     *
     * @return  the saldo
     */
    public static BigDecimal getSaldo(SSInvoice iInvoice, LocalDate iDate) {
        // a cash sales cant have any saldo
        if (iInvoice.getType() == SSInvoiceType.CASH) {
            return new BigDecimal(0);
        }

        BigDecimal iTotalSum = getTotalSum(iInvoice);

        BigDecimal iCreditingSum = SSCreditInvoiceMath.getSumForInvoice(iInvoice, iDate);
        BigDecimal iInpaymentSum = SSInpaymentMath.getSumForInvoice(iInvoice, iDate);

        iTotalSum = iTotalSum.subtract(iCreditingSum);
        iTotalSum = iTotalSum.subtract(iInpaymentSum);

        return iTotalSum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns all invoices and saldos up to and including the specified date
     *
     * @param iInvoices The invoices
     * @param iDate The end date
     *
     * @return map of the invoices and their saldo
     */
    public static Map<SSInvoice, BigDecimal> getSaldo(List<SSInvoice> iInvoices, LocalDate iDate) {
        Map<SSInvoice, BigDecimal> iSaldos = new HashMap<>();

        HashMap<Integer, BigDecimal> iInpaymentSum = SSInpaymentMath.getSumsForInvoices(iDate);

        HashMap<Integer, BigDecimal> iCreditInvoiceSum = SSCreditInvoiceMath.getSumsForInvoices(iDate);

        // Loop through the invoices
        for (SSInvoice iInvoice : iInvoices) {
            LocalDate iCurrent = iInvoice.getLocalDate();

            // Only put invoices that is added before the specified date
            if (iCurrent != null && iDate != null && !iCurrent.isAfter(iDate)
                    && iInvoice.getType() != SSInvoiceType.CASH) {
                BigDecimal iSum = getTotalSum(iInvoice);

                if (iInpaymentSum.containsKey(iInvoice.getNumber())) {
                    iSum = iSum.subtract(iInpaymentSum.get(iInvoice.getNumber()));
                }

                if (iCreditInvoiceSum.containsKey(iInvoice.getNumber())) {
                    iSum = iSum.subtract(iCreditInvoiceSum.get(iInvoice.getNumber()));
                }

                iSaldos.put(iInvoice, iSum.setScale(2, RoundingMode.HALF_UP));
                // BigDecimal iSaldo = getSaldo(iInvoice, iDate);
                // iSaldos.put(iInvoice, iSaldo.setScale(2, RoundingMode.HALF_UP));
            }
        }
        return iSaldos;
    }

    /**
     * Returns the sum of all saldos up to and including the specified date
     *
     * @param iInvoices The invoices
     * @param iDate The end date
     *
     * @return the saldo sum
     */
    public static BigDecimal getSaldoSum(List<SSInvoice> iInvoices, LocalDate iDate) {
        Map<SSInvoice, BigDecimal> iSaldos = getSaldo(iInvoices, iDate);

        BigDecimal iSum = new BigDecimal(0);

        for (SSInvoice iInvoice : iInvoices) {
            iSum = iSum.add(iSaldos.get(iInvoice));
        }

        return iSum;
    }

    /**
     * Returns the saldo for the sales, in the sales currency
     *
     * @param iInvoice
     *
     * @param iDate
     * @return  the saldo
     */
    public static BigDecimal getSumMinusCredited(SSInvoice iInvoice, LocalDate iDate) {
        // a cash sales cant have any saldo
        if (iInvoice.getType() == SSInvoiceType.CASH) {
            return new BigDecimal(0);
        }

        BigDecimal iTotalSum = getTotalSum(iInvoice);

        BigDecimal iCreditingSum = SSCreditInvoiceMath.getSumForInvoice(iInvoice, iDate);

        iTotalSum = iTotalSum.subtract(iCreditingSum);

        return iTotalSum;
    }

    /**
     * Returns the order connected to this sales, null if none is found
     *
     * @param iInvoice
     * @return the order or null
     */
    public static List<SSOrder> getOrdersForInvoice(SSInvoice iInvoice) {
        return getOrdersForInvoice(SSDB.getInstance().getOrders(), iInvoice);
    }

    /**
     * Returns the order connected to this sales from the list of orders, null if none is found
     *
     * @param iOrders
     * @param iInvoice
     * @return the order or null
     */
    public static List<SSOrder> getOrdersForInvoice(List<SSOrder> iOrders, SSInvoice iInvoice) {
        return iOrders.stream()
                .filter(iOrder -> iOrder.hasInvoice(iInvoice))
                .collect(Collectors.toList());
    }

    /**
     * Returns all invoices for the current customer
     *
     * @param iCustomer
     * @return the invoices for the customer
     */
    public static List<SSInvoice> getInvoicesForCustomer(SSCustomer iCustomer) {
        return getInvoicesForCustomer(SSDB.getInstance().getInvoices(), iCustomer);
    }

    /**
     * Returns all invoices for the current customer
     *
     * @param iInvoices
     * @param iCustomer
     * @return the invoices for the customer
     */
    public static List<SSInvoice> getInvoicesForCustomer(List<SSInvoice> iInvoices, SSCustomer iCustomer) {
        return iInvoices.stream()
                .filter(iInvoice -> iInvoice.hasCustomer(iCustomer))
                .collect(Collectors.toList());
    }

    public static Map<String, List<SSInvoice>> getInvoicesforCustomers() {
        List<SSInvoice> iInvoices = SSDB.getInstance().getInvoices();
        Map<String, List<SSInvoice>> iMap = new HashMap<>();

        for (SSInvoice iInvoice : iInvoices) {
            if (iInvoice.getCustomerNr() != null) {
                if (iMap.containsKey(iInvoice.getCustomerNr())) {
                    iMap.get(iInvoice.getCustomerNr()).add(iInvoice);
                } else {
                    List<SSInvoice> iTemp = new LinkedList<>();

                    iTemp.add(iInvoice);
                    iMap.put(iInvoice.getCustomerNr(), iTemp);
                }
            }
        }
        return iMap;
    }

    /**
     * Returns all invoices for the current customer
     *
     * @param iCustomer
     * @param iDate
     * @return the invoices for the customer
     */
    public static List<SSInvoice> getInvoicesForCustomer(SSCustomer iCustomer, LocalDate iDate) {
        return getInvoicesForCustomer(SSDB.getInstance().getInvoices(), iCustomer, iDate);
    }

    /**
     * Returns all invoices for the current customer
     *
     * @param iInvoices
     * @param iCustomer
     * @param iDate
     * @return the invoices for the customer
     */
    public static List<SSInvoice> getInvoicesForCustomer(List<SSInvoice> iInvoices, SSCustomer iCustomer, LocalDate iDate) {
        return iInvoices.stream()
                .filter(iInvoice -> iInvoice.hasCustomer(iCustomer) && inPeriod(iInvoice, iDate))
                .collect(Collectors.toList());
    }

    /**
     * Returns the invoices where the saldo is zero
     *
     * @return list of invoices
     */
    public static List<SSInvoice> getPayedOrCreditedInvoices() {
        return getPayedOrCreditedInvoices(SSDB.getInstance().getInvoices());
    }

    /**
     * Returns the invoices where the saldo is zero
     *
     * @param iInvoices
     * @return list of invoices
     */
    public static List<SSInvoice> getPayedOrCreditedInvoices(List<SSInvoice> iInvoices) {
        List<SSInvoice> iFiltered = new LinkedList<>();

        for (SSInvoice iInvoice : iInvoices) {
            BigDecimal iSaldo = getSaldo(iInvoice.getNumber());

            if (iSaldo.signum() == 0) {
                iFiltered.add(iInvoice);
            }
        }
        return iFiltered;
    }

    /**
     * Returns the invoices where the saldo different from zero
     *
     * @return list of invoices
     */
    public static List<SSInvoice> getNonPayedOrCreditedInvoices() {
        return getNonPayedOrCreditedInvoices(SSDB.getInstance().getInvoices());
    }

    /**
     * Returns the invoices where the saldo is different from zero
     *
     * @param iInvoices
     * @return list of invoices
     */
    public static List<SSInvoice> getNonPayedOrCreditedInvoices(List<SSInvoice> iInvoices) {
        List<SSInvoice> iFiltered = new LinkedList<>();

        for (SSInvoice iInvoice : iInvoices) {
            BigDecimal iSaldo = getSaldo(iInvoice.getNumber());

            if (iSaldo.signum() != 0) {
                iFiltered.add(iInvoice);
            }
        }
        return iFiltered;
    }

    /**
     * Returns the number of days between the due date and the last payment date.
     *
     * @param iInvoice the invoice
     * @return the number of delayed days, or 0 if no payment or due date exists
     */
    public static int getNumDelayedDays(SSInvoice iInvoice) {
        LocalDate iPaymentDay = iInvoice.getLocalDueDate();
        LocalDate iLastPayment = SSInpaymentMath.getLastLocalInpaymentForInvoice(iInvoice);

        if (iLastPayment == null || iPaymentDay == null) {
            return 0;
        }

        long days = ChronoUnit.DAYS.between(iPaymentDay, iLastPayment);
        return (int) Math.max(days, 0);
    }

    /**
     *
     * @param iInvoice
     * @return
     */
    public static BigDecimal getInterestSaldo(SSInvoice iInvoice) {
        BigDecimal iTotalSum = getTotalSum(iInvoice);

        BigDecimal iCredited = SSCreditInvoiceMath.getSumForInvoice(iInvoice);

        return iTotalSum.subtract(iCredited);

    }

    /**
     *
     * @param iInvoice
     * @param iSaldo
     * @param iNumDays
     * @return
     */
    public static BigDecimal getInterestSum(SSInvoice iInvoice, BigDecimal iSaldo, int iNumDays) {
        BigDecimal iInterest = iInvoice.getDelayInterest();

        // LOG.info(iInterest);
        BigDecimal iNormalisedInterest = iInterest.scaleByPowerOfTen(-2);

        BigDecimal iDay = new BigDecimal(iNumDays).divide(new BigDecimal(365), 16,
                RoundingMode.HALF_UP);

        return iSaldo.multiply(iNormalisedInterest).multiply(iDay);
    }

    /**
     *
     * @param iReferensNumber
     * @return
     */
    public static Optional<SSInvoice> getInvoiceByReference(String iReferensNumber) {
        return getInvoiceByReference(SSDB.getInstance().getInvoices(), iReferensNumber);
    }

    /**
     *
     * @param iInvoices
     * @param iReferensNumber
     * @return
     */
    public static Optional<SSInvoice> getInvoiceByReference(List<SSInvoice> iInvoices, String iReferensNumber) {
        for (SSInvoice iInvoice : iInvoices) {
            String iNumber = iInvoice.getNumber().toString();
            String iOCRNumber = iInvoice.getOCRNumber();

            if (iReferensNumber.equals(iOCRNumber) || iReferensNumber.equals(iNumber)) {
                return Optional.of(iInvoice);
            }
        }
        return Optional.empty();
    }

    public static Map<String, Integer> getStockInfluencing(List<? extends SSInvoice> iInvoices) {
        Map<String, Integer> iInvoiceCount = new HashMap<>();
        List<String> iParcelProducts = new LinkedList<>();
        List<SSProduct> iProducts = new LinkedList<>(
                SSDB.getInstance().getProducts());

        for (SSProduct iProduct : iProducts) {
            if (iProduct.isParcel() && iProduct.getNumber() != null) {
                iParcelProducts.add(iProduct.getNumber());
            }
        }
        for (SSInvoice iInvoice : iInvoices) {
            for (SSSaleRow iRow : iInvoice.getRows()) {
                if (iRow.getQuantity() == null) {
                    continue;
                }
                Integer iReserved;

                if (iParcelProducts.contains(iRow.getProductNr())) {
                    SSProduct iProduct = iRow.getProduct();

                    if (iProduct != null) {
                        for (SSProductRow iProductRow : iProduct.getParcelRows()) {
                            iReserved = iInvoiceCount.get(iProductRow.getProductNr())
                                    == null
                                            ? iProductRow.getQuantity()
                                                    * iRow.getQuantity().intValueExact()
                                                    : iInvoiceCount.get(
                                                            iProductRow.getProductNr())
                                                                    + (iProductRow.getQuantity()
                                                                            * iRow.getQuantity().intValueExact());
                            iInvoiceCount.put(iProductRow.getProductNr(), iReserved);
                        }
                    }
                } else {
                    iReserved = iInvoiceCount.get(iRow.getProductNr()) == null
                            ? iRow.getQuantity().intValueExact()
                            : iInvoiceCount.get(iRow.getProductNr())
                                    + iRow.getQuantity().intValueExact();
                    iInvoiceCount.put(iRow.getProductNr(), iReserved);
                }
            }
        }
        return iInvoiceCount;
    }

}
