/*
 * 2005-2010
 * $Id$
 */
package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.table.SSTableSearchable;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import javax.swing.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;


/**
 */
public class SSNewAccountingYear implements Serializable, SSTableSearchable {

    // / Constant for serialization versioning.
    static final long serialVersionUID = 1L;

    private Integer iId;

    private LocalDate iFrom;

    private LocalDate iTo;

    private SSAccountPlan iPlan;

    private Map<SSAccount, BigDecimal> iInBalance;

    private SSBudget iBudget;

    // Explicitly loaded vouchers; when set they replace the SSDB-backed lookup. Transient
    // so the serialized legacy object graph is unchanged.
    private transient List<SSVoucher> iVouchers;

    /**
     * Default constructor.
     */
    public SSNewAccountingYear() {
        iId = 0;
        iFrom = SSDateUtil.today();
        iTo = SSDateUtil.today();
        iInBalance = new HashMap<>();
        iBudget = new SSBudget();
    }

    /**
     *
     * @param pAccountingYear
     */
    public SSNewAccountingYear(SSNewAccountingYear pAccountingYear) {
        this();
        setData(pAccountingYear);
    }

    public SSNewAccountingYear(SSAccountingYear iOldYear) {
        iFrom = iOldYear.getLocalFrom();
        iTo = iOldYear.getLocalTo();
        iPlan = iOldYear.getAccountPlan();
        iInBalance = iOldYear.getInBalance();
        iBudget = iOldYear.getBudget();
    }

    /**
     * Sets the data of the accountingyear to the same as the parameter
     *
     * Note that the data aren't copied
     *
     * @param pAccountingYear
     */
    public void setData(SSNewAccountingYear pAccountingYear) {
        iId = pAccountingYear.iId;
        iFrom = pAccountingYear.iFrom;
        iTo = pAccountingYear.iTo;
        iInBalance = pAccountingYear.iInBalance;
        iBudget = pAccountingYear.iBudget;
        iPlan = pAccountingYear.iPlan;
    }

    /**
     *
     * @return the id
     */
    public Integer getId() {
        return iId;
    }

    public void setId(Integer pId) {
        iId = pId;
    }

    /**
     * @return the from date as a LocalDate
     */
    public LocalDate getLocalFrom() {
        return iFrom;
    }

    /**
     * @param pFrom the from date as a LocalDate
     */
    public void setLocalFrom(LocalDate pFrom) {
        iFrom = pFrom;
    }

    /**
     * @return the to date as a LocalDate
     */
    public LocalDate getLocalTo() {
        return iTo;
    }

    /**
     * @param pTo the to date as a LocalDate
     */
    public void setLocalTo(LocalDate pTo) {
        iTo = pTo;
    }

    /**
     *
     * @return the account plan
     */
    public SSAccountPlan getAccountPlan() {
        if (iPlan == null) {
            iPlan = new SSAccountPlan();
        }

        return iPlan;
    }

    /**
     *
     * @param pAccountPlan
     */
    public void setAccountPlan(SSAccountPlan pAccountPlan) {
        iPlan = pAccountPlan;
    }

    /**
     *
     * @return the budget for the year
     */
    public SSBudget getBudget() {
        // Make shure the budget know that we are the owning year
        iBudget.setYear(this);

        return iBudget;
    }

    /**
     *
     * @param iBudget
     */
    public void setBudget(SSBudget iBudget) {
        this.iBudget = iBudget;
    }

    /**
     *
     * @return the in balance
     */
    public Map<SSAccount, BigDecimal> getInBalance() {
        return iInBalance;
    }

    /**
     *
     * @param pInBalance
     */
    public void setInBalance(Map<SSAccount, BigDecimal> pInBalance) {
        iInBalance = pInBalance;
    }

    /**
     * Returns the vouchers for the year
     *
     * @return the vouchers
     */
    public List<SSVoucher> getVouchers() {
        if (iVouchers != null) {
            return iVouchers;
        }
        return SSDB.getInstance().getVouchers(this);
    }

    /**
     * Sets an explicit voucher list for the year, replacing the SSDB-backed lookup in
     * {@link #getVouchers()}. Used by storage modes where the legacy object storage is
     * not running; a null value restores the legacy lookup.
     *
     * @param pVouchers the vouchers, or null to restore the SSDB-backed lookup
     */
    public void setVouchers(List<SSVoucher> pVouchers) {
        iVouchers = pVouchers;
    }

    /**
     * Returns the accounts in the current acccountplan.
     *
     * @return A List of the current accounts or null.
     */
    public List<SSAccount> getAccounts() {
        if (iPlan != null) {
            return iPlan.getAccounts();
        }
        return Collections.emptyList();
    }

    /**
     * Returns the active accounts in the current acccountplan.
     *
     * @return A List of the active accounts or null.
     */
    public List<SSAccount> getActiveAccounts() {
        if (iPlan != null) {
            return iPlan.getActiveAccounts();
        }
        return Collections.emptyList();
    }

    /**
     * Returns the render string to be shown in the tables
     *
     * @return The searchable string
     */
    public String toRenderString() {
        DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

        return iFrom.format(iFormat) + " - " + iTo.format(iFormat);
    }

    public String toString() {
        DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

        StringBuilder sb = new StringBuilder();

        sb.append(iFrom.format(iFormat));
        sb.append(' ');
        sb.append(SSBundle.getBundle().getString("date.separator"));
        sb.append(' ');
        sb.append(iTo.format(iFormat));

        return sb.toString();
    }

    /**
     *
     * @param pAccount
     * @param pAmount
     */
    public void setInBalance(SSAccount pAccount, BigDecimal pAmount) {
        if (iInBalance == null) {
            iInBalance = new HashMap<>();
        }
        iInBalance.put(pAccount, pAmount);
    }

    /**
     *
     * @param pAccount
     *
     * @return
     */
    public BigDecimal getInBalance(SSAccount pAccount) {
        if (iInBalance == null) {
            iInBalance = new HashMap<>();
        }
        BigDecimal amount = iInBalance.get(pAccount);

        if (amount == null) {
            amount = new BigDecimal(0);
        }
        return amount;
    }

    /**
     * Custom deserialization that handles both old (Date) and new (LocalDate) field formats.
     *
     * <p>Pre-migration serialized streams stored {@code iFrom} and {@code iTo} as
     * {@code java.util.Date}.  This method reads them as raw objects and converts
     * via {@link SSDateUtil#readLocalDate(Object)}.
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        iId = (Integer) fields.get("iId", null);
        iFrom = SSDateUtil.readLocalDate(fields.get("iFrom", null));
        iTo = SSDateUtil.readLocalDate(fields.get("iTo", null));
        iPlan = (SSAccountPlan) fields.get("iPlan", null);
        iInBalance = (Map<SSAccount, BigDecimal>) fields.get("iInBalance", null);
        iBudget = (SSBudget) fields.get("iBudget", null);
    }


    /**
     *
     * @param iMainFrame The main frame
     */
    public static void openWarningDialogNoYearData(SSMainFrame iMainFrame) {
        String message = SSBundle.getBundle().getString("accountingYear.no.year.message");
        String title = SSBundle.getBundle().getString("accountingYear.no.year.title");

        JOptionPane.showMessageDialog(iMainFrame, message, title,
                JOptionPane.INFORMATION_MESSAGE);
    }

    // //////////////////////////////////////////////////////////////////
    /*
     public static interface SSNewAccountingYearListener{
     public void yearLoaded(SSNewCompany iCompany, SSNewAccountingYear iAccountingYear);
     }

     private static List<SSNewAccountingYearListener> iListeners = new LinkedList<>();

     public void addListener(SSNewAccountingYearListener iListener){
     iListeners.add(iListener);
     }

     private void notifyListeners(SSNewCompany iCompany, SSNewAccountingYear iAccountingYear){
     for(SSNewAccountingYearListener iListener: iListeners){
     iListener.yearLoaded(iCompany, iAccountingYear);
     }
     }  */

    public boolean equals(Object obj) {
        if (!(obj instanceof SSNewAccountingYear)) {
            return false;
        }
        return iId.equals(((SSNewAccountingYear) obj).iId);
    }

}
