/*
 * 2005-2010
 * $Id$
 */
package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.gui.util.table.SSTableSearchable;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


/**
 */
public class SSVoucher implements Serializable, Cloneable, SSTableSearchable {

    /**
     * Constant for serialization versioning.
     */
    static final long serialVersionUID = 1L;

    private int iNumber;

    private LocalDate iDate;

    private String iDescription;

    private SSVoucher iCorrects;

    private SSVoucher iCorrectedBy;

    private List<SSVoucherRow> iVoucherRows;

    /**
     * Default constructor.
     */
    public SSVoucher() {
        iDate = SSVoucherMath.getNextVoucherLocalDate();
        iVoucherRows = new ArrayList<>();
        doAutoIncrecement();
    }

    public SSVoucher(Integer iNumber) {
        iDate = SSVoucherMath.getNextVoucherLocalDate();
        iVoucherRows = new ArrayList<>();
        this.iNumber = iNumber;
    }

    /**
     * Creates a voucher header without reading the selected year, clock or legacy database.
     * @param number explicit voucher number
     * @param date explicit date, nullable for historical snapshots
     */
    public SSVoucher(int number, LocalDate date) {
        iNumber = number;
        iDate = date;
        iVoucherRows = new ArrayList<>();
    }

    /**
     * Copy constructor.
     *
     * @param voucher The voucher to copy.
     */
    public SSVoucher(SSVoucher voucher) {
        copyFrom(voucher);
    }

    /**
     *
     * @param pVoucher
     */
    public void copyFrom(SSVoucher pVoucher) {
        iNumber = pVoucher.iNumber;
        iDate = pVoucher.iDate;
        iDescription = pVoucher.iDescription;
        iCorrects = pVoucher.iCorrects;
        iCorrectedBy = pVoucher.iCorrectedBy;
        iVoucherRows = new LinkedList<>();

        for (SSVoucherRow iVoucherRow : pVoucher.iVoucherRows) {
            iVoucherRows.add(new SSVoucherRow(iVoucherRow));
        }

    }

    // //////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public int getNumber() {
        return iNumber;
    }

    /**
     *
     * @param number
     */
    public void setNumber(int number) {
        iNumber = number;
    }

    /**
     * Sets the number of this voucher as the maxinum mumber + 1
     */
    public void doAutoIncrecement() {
        int iNumber = SSVoucherMath.getMaxNumber();

        this.iNumber = iNumber + 1;
    }

    // //////////////////////////////////////////////////////////////////

    /**
     * Returns the date as a {@link LocalDate}.
     *
     * @return the date
     */
    public LocalDate getLocalDate() {
        return iDate;
    }

    /**
     * Sets the date as a {@link LocalDate}.
     *
     * @param date the date
     */
    public void setLocalDate(LocalDate date) {
        iDate = date;
    }

    // //////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public String getDescription() {
        return iDescription;
    }

    /**
     *
     * @param description
     */
    public void setDescription(String description) {
        iDescription = description;
    }

    // //////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public SSVoucher getCorrects() {
        return iCorrects;
    }

    /**
     *
     * @param corrects
     */
    public void setCorrects(SSVoucher corrects) {
        iCorrects = corrects;
    }

    // //////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public SSVoucher getCorrectedBy() {
        return iCorrectedBy;
    }

    /**
     *
     * @param correctedBy
     */
    public void setCorrectedBy(SSVoucher correctedBy) {
        iCorrectedBy = correctedBy;
    }

    // //////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSVoucherRow> getRows() {
        return iVoucherRows;
    }

    /**
     *
     * @param pVoucherRow
     */
    public void addVoucherRow(SSVoucherRow pVoucherRow) {
        iVoucherRows.add(pVoucherRow);
    }

    /**
     *
     * @param iAccount
     * @param iDebet
     * @param iCredit
     */
    public void addVoucherRow(SSAccount iAccount, BigDecimal iDebet, BigDecimal iCredit) {
        SSVoucherRow iVoucherRow = new SSVoucherRow();

        iVoucherRow.setAccount(iAccount);
        iVoucherRow.setDebet(iDebet);
        iVoucherRow.setCredit(iCredit);

        iVoucherRows.add(iVoucherRow);
    }

    /**
     *
     * @param iAccount
     * @param iValue
     */
    public void addVoucherRow(SSAccount iAccount, BigDecimal iValue) {
        // Dont add a empty row
        if (iValue == null || iValue.signum() == 0) {
            return;
        }

        SSVoucherRow iVoucherRow = new SSVoucherRow();

        iVoucherRow.setAccount(iAccount);

        // Add the rounding
        if (iValue.signum() > 0) {
            iVoucherRow.setDebet(iValue);
            iVoucherRow.setCredit(null);
        } else {
            iVoucherRow.setDebet(null);
            iVoucherRow.setCredit(iValue.abs());
        }

        iVoucherRows.add(iVoucherRow);
    }

    /**
     *
     * @param row
     * @return
     */
    public boolean removeVoucherRow(SSVoucherRow row) {
        return iVoucherRows.remove(row);
    }

    /**
     *
     * @param rows
     */
    public void setVoucherRows(List<SSVoucherRow> rows) {
        iVoucherRows = rows;
    }

    // //////////////////////////////////////////////////////////////////

    public boolean equals(Object obj) {
        if (obj instanceof SSVoucher) {
            SSVoucher iVoucher = (SSVoucher) obj;

            return iNumber == iVoucher.iNumber;
        }
        return super.equals(obj);
    }

    public String toString() {
        DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

        StringBuilder sb = new StringBuilder();

        sb.append(iNumber);
        sb.append(", ");
        sb.append(iDescription);
        sb.append(", ");
        sb.append(iDate != null ? iDate.format(iFormat) : "null"); /*
         sb.append( ", " );
         sb.append( iVoucherRows.size() );
         sb.append( " rows.{\n" );
         for (SSVoucherRow row : iVoucherRows) {
         sb.append("  " );
         sb.append(row );
         sb.append("\n");
         }
         sb.append( "}\n" );   */
        return sb.toString();
    }

    /**
     * Returns the render string to be shown in the tables
     *
     * @return The searchable string
     */
    public String toRenderString() {
        return Integer.toString(iNumber);
    }

    /**
     * Creates a new voucher with the number as the lastest voucher number + 1
     * @return The voucher

     public static SSVoucher newVoucher(){
     SSVoucher        iVoucher  = new SSVoucher();
     SSVoucher        iPrevious = SSVoucherMath.getPreviousVoucher();
     SSNewAccountingYear iYear     = SSDB.getInstance().getCurrentYear();

     LocalDate iDate = iPrevious != null ? iPrevious.getLocalDate() : (iYear != null) ? iYear.getLocalFrom() : SSDateUtil.today();

     iVoucher.doAutoIncrecement();
     iVoucher.setLocalDate(iDate);

     return iVoucher;
     }

     */

    /**
     * Used to clean up references making sure the garbage collector
     * is able to clean up the object.
     */
    public void dispose() {// iDate=null;
        // iDescription=null;
        // iCorrects=null;
        // iCorrectedBy=null;
        // iVoucherRows.removeAll(iVoucherRows);
    }

    /**
     * Custom deserialization to handle backward compatibility.
     * Pre-migration serialized streams stored {@code iDate} as {@code java.util.Date}.
     * This method reads it as a raw object and converts via
     * {@link SSDateUtil#readLocalDate(Object)}.
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        iNumber = fields.get("iNumber", 0);
        iDate = SSDateUtil.readLocalDate(fields.get("iDate", null));
        iDescription = (String) fields.get("iDescription", null);
        iCorrects = (SSVoucher) fields.get("iCorrects", null);
        iCorrectedBy = (SSVoucher) fields.get("iCorrectedBy", null);
        iVoucherRows = (List<SSVoucherRow>) fields.get("iVoucherRows", null);
    }
}
