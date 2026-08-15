package se.swedsoft.bookkeeping.importexport.sie;


import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundleString;
import se.swedsoft.bookkeeping.importexport.sie.fields.SIEEntry;
import se.swedsoft.bookkeeping.importexport.sie.util.*;
import se.swedsoft.bookkeeping.importexport.util.SSExportException;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-20
 * Time: 14:16:55
 */
public class SSSIEExporter {    private static final Logger LOG = LoggerFactory.getLogger(SSSIEExporter.class);


    private List<String> iLines;

    private SIEType iType;

    private String iComment;

    private List<SIEExportAdjustment> iAdjustments = new ArrayList<>();

    private boolean iAllowRoundingAdjustments;

    /**
     *
     * @param pType
     */
    public SSSIEExporter(SIEType pType) {
        iLines = new LinkedList<>();
        iType = pType;
        iComment = null;
    }

    /**
     *
     * @param pType
     * @param pComment
     */
    public SSSIEExporter(SIEType pType, String pComment) {
        iLines = new LinkedList<>();
        iType = pType;
        iComment = pComment;
    }

    /**
     *
     * @param pFile
     * @throws SSExportException
     */
    public void exportSIE(File pFile) throws SSExportException {
        SSNewAccountingYear iYearData = SSDB.getInstance().getCurrentYear();

        // Test so we have an active year
        if (iYearData == null) {
            throw new SSExportException(SSBundleString.getString("sieexport.noyear"));
        }

        SSNewCompany iCompany = SSDB.getInstance().getCurrentCompany();

        // Test so we have an active company
        if (iCompany == null) {
            throw new SSExportException(SSBundleString.getString("sieexport.nocompany"));
        }

        iLines.clear();
        iAdjustments = new ArrayList<>(findRequiredAdjustments());
        if (!iAdjustments.isEmpty() && !iAllowRoundingAdjustments) {
            throw new SSExportException(iAdjustments.size()
                    + " voucher(s) need SIE-only rounding rows. Approve rounding adjustments "
                    + "to continue; the stored vouchers will not be changed.");
        }

        // Get the exporter factory
        SIEFactory iFactory = SIEFactory.getExportInstance(iType);

        // LOG.info(iFactory.toString());

        for (SIELabel iLabel : iFactory.getLabels()) {
            SIEEntry iEntry = iLabel.getEntry();

            if (iEntry == null) {
                continue;
            }

            SIEWriter iWriter = new SIEWriter();

            if (iEntry.exportEntry(this, iWriter, iYearData)) {

                if (iWriter.getLines().isEmpty()) {
                    throw new RuntimeException(
                            "Entry reported data but no lines found: " + iEntry);
                }

                iLines.addAll(iWriter.getLines());
            }
        }
        writeFile(pFile);
    }

    /**
     *
     * @param pFile
     * @throws SSImportException
     * @throws SSExportException
     */
    protected void readFile(File pFile) throws SSExportException {
        try {
            iLines = SIEFile.readFile(pFile);
        } catch (FileNotFoundException ex) {
            LOG.error("Unexpected error", ex);
            throw new SSExportException(ex.getMessage());
        } catch (IOException ex) {
            LOG.error("Unexpected error", ex);
            throw new SSExportException(ex.getMessage());
        }
    }

    /**
     *
     * @param pFile
     * @throws SSImportException
     * @throws SSExportException
     */
    private void writeFile(File pFile) throws SSExportException {
        try {
            SIEFile.writeFile(pFile, iLines);
        } catch (FileNotFoundException ex) {
            LOG.error("Unexpected error", ex);
            throw new SSExportException(ex.getMessage());
        } catch (IOException ex) {
            LOG.error("Unexpected error", ex);
            throw new SSExportException(ex.getMessage());
        }
    }

    /**
     *
     * @param pType
     */
    public void setType(SIEType pType) {
        iType = pType;

    }

    /**
     *
     * @return
     */
    public SIEType getType() {
        return iType;
    }

    /**
     *
     * @return
     */
    public String getComment() {
        return iComment;
    }

    /**
     *
     * @return
     */
    public List<String> getLines() {
        return iLines;
    }

    /** Finds rounding rows that would be needed without writing an export file. */
    public List<SIEExportAdjustment> findRequiredAdjustments() {
        SSNewCompany company = SSDB.getInstance().getCurrentCompany();
        if (company == null) {
            return List.of();
        }
        int account = company.getDefaultAccount(
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.Rounding);
        var roundingAccount = SSDB.getInstance().getCurrentAccountPlan().getAccount(account);
        List<SIEExportAdjustment> adjustments = SSDB.getInstance().getVouchers().stream()
                .map(voucher -> new SIEExportAdjustment(voucher.getNumber(), account,
                        SIERounding.voucherAdjustment(voucher)))
                .filter(adjustment -> adjustment.amount().signum() != 0).toList();
        if (!adjustments.isEmpty() && roundingAccount == null) {
            throw new SSExportException("SIE rounding account " + account
                    + " does not exist in the selected accounting year");
        }
        return adjustments;
    }

    /** Allows required non-persistent rounding rows to be included in the export. */
    public void setAllowRoundingAdjustments(boolean allowRoundingAdjustments) {
        iAllowRoundingAdjustments = allowRoundingAdjustments;
    }

    /** Adjustments added only to SIE output to balance two-decimal voucher rows. */
    public List<SIEExportAdjustment> getAdjustments() {
        return List.copyOf(iAdjustments);
    }

    /** One non-persistent SIE rounding row. */
    public record SIEExportAdjustment(Integer voucherNumber, int account, BigDecimal amount) {}

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter");
        sb.append("{iComment='").append(iComment).append('\'');
        sb.append(", iLines=").append(iLines);
        sb.append(", iType=").append(iType);
        sb.append('}');
        return sb.toString();
    }
}
