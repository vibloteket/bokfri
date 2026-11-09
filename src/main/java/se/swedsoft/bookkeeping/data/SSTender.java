package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.data.base.SSSale;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.time.LocalDate;
import java.util.List;


/**
 * Tender
 * Date: 2006-mar-24
 * Time: 15:51:50
 */
public class SSTender extends SSSale {

    // Constant for serialization versioning.
    static final long serialVersionUID = 1L;

    // Giltig tom
    private LocalDate iExpires;

    public Integer getOrderNr() {
        return iOrderNr;
    }

    /** Sets a stored order reference without resolving it through the global database. */
    public void setOrderNr(Integer number) {
        iOrderNr = number;
        iOrder = null;
    }

    // Order nummer
    private Integer iOrderNr;

    // Valutakurs
    protected BigDecimal iCurrencyRate;

    // Ordern
    private transient SSOrder iOrder;

    // //////////////////////////////////////////////////

    /**
     * Default constructor
     */
    public SSTender() {
        super();
    }

    private SSTender(boolean legacyDefaults) {
        super(legacyDefaults);
    }

    /**
     * Creates empty containers without legacy company, clock or address defaults.
     * @return a tender for typed persistence hydration only
     */
    public static SSTender forPersistence() {
        return new SSTender(false);
    }

    /**
     * Copy constructor
     *
     * @param iTender
     */
    public SSTender(SSTender iTender) {
        copyFrom(iTender);
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @param iTender
     */
    public void copyFrom(SSTender iTender) {
        super.copyFrom(iTender);
        iOrder = iTender.iOrder;
        iOrderNr = iTender.iOrderNr;
        iExpires = iTender.iExpires;
        iCurrencyRate = iTender.iCurrencyRate;
    }

    // //////////////////////////////////////////////////

    /**
     * Sets the number of this sale as the maxinum mumber + 1
     */
    @Override
    public void doAutoIncrecement() {
        SSNewCompany iCompany = SSDB.getInstance().getCurrentCompany();

        int iNumber = iCompany.getAutoIncrement().getNumber("tender");

        for (SSTender iTender:  SSDB.getInstance().getTenders()) {

            if (iTender.getNumber() != null && iTender.getNumber() > iNumber) {
                iNumber = iTender.getNumber();
            }
        }
        setNumber(iNumber + 1);
    }

    // //////////////////////////////////////////////////

    /**
     * @return the expiry date as a LocalDate
     */
    public LocalDate getLocalExpires() {
        return iExpires;
    }

    /**
     * @param iExpires the expiry date as a LocalDate
     */
    public void setLocalExpires(LocalDate iExpires) {
        this.iExpires = iExpires;
    }

    // //////////////////////////////////////////////////

    /** Returns the stored currency rate without resolving customers or applying a fallback. */
    public BigDecimal getStoredCurrencyRate() {
        return iCurrencyRate;
    }

    /** Returns the business currency rate, including the existing customer/one fallback. */
    public BigDecimal getCurrencyRate() {
        if (iCurrencyRate != null) {
            return iCurrencyRate;
        } else {
            if (iCustomer != null) {
                return iCustomer.getInvoiceCurrency().getExchangeRate();
            } else {
                return new BigDecimal(1);
            }
        }
    }

    /**
     *
     * @param iCurrencyRate
     */
    public void setCurrencyRate(BigDecimal iCurrencyRate) {
        this.iCurrencyRate = iCurrencyRate;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public SSOrder getOrder() {
        return getOrder(SSDB.getInstance().getOrders());
    }

    /**
     *
     * @param iOrders
     * @return
     */
    public SSOrder getOrder(List<SSOrder> iOrders) {
        if (iOrder == null && iOrderNr != null) {
            for (SSOrder iCurrent : iOrders) {
                if (iOrderNr.equals(iCurrent.getNumber())) {
                    iOrder = iCurrent;
                }
            }
        }
        return iOrder;
    }

    /**
     *
     * @param iOrder
     */
    public void setOrder(SSOrder iOrder) {
        this.iOrder = iOrder;
        iOrderNr = iOrder == null ? null : iOrder.getNumber();
    }

    public boolean hasOrder(SSOrder iOrder) {
        return iOrderNr != null && iOrderNr.equals(iOrder.getNumber());
    }

    // //////////////////////////////////////////////////

    /**
     * Returns whether the tender has expired relative to today.
     *
     * @return if the tender is expired
     */
    public boolean isExpired() {
        // Never expires
        if (iExpires == null) {
            return false;
        }

        return LocalDate.now().isAfter(iExpires);
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof SSTender)) {
            return false;
        }
        return iNumber.equals(((SSTender) obj).getNumber());
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.SSTender");
        sb.append("{iCurrencyRate=").append(iCurrencyRate);
        sb.append(", iExpires=").append(iExpires);
        sb.append(", iOrder=").append(iOrder);
        sb.append(", iOrderNr=").append(iOrderNr);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Custom deserialization to handle backward compatibility.
     * Pre-migration serialized streams stored {@code iExpires} as
     * {@code java.util.Date}.  This method reads it as a raw object and converts
     * via {@link SSDateUtil#readLocalDate(Object)}.
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        iExpires = SSDateUtil.readLocalDate(fields.get("iExpires", null));
        iOrderNr = (Integer) fields.get("iOrderNr", null);
        iCurrencyRate = (BigDecimal) fields.get("iCurrencyRate", null);
    }
}
