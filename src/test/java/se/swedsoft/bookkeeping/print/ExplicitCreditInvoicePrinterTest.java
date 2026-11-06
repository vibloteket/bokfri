package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.print.report.sales.SSCreditinvoicePrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitCreditInvoicePrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void explicitReferencesAndCompanyRoundingAvoidSingletonLookups() {
        SSNewCompany company = company();
        SSCustomer customer = new SSCustomer();
        customer.setVATNumber("VAT-EXPLICIT");
        SSInvoice original = new SSInvoice();
        original.setOCRNumber("987654321");
        SSCreditInvoice credit = credit();
        var printer = new SSCreditinvoicePrinter(credit, Locale.forLanguageTag("sv-SE"),
                company, customer, original, List.of());
        assertThat(printer.getParameters()).containsEntry("company", "Explicit credit company")
                .containsEntry("lastvoucher", 0).containsEntry("creditinvoice.vatnumber", "VAT-EXPLICIT")
                .containsEntry("creditinvoice.invoicenumber", "987654321")
                .containsEntry("creditinvoice.invoiceadress.name", "Stored invoice address");
        assertThat((BigDecimal) printer.getParameters().get("creditinvoice.totalsum"))
                .isEqualByComparingTo("225.285");
        company.setRoundingOff(false);
        var rounded = new SSCreditinvoicePrinter(credit, Locale.ENGLISH, company, null, null, List.of());
        assertThat((BigDecimal) rounded.getParameters().get("creditinvoice.totalsum"))
                .isEqualByComparingTo("225");
        assertThat((BigDecimal) rounded.getParameters().get("creditinvoice.rounding"))
                .isEqualByComparingTo("-0.285");
        assertThat(rounded.getParameters()).containsEntry("creditinvoice.invoicenumber", "42");
    }

    @Test
    void originalWithoutOcrFallsBackToStoredNumber() {
        var printer = new SSCreditinvoicePrinter(credit(), Locale.ENGLISH, company(), null, new SSInvoice(), List.of());
        assertThat(printer.getParameters()).containsEntry("creditinvoice.invoicenumber", "42");
    }

    @Test
    void nestedRowsAndPdfUseSnapshotsWithoutLegacyCompanyOrYear() throws Exception {
        var printer = new Rows(credit(), company());
        printer.rows();
        JRDataSource rows = (JRDataSource) printer.getParameters().get("subreport.datasource");
        assertThat(rows.next()).isTrue();
        JRDesignField field = new JRDesignField();
        field.setName("row.description");
        field.setValueClass(String.class);
        assertThat(rows.getFieldValue(field)).isEqualTo("Stored row description");
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> new SSCreditinvoicePrinter(
                credit(), Locale.ENGLISH, company(), null, null, List.of())), temporaryDirectory.resolve("credit.pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("Stored row description", "Stored invoice address", "Explicit credit company");
        }
    }

    @Test
    void explicitProductsPreserveLocalizedDescriptions() throws Exception {
        SSProduct product = new SSProduct();
        product.setNumber("P1");
        product.setDescription(Locale.ENGLISH, "English product description");
        SSCreditInvoice credit = credit();
        credit.getRows().get(0).setProductNr("P1");
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> new SSCreditinvoicePrinter(
                credit, Locale.ENGLISH, company(), null, null, List.of(product))),
                temporaryDirectory.resolve("localized.pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("English product description").doesNotContain("Stored row description");
        }
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit credit company");
        company.setRoundingOff(true);
        return company;
    }

    private static SSCreditInvoice credit() {
        SSCreditInvoice credit = new SSCreditInvoice() {
            @Override public SSInvoice getCrediting() { throw new AssertionError("Implicit invoice lookup"); }
        };
        credit.setNumber(7);
        credit.setCreditingNr(42);
        credit.setTaxFree(false);
        credit.setTaxRate1(new BigDecimal("25"));
        credit.setTaxRate2(BigDecimal.ZERO);
        credit.setTaxRate3(BigDecimal.ZERO);
        credit.getInvoiceAddress().setName("Stored invoice address");
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
        };
        row.setDescription("Stored row description");
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.125"));
        row.setDiscount(new BigDecimal("10"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        credit.getRows().add(row);
        return credit;
    }

    private static class Rows extends SSCreditinvoicePrinter {
        Rows(SSCreditInvoice invoice, SSNewCompany company) {
            super(invoice, Locale.ENGLISH, company, null, null, List.of());
        }
        void rows() { getModel().getValueAt(0, 0); }
    }
}
