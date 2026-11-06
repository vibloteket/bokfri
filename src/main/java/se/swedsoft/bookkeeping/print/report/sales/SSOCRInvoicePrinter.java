package se.swedsoft.bookkeeping.print.report.sales;


import se.swedsoft.bookkeeping.calc.SSOCRNumber;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.gui.util.graphics.SSImage;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;


/**
 * $Id$ 
 *
 */
public class SSOCRInvoicePrinter extends SSInvoicePrinter {

    private SSORCInvoiceCodeRow iCodeRow;

    /**
     *
     * @param iInvoice
     * @param iLocale
     * @param iShowBackground
     */
    public SSOCRInvoicePrinter(SSInvoice iInvoice, Locale iLocale, boolean iShowBackground) {
        initialize(iInvoice, iLocale, iShowBackground);
    }

    /**
     * Creates an OCR invoice report without legacy storage access.
     *
     * @param invoice invoice with an OCR reference already assigned
     * @param locale report language
     * @param showBackground whether to include the OCR form background
     * @param company selected company and bank-giro/rounding settings
     * @param customer current customer, or null if no longer present
     * @param products company products for localized descriptions
     */
    public SSOCRInvoicePrinter(SSInvoice invoice, Locale locale, boolean showBackground,
            SSNewCompany company, SSCustomer customer, List<SSProduct> products) {
        super(company, customer, products);
        initialize(invoice, locale, showBackground);
    }

    private void initialize(SSInvoice iInvoice, Locale iLocale, boolean iShowBackground) {
        this.iInvoice = iInvoice;
        this.iLocale = iLocale;
        super.iLocale = iLocale;

        ResourceBundle iBundle = ResourceBundle.getBundle("reports.invoicereport", iLocale);

        setBundle(iBundle);
        setLocale(iLocale);

        setPageHeader("sales/sale.header.jrxml");
        setPageFooter("sales/ocrinvoice.jrxml");
        setDetail("sales/ocrinvoice.jrxml");
        setColumnHeader("sales/ocrinvoice.jrxml");
        setColumnFooter("sales/ocrinvoice.jrxml");

        if (iShowBackground) {
            setBackground("sales/ocrinvoice.jrxml");
        }

        setMargins(0, 0, 0, 0);

        iCodeRow = new SSORCInvoiceCodeRow();
        iCodeRow.setReferenceNumber(iInvoice.getOCRNumber());
        iCodeRow.setBankNumber(reportCompany().getBankGiroNumber());
        iCodeRow.setValue(SSInvoiceMath.getTotalSum(iInvoice, reportCompany().isRoundingOff()));

        addParameters();

    }

    /**
     *
     */
    @Override
    protected void addParameters() {
        super.addParameters();

        addParameter("ocrinvoice.coderow", iCodeRow.toString());
        addParameter("ocrinvoice.background", SSImage.getImage("OCRBackground"));
    }

    /**
     *
     */
    public static class SSORCInvoiceCodeRow {
        private char[] iCharacters;

        /**
         *
         */
        public SSORCInvoiceCodeRow() {
            iCharacters = new char[84];

            for (int i = 0; i < 84; i++) {
                iCharacters[i] = ' ';
            }
            // Förtryckta fält
            iCharacters[83] = 'H';

            /* iCharacters[79] = '#';
             iCharacters[51] = '#';
             iCharacters[34] = '>';
             iCharacters[ 8] = '#';
             iCharacters[ 7] = '4';
             iCharacters[ 6] = '1';
             iCharacters[ 5] = '#';*/
        }

        /**
         *
         * @param iNumber
         */
        public void setReferenceNumber(String iNumber) {
            String iText = iNumber.trim();

            for (int i = 0; i < iText.length(); i++) {
                iCharacters[53 + i] = iText.charAt(iText.length() - i - 1);
            }
        }

        /**
         *
         * @param iValue
         */
        public void setValue(BigDecimal iValue) {
            DecimalFormat iFormat = new DecimalFormat("0.00");
            String iText = iFormat.format(iValue.doubleValue());

            for (int i = 0; i < iText.length(); i++) {
                iCharacters[40 + i] = iText.charAt(iText.length() - i - 1);
            }
            iText = iText.replaceAll("\\.", "");
            iText = iText.replaceAll(",", "");

            iCharacters[42] = ' ';
            iCharacters[36] = SSOCRNumber.getCheckSum(iText);
        }

        /**
         *
         * @param iNumber
         */
        public void setBankNumber(String iNumber) {
            String iText = iNumber.replaceAll("-", "");

            int iLength = iText.length();

            for (int i = 0; i < iLength; i++) {
                iCharacters[9 + i] = iText.charAt(iLength - i - 1);
            }
        }

        /**
         *
         * @return
         */
        public String toString() {
            String iText = "";

            for (int i = 83; i > 0; i--) {
                iText = iText + iCharacters[i];
            }
            return iText;
        }

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.sales.SSOCRInvoicePrinter");
        sb.append("{iCodeRow=").append(iCodeRow);
        sb.append('}');
        return sb.toString();
    }
}
