package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.calc.math.SSDateMath;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;


/**
 * User: Andreas Lago
 * Date: 2006-aug-11
 * Time: 09:10:23
 */
public class SSPeriodicInvoice implements Serializable {

    private static final long serialVersionUID = 4800991425088361649L;

    private Integer iNumber;
    // Fakturamallen
    private SSInvoice iTemplate;

    // Start Datum
    private LocalDate iDate;
    // Antal fakturor
    private Integer iCount;
    // Perioden i månader
    private Integer iPeriod;
    // Beskrivning
    private String iDescription;

    private LocalDate iPeriodStart;

    private LocalDate iPeriodEnd;

    private boolean iAppendPeriod;

    private boolean iAppendInformation;

    private String iInformation;

    // Fakturor
    private List<SSInvoice> iInvoices;
    // Tillagta fakturor
    private Map<Integer, Boolean> iAdded;

    // ////////////////////////////////////////////////////////////////////////////////

    /**
     *
     */
    public SSPeriodicInvoice() {
        iDate = SSDateUtil.today();
        iCount = 1;
        iPeriod = 1;
        iAppendPeriod = false;
        iAppendInformation = false;
        iInformation = "Detta är faktura [FAK] av [TOT].";
        iPeriodStart = SSDateMath.getFirstDayInMonth(iDate);
        iPeriodEnd = SSDateMath.getLastDayInMonth(iDate);
        iInvoices = new LinkedList<>();
        iAdded = new HashMap<>();
        doAutoIncrecement();
    }

    /**
     *
     * @param iPeriodicInvoice
     */
    public SSPeriodicInvoice(SSPeriodicInvoice iPeriodicInvoice) {
        copyFrom(iPeriodicInvoice);
    }

    /**
     *
     * @param iPeriodicInvoice
     */
    public void copyFrom(SSPeriodicInvoice iPeriodicInvoice) {
        iNumber = iPeriodicInvoice.iNumber;
        iPeriod = iPeriodicInvoice.iPeriod;
        iDate = iPeriodicInvoice.iDate;
        iCount = iPeriodicInvoice.iCount;
        iDescription = iPeriodicInvoice.iDescription;
        iPeriodStart = iPeriodicInvoice.iPeriodStart;
        iPeriodEnd = iPeriodicInvoice.iPeriodEnd;
        iAppendPeriod = iPeriodicInvoice.iAppendPeriod;
        iAppendInformation = iPeriodicInvoice.iAppendInformation;
        iInformation = iPeriodicInvoice.iInformation;
        iTemplate = new SSInvoice(iPeriodicInvoice.iTemplate);
        iInvoices = new LinkedList<>();
        iAdded = new HashMap<>();
        iTemplate.setCurrency(iPeriodicInvoice.getTemplate().getCurrency());
        iTemplate.setCurrencyRate(iPeriodicInvoice.getTemplate().getCurrencyRate());

        for (SSInvoice iInvoice : iPeriodicInvoice.iInvoices) {
            boolean isAdded = iPeriodicInvoice.isAdded(iInvoice);

            iInvoices.add(new SSInvoice(iInvoice));
            iAdded.put(iInvoice.getNumber(), isAdded);
        }
    }

    // //////////////////////////////////////////////////

    /**
     * Auto increment the sales number
     */
    public void doAutoIncrecement() {
        List<SSPeriodicInvoice> iPeriodicInvoices = SSDB.getInstance().getPeriodicInvoices();

        int iMax = 0;

        for (SSPeriodicInvoice iPeriodicInvoice: iPeriodicInvoices) {

            if (iPeriodicInvoice.iNumber != null && iPeriodicInvoice.iNumber > iMax) {
                iMax = iPeriodicInvoice.iNumber;
            }
        }
        iNumber = iMax + 1;
    }

    // //////////////////////////////////////////////////

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

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public SSInvoice getTemplate() {
        if (iTemplate == null) {
            iTemplate = new SSInvoice(SSInvoiceType.NORMAL);
        }
        return iTemplate;
    }

    /** Returns the persisted template without generating a default. */
    public SSInvoice getStoredTemplate() {
        return iTemplate;
    }

    /**
     *
     * @param iTemplate
     */
    public void setTemplate(SSInvoice iTemplate) {
        this.iTemplate = iTemplate;

        // createInvoices();
    }

    // //////////////////////////////////////////////////

    /**
     * @return the date as a LocalDate
     */
    public LocalDate getLocalDate() {
        return iDate;
    }

    /**
     * @param iDate the date as a LocalDate
     */
    public void setLocalDate(LocalDate iDate) {
        this.iDate = iDate;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public Integer getCount() {
        return iCount;
    }

    /**
     *
     * @param iValue
     */
    public void setCount(Integer iValue) {
        iCount = iValue;

        // createInvoices();
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public Integer getPeriod() {
        return iPeriod;
    }

    /**
     *
     * @param iValue
     */
    public void setPeriod(Integer iValue) {
        iPeriod = iValue;
        // createInvoices();
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public String getDescription() {
        return iDescription;
    }

    /**
     *
     * @param iDescription
     */
    public void setDescription(String iDescription) {
        this.iDescription = iDescription;
    }

    // //////////////////////////////////////////////////

    /**
     * @return the period start date as a LocalDate
     */
    public LocalDate getLocalPeriodStart() {
        return iPeriodStart;
    }

    /**
     * @param iPeriodStart the period start date as a LocalDate
     */
    public void setLocalPeriodStart(LocalDate iPeriodStart) {
        this.iPeriodStart = iPeriodStart;
    }

    // //////////////////////////////////////////////////

    /**
     * @return the period end date as a LocalDate
     */
    public LocalDate getLocalPeriodEnd() {
        return iPeriodEnd;
    }

    /**
     * @param iPeriodEnd the period end date as a LocalDate
     */
    public void setLocalPeriodEnd(LocalDate iPeriodEnd) {
        this.iPeriodEnd = iPeriodEnd;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public boolean getAppendPeriod() {
        return iAppendPeriod;
    }

    /**
     *
     * @param iAppendPeriod
     */

    public void setAppendPeriod(boolean iAppendPeriod) {
        this.iAppendPeriod = iAppendPeriod;

        // createInvoices();
    }

    public boolean isAppendInformation() {
        return iAppendInformation;
    }

    public void setAppendInformation(boolean iAppendInformation) {
        this.iAppendInformation = iAppendInformation;
    }

    public String getInformation() {
        return iInformation;
    }

    public void setInformation(String iInformation) {
        this.iInformation = iInformation;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @param iInvoice
     * @return
     */
    /** Returns the added flags for persisted instances, keyed by invoice number. */
    public Map<Integer, Boolean> getStoredAdded() {
        if (iAdded == null) {
            iAdded = new HashMap<>();
        }
        return iAdded;
    }

    public boolean isAdded(SSInvoice iInvoice) {
        if (iAdded == null) {
            iAdded = new HashMap<>();
        }

        Integer iNumber = iInvoice.getNumber();

        if (iNumber != null) {
            return iAdded.get(iNumber);
        } else {
            return true;
        }
    }

    /**
     *
     * @param iInvoice
     */
    public void setAdded(SSInvoice iInvoice) {
        if (iAdded == null) {
            iAdded = new HashMap<>();
        }

        Integer iNumber = iInvoice.getNumber();

        if (iNumber != null) {
            iAdded.put(iNumber, true);
        }
    }

    /**
     *
     * @param iInvoice
     */
    public void setNotAdded(SSInvoice iInvoice) {
        if (iAdded == null) {
            return;
        }

        Integer iNumber = iInvoice.getNumber();

        if (iNumber != null) {
            iAdded.put(iNumber, false);
        }
    }

    /**
     *
     * @return
     */
    private Map<Integer, Boolean> getAdded() {
        if (iAdded == null) {
            iAdded = new HashMap<>();
        }
        return iAdded;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSInvoice> getInvoices() {
        if (iInvoices == null) {
            iInvoices = new LinkedList<>();
        }

        return iInvoices;
    }

    public List<SSInvoice> getInvoices(LocalDate iDate) {
        List<SSInvoice> iFiltered = new LinkedList<>();

        for (SSInvoice iInvoice : getInvoices()) {

            // Skip the invoice if its already added
            if (isAdded(iInvoice)) {
                continue;
            }

            // The invoice date is before the date
            if (SSInvoiceMath.inPeriod(iInvoice, iDate)) {
                iFiltered.add(iInvoice);
            }
        }
        return iFiltered;
    }

    public Optional<LocalDate> getNextLocalDate() {

        for (SSInvoice iInvoice : getInvoices()) {
            // Skip the invoice if its already added
            if (isAdded(iInvoice)) {
                continue;
            }

            return Optional.ofNullable(iInvoice.getLocalDate());
        }
        return Optional.empty();
    }

    /**
     *
     */
    public void createInvoices() {
        iInvoices = new LinkedList<>();

        if (iPeriod == null || iPeriodStart == null || iPeriodEnd == null
                || iTemplate == null) {
            return;
        }

        LocalDate iDate = this.iDate;
        LocalDate iPeriodStart = this.iPeriodStart;
        LocalDate iPeriodEnd = this.iPeriodEnd;

        DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

        for (int i = 0; i < iCount; i++) {
            SSInvoice iInvoice = new SSInvoice(iTemplate);

            iInvoice.setLocalDate(iDate);
            iInvoice.setDueDate();
            iInvoice.setNumber(i);
            iInvoice.setOrderNumbers(iTemplate.getOrderNumbers());

            if (iAppendPeriod) {
                String strPeriodStart = iPeriodStart.format(iFormat);
                String strPeriodEnd = iPeriodEnd.format(iFormat);

                SSSaleRow iRow = new SSSaleRow();

                iRow.setDescription(
                        String.format(
                                SSBundle.getBundle().getString(
                                        "periodicinvoiceframe.invoiceperiod"),
                                        strPeriodStart,
                                        strPeriodEnd));
                iRow.setQuantity(null);
                iRow.setUnitprice(null);
                iRow.setTaxCode(null);

                iInvoice.getRows().add(iRow);
            }

            if (iAppendInformation) {
                String iInformationText = iInformation;

                if (iInformationText.contains("[FAK]")) {
                    iInformationText = iInformationText.replace("[FAK]",
                            String.valueOf(i + 1));
                }
                if (iInformationText.contains("[TOT]")) {
                    iInformationText = iInformationText.replace("[TOT]",
                            String.valueOf(iCount));
                }

                SSSaleRow iRow = new SSSaleRow();

                iRow.setDescription(iInformationText);
                iRow.setQuantity(null);
                iRow.setUnitprice(null);
                iRow.setTaxCode(null);
                iInvoice.getRows().add(iRow);
            }

            iDate = SSDateMath.addMonths(iDate, iPeriod);
            iPeriodStart = SSDateMath.addMonths(iPeriodStart, iPeriod);
            iPeriodEnd = SSDateMath.addMonths(iPeriodStart, iPeriod).minusDays(1);
            iInvoices.add(iInvoice);
        }

        if (iAdded == null || iAdded.size() != iInvoices.size()) {
            iAdded = new HashMap<>();
            for (SSInvoice iInvoice : iInvoices) {
                Integer iNumber = iInvoice.getNumber();

                iAdded.put(iNumber, false);
            }
        }
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof SSPeriodicInvoice)) {
            return false;
        }
        return iNumber.equals(((SSPeriodicInvoice) obj).iNumber);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.SSPeriodicInvoice");
        sb.append("{iAdded=").append(iAdded);
        sb.append(", iAppendInformation=").append(iAppendInformation);
        sb.append(", iAppendPeriod=").append(iAppendPeriod);
        sb.append(", iCount=").append(iCount);
        sb.append(", iDate=").append(iDate);
        sb.append(", iDescription='").append(iDescription).append('\'');
        sb.append(", iInformation='").append(iInformation).append('\'');
        sb.append(", iInvoices=").append(iInvoices);
        sb.append(", iNumber=").append(iNumber);
        sb.append(", iPeriod=").append(iPeriod);
        sb.append(", iPeriodEnd=").append(iPeriodEnd);
        sb.append(", iPeriodStart=").append(iPeriodStart);
        sb.append(", iTemplate=").append(iTemplate);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Custom deserialization to handle backward compatibility.
     * Pre-migration serialized streams stored {@code iDate}, {@code iPeriodStart}, and
     * {@code iPeriodEnd} as {@code java.util.Date}.  This method reads them as raw
     * objects and converts via {@link SSDateUtil#readLocalDate(Object)}.
     * Fields {@code iAppendInformation} and {@code iInformation} may not exist in
     * older serialized blobs, so they use safe defaults.
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        iNumber = (Integer) fields.get("iNumber", null);
        iTemplate = (SSInvoice) fields.get("iTemplate", null);
        iDate = SSDateUtil.readLocalDate(fields.get("iDate", null));
        iCount = (Integer) fields.get("iCount", null);
        iPeriod = (Integer) fields.get("iPeriod", null);
        iDescription = (String) fields.get("iDescription", null);
        iPeriodStart = SSDateUtil.readLocalDate(fields.get("iPeriodStart", null));
        iPeriodEnd = SSDateUtil.readLocalDate(fields.get("iPeriodEnd", null));
        iAppendPeriod = fields.get("iAppendPeriod", false);
        iAppendInformation = fields.get("iAppendInformation", false);
        iInformation = (String) fields.get("iInformation", null);
        iInvoices = (List<SSInvoice>) fields.get("iInvoices", null);
        iAdded = (Map<Integer, Boolean>) fields.get("iAdded", null);
    }
}
