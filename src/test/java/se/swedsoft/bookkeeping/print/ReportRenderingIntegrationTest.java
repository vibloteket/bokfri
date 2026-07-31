package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;
import se.swedsoft.bookkeeping.print.report.SSProjectsPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSInvoicePrinter;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for report preview rendering.
 */
@Tag("integration")
class ReportRenderingIntegrationTest {

    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @Test
    void projectReportPreviewImageContainsRenderedContent() throws Exception {
        SSProjectsPrinter printer = new SSProjectsPrinter(List.of(
                new SSNewProject("P-1", "Preview project", "Ensures report pages are not blank")));

        assertPreviewImageContainsRenderedContent(printer);
    }

    @Test
    void invoiceReportPreviewImageContainsRenderedContent() throws Exception {
        SSInvoicePrinter printer = new SSInvoicePrinter(invoice(), Locale.forLanguageTag("sv-SE"));

        assertPreviewImageContainsRenderedContent(printer);
    }

    @Test
    void inpaymentJournalAcceptsFormattedDateFromItsDataModel() throws Exception {
        SSInpayment inpayment = new SSInpayment();
        inpayment.setNumber(1);
        inpayment.setText("Customer payment");
        inpayment.setLocalDate(LocalDate.of(2026, 7, 30));

        SSInpaymentjournalPrinter printer = new SSInpaymentjournalPrinter(
                new ArrayList<>(List.of(inpayment)), 1, LocalDate.of(2026, 7, 31));

        assertPreviewImageContainsRenderedContent(printer);
    }

    @Test
    void outpaymentJournalAcceptsFormattedDateFromItsDataModel() throws Exception {
        SSOutpayment outpayment = new SSOutpayment();
        outpayment.setNumber(1);
        outpayment.setText("Supplier payment");
        outpayment.setLocalDate(LocalDate.of(2026, 7, 30));

        SSOutpaymentjournalPrinter printer = new SSOutpaymentjournalPrinter(
                new ArrayList<>(List.of(outpayment)), 1, LocalDate.of(2026, 7, 31));

        assertPreviewImageContainsRenderedContent(printer);
    }

    private static void assertPreviewImageContainsRenderedContent(SSPrinter printer) throws Exception {
        printer.generateReport();

        JasperPrint jasperPrint = printer.getPrinter();
        Image image = JasperPrintManager.printPageToImage(jasperPrint, 0, 1.0f);
        BufferedImage bufferedImage = toBufferedImage(image);

        assertThat(countNonWhitePixels(bufferedImage)).isGreaterThan(1_000);
    }

    private static SSInvoice invoice() {
        SSInvoice invoice = new SSInvoice();

        invoice.setNumber(1234);
        invoice.setCustomerNr("C-1");
        invoice.setCustomerName("Preview Customer AB");
        invoice.setLocalDate(LocalDate.of(2026, 5, 29));
        invoice.setLocalDueDate(LocalDate.of(2026, 6, 28));
        invoice.setCurrency(new SSCurrency("SEK", "Svenska kronor"));
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(new BigDecimal("12"));
        invoice.setTaxRate3(new BigDecimal("6"));

        SSSaleRow row = new SSSaleRow();

        row.setProductNr("P-1");
        row.setDescription("Preview row");
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.00"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.getRows().add(row);

        return invoice;
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage) {
            return (BufferedImage) image;
        }
        BufferedImage bufferedImage = new BufferedImage(
                image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);

        bufferedImage.getGraphics().drawImage(image, 0, 0, null);
        return bufferedImage;
    }

    private static int countNonWhitePixels(BufferedImage image) {
        int count = 0;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0x00ffffff;

                if (rgb != 0x00ffffff) {
                    count++;
                }
            }
        }
        return count;
    }
}
