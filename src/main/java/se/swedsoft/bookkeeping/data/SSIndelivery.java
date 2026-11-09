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
public class SSIndelivery implements Serializable {

    private static final long serialVersionUID = -5537699296769492741L;

    private Integer iNumber;

    private LocalDate iDate;

    private String iText;

    private List<SSIndeliveryRow> iRows;

    /**
     *
     */
    public SSIndelivery() {
        this(true);
    }

    private SSIndelivery(boolean legacyDefaults) {
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
    public static SSIndelivery forPersistence() {
        return new SSIndelivery(false);
    }

    /**
     * Copy constructor
     *
     * @param iIndelivery
     */
    public SSIndelivery(SSIndelivery iIndelivery) {
        copyFrom(iIndelivery);
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     */
    public void doAutoIncrement() {
        iNumber = 1;

        List<SSIndelivery> iIndeliveries = SSDB.getInstance().getIndeliveries();

        for (SSIndelivery iIndelivery : iIndeliveries) {
            if (iIndelivery.iNumber >= iNumber) {
                iNumber = iIndelivery.iNumber + 1;
            }
        }

    }

    /**
     *
     * @param iIndelivery
     */
    public void copyFrom(SSIndelivery iIndelivery) {
        iNumber = iIndelivery.iNumber;
        iDate = iIndelivery.iDate;
        iText = iIndelivery.iText;
        iRows = new LinkedList<>();

        for (SSIndeliveryRow iRow : iIndelivery.iRows) {
            iRows.add(new SSIndeliveryRow(iRow));
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
    public List<SSIndeliveryRow> getRows() {
        return iRows;
    }

    /**
     *
     * @param iRows
     */
    public void setRows(List<SSIndeliveryRow> iRows) {
        this.iRows = iRows;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof SSIndelivery)) {
            return false;
        }
        return iNumber.equals(((SSIndelivery) obj).iNumber);
    }

    // /////////////////////////////////////////////////////////////////////////////////////

    /**
     * Return the correction for the supplied product.
     *
     * @param iProduct
     * @return the correction
     */
    public Integer getChange(SSProduct iProduct) {
        Integer iChange = 0;

        for (SSIndeliveryRow iRow : iRows) {
            if (iRow.hasProduct(iProduct)) {
                iChange = iChange + iRow.getChange();
            }

        }

        return iChange;

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.SSIndelivery");
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
        iRows = (List<SSIndeliveryRow>) fields.get("iRows", null);
    }
}
