package se.swedsoft.bookkeeping.data;


import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.calc.util.SSAutoIncrement;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;


/**
 * User: Andreas Lago
 * Date: 2006-mar-30
 * Time: 12:24:20
 */
public class SSCreditInvoice extends SSInvoice {
    // Constant for serialization versioning.
    static final long serialVersionUID = 1L;

    // The sales this sales is crediting, only used for credit invoices
    private Integer iCreditingNr;

    // The transient crediting sales
    protected transient SSInvoice iCrediting;

    // //////////////////////////////////////////////////

    /**
     * Default constructor
     */
    public SSCreditInvoice() {
        iCurrencyRate = new BigDecimal(1);
        iVoucher = new SSVoucher();
    }

    /**
     *
     * @param iCrediting
     */
    public SSCreditInvoice(SSInvoice iCrediting) {
        copyFrom(iCrediting);

        this.iCrediting = iCrediting;
        iCreditingNr = iCrediting.getNumber();
        iDate = SSDateUtil.today();
        iEntered = false;
        iPrinted = false;

        SSNewCompany iCompany = SSDB.getInstance().getCurrentCompany();

        if (iCompany != null) {
            setText(iCompany.getStandardText(SSStandardText.Creditinvoice).orElse(null));
        }

        generateVoucher();
    }

    // //////////////////////////////////////////////////

    /**
     * Copy constructor
     *
     * @param iInvoice
     */
    public SSCreditInvoice(SSCreditInvoice iInvoice) {
        copyFrom(iInvoice);
    }

    /**
     * Clone constructor
     *
     * @param iInvoice
     * @param iNumber
     */
    public SSCreditInvoice(SSCreditInvoice iInvoice, Integer iNumber) {
        copyFrom(iInvoice);

        this.iNumber = iNumber;
    }

    // //////////////////////////////////////////////////

    /**
     * @param iInvoice
     */
    public void copyFrom(SSCreditInvoice iInvoice) {
        super.copyFrom(iInvoice);

        iCreditingNr = iInvoice.iCreditingNr;
        iCurrencyRate = iInvoice.iCurrencyRate;
        iCrediting = iInvoice.iCrediting;
        iVoucher = new SSVoucher(iInvoice.iVoucher);
    }

    // //////////////////////////////////////////////////

    /**
     * Auto increment the number
     */
    @Override
    public void doAutoIncrecement() {
        List<SSCreditInvoice> iCreditInvoices = SSDB.getInstance().getCreditInvoices();

        int iNumber = SSDB.getInstance().getAutoIncrement().orElse(new SSAutoIncrement()).getNumber("creditinvoice");

        for (SSCreditInvoice iCreditInvoice: iCreditInvoices) {

            if (iCreditInvoice.getNumber() != null && iCreditInvoice.getNumber() > iNumber) {
                iNumber = iCreditInvoice.getNumber();
            }
        }
        this.iNumber = iNumber + 1;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public Integer getCreditingNr() {
        return iCreditingNr;
    }

    /**
     *
     * @param iCreditingNr
     */
    public void setCreditingNr(Integer iCreditingNr) {
        this.iCreditingNr = iCreditingNr;
        iCrediting = null;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    @Override
    public BigDecimal getCurrencyRate() {
        return iCurrencyRate;
    }

    /**
     *
     * @param iCurrencyRate
     */
    @Override
    public void setCurrencyRate(BigDecimal iCurrencyRate) {
        this.iCurrencyRate = iCurrencyRate;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    @Override
    public SSVoucher getVoucher() {
        return iVoucher;
    }

    /**
     *
     * @param iVoucher
     */
    @Override
    public void setVoucher(SSVoucher iVoucher) {
        this.iVoucher = iVoucher;
    }

    // //////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public SSInvoice getCrediting() {
        return getCrediting(SSDB.getInstance().getInvoices());
    }

    /**
     * Get the sales this sales is crediting
     *
     * @param iInvoices
     * @return the sales
     */
    public SSInvoice getCrediting(List<SSInvoice> iInvoices) {
        if (iCrediting == null && iCreditingNr != null) {
            for (SSInvoice iCurrent : iInvoices) {
                if (iCreditingNr.equals(iCurrent.getNumber())) {
                    iCrediting = iCurrent;
                }
            }
        }
        return iCrediting;
    }

    /**
     * Set the sales this sales is crediting
     *
     * @param iCrediting
     */
    public void setCrediting(SSInvoice iCrediting) {
        this.iCrediting = iCrediting;
        iCreditingNr = iCrediting != null ? iCrediting.getNumber() : null;
    }

    /**
     * Returns if this credit sales is crediting the specified sales
     *
     * @param iCrediting
     * @return
     */
    public boolean isCrediting(SSInvoice iCrediting) {
        boolean answer = false;

        if (iCrediting != null) {
            answer = iCrediting.getNumber().equals(iCreditingNr);
        }
        return answer;
    }

    /**
     * Returns if this credit sales is crediting the specified sales
     *
     * @param iCrediting
     * @return
     */
    public boolean isCrediting(Integer iCrediting) {
        return iCrediting != null && iCrediting.equals(iCreditingNr);
    }

    // //////////////////////////////////////////////////

    /**
     *
     */
    @Override
    public SSVoucher generateVoucher() {
        return generateVoucher(SSDB.getInstance().getCurrentAccountPlan(),
                SSDB.getInstance().getCurrentCompany().isRoundingOff(),
                SSDB.getInstance().getProjects(), SSDB.getInstance().getResultUnits());
    }

    /**
     * Generates the booking voucher with collaborators supplied explicitly, so the
     * calculation works without the global database singleton (normalized storage).
     */
    @Override
    public SSVoucher generateVoucher(SSAccountPlan iAccountPlan, boolean roundingOff,
            List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        String iDescription = SSBundle.getBundle().getString(
                "creditinvoiceframe.voucherdescription");

        iVoucher = new SSVoucher();
        iVoucher.setLocalDate(SSDateUtil.today());
        iVoucher.setNumber(0);
        iVoucher.setDescription(String.format(iDescription, iNumber));

        // Get the total sum for the sales
        BigDecimal iTotalSum = SSInvoiceMath.getTotalSum(this, roundingOff);
        // Get the tax sums for the sales
        Map<SSTaxCode, BigDecimal> iTaxSum = SSInvoiceMath.getTaxSum(this);
        // Get the required rounding for the sales
        BigDecimal iRoundingSum = SSInvoiceMath.getRounding(this, roundingOff);

        // Add the total sum to the voucher
        if (iType == SSInvoiceType.NORMAL) {
            iVoucher.addVoucherRow(
                    getDefaultAccount(iAccountPlan, SSDefaultAccount.CustomerClaim).orElse(null), null,
                    iTotalSum);
        }
        if (iType == SSInvoiceType.CASH) {
            iVoucher.addVoucherRow(getDefaultAccount(iAccountPlan, SSDefaultAccount.Cash).orElse(null),
                    null, iTotalSum);
        }

        // Add the rounding
        if (!roundingOff) {
            iVoucher.addVoucherRow(
                    getDefaultAccount(iAccountPlan, SSDefaultAccount.Rounding).orElse(null),
                    iRoundingSum);
        }

        // Add the tax if not tax free
        if (!iTaxFree) {
            // Add the tax 1
            iVoucher.addVoucherRow(getDefaultAccount(iAccountPlan, SSDefaultAccount.Tax1).orElse(null),
                    iTaxSum.get(SSTaxCode.TAXRATE_1), null);
            // Add the tax 2
            iVoucher.addVoucherRow(getDefaultAccount(iAccountPlan, SSDefaultAccount.Tax2).orElse(null),
                    iTaxSum.get(SSTaxCode.TAXRATE_2), null);
            // Add the tax 3
            iVoucher.addVoucherRow(getDefaultAccount(iAccountPlan, SSDefaultAccount.Tax3).orElse(null),
                    iTaxSum.get(SSTaxCode.TAXRATE_3), null);
        }

        // Add all products
        for (SSSaleRow iRow : iRows) {
            SSVoucherRow iVoucherRow = new SSVoucherRow();

            iVoucherRow.setDebet(iRow.getSum().orElse(null));
            iVoucherRow.setAccount(iRow.getAccount(iAccountPlan.getAccounts()));
            iVoucherRow.setProject(iRow.getProject(projects));
            iVoucherRow.setResultUnit(iRow.getResultUnit(resultUnits));
            if (iVoucherRow.getAccountNr() != null) {
                iVoucher.addVoucherRow(iVoucherRow);
            }
        }

        for (SSVoucherRow iRow : iVoucher.getRows()) {
            if (iRow.isDebet()) {
                if (iRow.getDebet().compareTo(new BigDecimal(0)) == -1) {
                    iRow.setCredit(iRow.getDebet().negate());
                    iRow.setDebet(null);
                }
            } else {
                if (iRow.getCredit().compareTo(new BigDecimal(0)) == -1) {
                    iRow.setDebet(iRow.getCredit().negate());
                    iRow.setCredit(null);
                }
            }
        }
        // Convert all rows to the local currency
        if (iCurrencyRate != null) {
            SSVoucherMath.multiplyRowsBy(iVoucher, iCurrencyRate);
        }

        iVoucher = SSVoucherMath.compress(iVoucher);

        return iVoucher;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof SSCreditInvoice)) {
            return false;
        }
        return iNumber.equals(((SSCreditInvoice) obj).getNumber());
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.SSCreditInvoice");
        sb.append("{iCrediting=").append(iCrediting);
        sb.append(", iCreditingNr=").append(iCreditingNr);
        sb.append('}');
        return sb.toString();
    }
}
