package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.calc.SSOCRNumber;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.print.report.sales.SSInvoicePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSOCRInvoicePrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitInvoicePrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void explicitReferencesAndRoundingDoNotReadSingleton() {
        SSCustomer customer = new SSCustomer();
        customer.setVATNumber("VAT-EXPLICIT");
        var printer = new SSInvoicePrinter(invoice(), Locale.ENGLISH, company(true), customer, List.of());
        assertThat(printer.getParameters()).containsEntry("company", "Explicit invoice company")
                .containsEntry("lastvoucher", 0).containsEntry("invoice.vatnumber", "VAT-EXPLICIT")
                .containsEntry("invoice.invoiceadress.name", "Stored invoice address");
        assertThat((BigDecimal) printer.getParameters().get("invoice.totalsum"))
                .isEqualByComparingTo("225.285");
        var rounded = new SSInvoicePrinter(invoice(), Locale.ENGLISH, company(false), null, List.of());
        assertThat((BigDecimal) rounded.getParameters().get("invoice.totalsum"))
                .isEqualByComparingTo("225");
        assertThat((BigDecimal) rounded.getParameters().get("invoice.rounding"))
                .isEqualByComparingTo("-0.285");
    }

    @Test
    void ocrCodeRowUsesExplicitBankAndCompanyRounding() {
        for (boolean rounding : List.of(false, true)) {
            SSInvoice invoice = invoice();
            SSNewCompany company = company(rounding);
            var printer = new SSOCRInvoicePrinter(invoice, Locale.ENGLISH, false, company, null, List.of());
            var expected = new SSOCRInvoicePrinter.SSORCInvoiceCodeRow();
            expected.setReferenceNumber(invoice.getOCRNumber());
            expected.setBankNumber(company.getBankGiroNumber());
            expected.setValue(new BigDecimal(rounding ? "225.285" : "225"));
            assertThat(printer.getParameters()).containsEntry("ocrinvoice.coderow", expected.toString())
                    .containsEntry("company.bankaccount", "123-4567").containsEntry("lastvoucher", 0);
            assertThat((BigDecimal) printer.getParameters().get("invoice.totalsum"))
                    .isEqualByComparingTo(rounding ? "225.285" : "225");
        }
    }

    @Test
    void ocrStillRequiresBankGiroRatherThanInventingPaymentDetails() {
        SSNewCompany company = company(true);
        company.setBankGiroNumber(null);
        assertThatThrownBy(() -> new SSOCRInvoicePrinter(invoice(), Locale.ENGLISH, false,
                company, null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void standardAndOcrVariantsRenderStoredRowsWithoutCompanyOrYearSelections() throws Exception {
        for (boolean cash : List.of(false, true)) {
            SSInvoice invoice = invoice();
            if (cash) invoice.setType(SSInvoiceType.CASH);
            for (String variant : List.of("standard", "ocr", "background")) {
                SSPrinter printer = variant.equals("standard")
                        ? new SSInvoicePrinter(invoice, Locale.ENGLISH, company(true), null, List.of())
                        : new SSOCRInvoicePrinter(invoice, Locale.ENGLISH, variant.equals("background"),
                                company(true), null, List.of());
                String content = pdfText(printer, variant + cash);
                assertThat(content).contains("Stored row description", "Stored invoice address",
                        "Explicit invoice company", "ORDER-SNAPSHOT", "Contains order:", "ORDERS-42");
            }
        }
    }

    @Test
    void localizedProductDescriptionsRemainExplicitForBothVariants() throws Exception {
        SSProduct product = new SSProduct();
        product.setNumber("P1");
        product.setDescription(Locale.ENGLISH, "English product description");
        for (boolean ocr : List.of(false, true)) {
            SSPrinter printer = ocr
                    ? new SSOCRInvoicePrinter(invoice(), Locale.ENGLISH, false, company(true), null, List.of(product))
                    : new SSInvoicePrinter(invoice(), Locale.ENGLISH, company(true), null, List.of(product));
            assertThat(pdfText(printer, "localized" + ocr)).contains("English product description")
                    .doesNotContain("Stored row description");
        }
    }

    private String pdfText(SSPrinter printer, String name) throws Exception {
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(name + ".pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }

    private static SSNewCompany company(boolean rounding) {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit invoice company");
        company.setRoundingOff(rounding);
        company.setBankGiroNumber("123-4567");
        return company;
    }

    private static SSInvoice invoice() {
        SSInvoice invoice = new SSInvoice();
        invoice.setNumber(7);
        invoice.setOCRNumber(SSOCRNumber.getOCRNumber(invoice));
        invoice.setYourOrderNumber("ORDER-SNAPSHOT");
        invoice.setOrderNumbers("ORDERS-42");
        invoice.setTaxFree(false);
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(BigDecimal.ZERO);
        invoice.setTaxRate3(BigDecimal.ZERO);
        invoice.getInvoiceAddress().setName("Stored invoice address");
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
        };
        row.setProductNr("P1");
        row.setDescription("Stored row description");
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.125"));
        row.setDiscount(new BigDecimal("10"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.getRows().add(row);
        return invoice;
    }
}
