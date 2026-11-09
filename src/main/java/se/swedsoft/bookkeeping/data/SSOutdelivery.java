package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;


/**
 * User: Andreas Lago
 * Date: 2006-sep-25
 * Time: 09:31:58
 */
public class SSOutdelivery implements Serializable {

    private static final long serialVersionUID = -5537699296769492741L;

    private Integer iNumber;

    private LocalDate iDate;

    private String iText;

    private List<SSOutdeliveryRow> iRows;

    /**
     *
     */
    public SSOutdelivery() {
        this(true);
    }

    private SSOutdelivery(boolean legacyDefaults) {
        iRows = new LinkedList<>();
        if (!legacyDefaults) {
            return;
        }
        iDate = SSDateUtil.today();
        iText = null;

        doAutoIncrement();
    }

    /**
     * Creates empty rows without legacy numbering, clock or text defaults.
     * @return a stock document for typed persistence hydration only
     */
    public static SSOutdelivery forPersistence() {
        return new SSOutdelivery(false);
    }

    /**
     * Copy constructor
     *
     * @param iOutdelivery
     */
    public SSOutdelivery(SSOutdelivery iOutdelivery) {
        copyFrom(iOutdelivery);
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     */
    public void doAutoIncrement() {
        iNumber = 1;

        List<SSOutdelivery> iOutdeliveries = SSDB.getInstance().getOutdeliveries();

        for (SSOutdelivery iOutdelivery : iOutdeliveries) {
            if (iOutdelivery.iNumber >= iNumber) {
                iNumber = iOutdelivery.iNumber + 1;
            }
        }

    }

    /**
     *
     * @param iOutdelivery
     */
    public void copyFrom(SSOutdelivery iOutdelivery) {
        iNumber = iOutdelivery.iNumber;
        iDate = iOutdelivery.iDate;
        iText = iOutdelivery.iText;
        iRows = new LinkedList<>();

        for (SSOutdeliveryRow iRow : iOutdelivery.iRows) {
            iRows.add(new SSOutdeliveryRow(iRow));
        }
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public Integer getNumber() {
        return iNumber;
    }

    /**
     *
     * @param iNumber
     */
    public void setNumber(Integer iNumber) {
        this.iNumber = iNumber;
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    public LocalDate getLocalDate() {
        return iDate;
    }

    public void setLocalDate(LocalDate iDate) {
        this.iDate = iDate;
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public String getText() {
        return iText;
    }

    /**
     *
     * @param iText
     */
    public void setText(String iText) {
        this.iText = iText;
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSOutdeliveryRow> getRows() {
        return iRows;
    }

    /**
     *
     * @param iRows
     */
    public void setRows(List<SSOutdeliveryRow> iRows) {
        this.iRows = iRows;
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     * Return the correction for the supplied product.
     *
     * @param iProduct
     * @return the correction
     */
    public Integer getChange(SSProduct iProduct) {
        Integer iSum = 0;

        for (SSOutdeliveryRow iRow : iRows) {
            if (iRow.hasProduct(iProduct)) {
                Integer iChange = iRow.getChange();

                if (iChange != null) {
                    iSum = iSum + iChange;
                }
            }

        }

        return iSum;

    }

    public boolean equals(Object obj) {
        if (!(obj instanceof SSOutdelivery)) {
            return false;
        }
        return iNumber.equals(((SSOutdelivery) obj).iNumber);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.SSOutdelivery");
        sb.append("{iDate=").append(iDate);
        sb.append(", iNumber=").append(iNumber);
        sb.append(", iRows=").append(iRows);
        sb.append(", iText='").append(iText).append('\'');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Custom deserialization to handle backward compatibility.
     * Pre-migration serialized streams stored {@code iDate} as {@code java.util.Date}.
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        iNumber = (Integer) fields.get("iNumber", null);
        iDate = SSDateUtil.readLocalDate(fields.get("iDate", null));
        iText = (String) fields.get("iText", null);
        iRows = (List<SSOutdeliveryRow>) fields.get("iRows", null);
    }
}
