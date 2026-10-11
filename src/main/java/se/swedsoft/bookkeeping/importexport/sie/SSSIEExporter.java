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

    private ExportContext context;

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
        SSNewAccountingYear iYearData = context == null
                ? SSDB.getInstance().getCurrentYear() : context.year();

        // Test so we have an active year
        if (iYearData == null) {
            throw new SSExportException(SSBundleString.getString("sieexport.noyear"));
        }

        SSNewCompany iCompany = getCompany();

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

    /** Explicit, hydrated data for singleton-free export. Previous year may be absent. */
    public record ExportContext(SSNewCompany company, SSNewAccountingYear year,
            SSNewAccountingYear previousYear,
            List<se.swedsoft.bookkeeping.data.SSNewProject> projects,
            List<se.swedsoft.bookkeeping.data.SSNewResultUnit> resultUnits) {}

    /** Binds explicit data; legacy callers leave the context unset. */
    public void setContext(ExportContext context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    /** Returns the selected company. */
    public SSNewCompany getCompany() {
        return context == null ? SSDB.getInstance().getCurrentCompany() : context.company();
    }

    /** Returns the selected hydrated year. */
    public SSNewAccountingYear getYear() {
        return context == null ? SSDB.getInstance().getCurrentYear() : context.year();
    }

    /** Returns the adjacent preceding year, or null when absent. */
    public SSNewAccountingYear getPreviousYear() {
        return context == null ? SSDB.getInstance().getPreviousYear().orElse(null) : context.previousYear();
    }

    /** Returns the selected year vouchers. */
    public List<se.swedsoft.bookkeeping.data.SSVoucher> getVouchers() {
        return context == null ? SSDB.getInstance().getVouchers() : context.year().getVouchers();
    }

    /** Returns the company project register. */
    public List<se.swedsoft.bookkeeping.data.SSNewProject> getProjects() {
        return context == null ? SSDB.getInstance().getProjects() : context.projects();
    }

    /** Returns the company result-unit register. */
    public List<se.swedsoft.bookkeeping.data.SSNewResultUnit> getResultUnits() {
        return context == null ? SSDB.getInstance().getResultUnits() : context.resultUnits();
    }

    /** Computes closing balances with year-specific accounts only in explicit-context mode. */
    public java.util.Map<se.swedsoft.bookkeeping.data.SSAccount, BigDecimal> getOutBalance(
            SSNewAccountingYear year) {
        return context == null ? se.swedsoft.bookkeeping.calc.SSBalanceCalculator.getOutBalance(year)
                : se.swedsoft.bookkeeping.calc.SSBalanceCalculator.getOutBalance(
                        year, year.getVouchers(), year.getAccountPlan().getAccounts());
    }

    /** Last voucher date, falling back to the selected year start when empty. */
    public java.time.LocalDate getCoverageDate() {
        if (context == null) {
            return se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getNextVoucherLocalDate();
        }
        return getVouchers().stream().map(se.swedsoft.bookkeeping.data.SSVoucher::getLocalDate)
                .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder())
                .orElse(getYear().getLocalFrom());
    }

    /** Finds rounding rows that would be needed without writing an export file. */
    public List<SIEExportAdjustment> findRequiredAdjustments() {
        SSNewCompany company = getCompany();
        if (company == null) {
            return List.of();
        }
        int account = company.getDefaultAccount(
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.Rounding);
        var roundingAccount = getYear().getAccountPlan().getAccount(account);
        List<SIEExportAdjustment> adjustments = getVouchers().stream()
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
