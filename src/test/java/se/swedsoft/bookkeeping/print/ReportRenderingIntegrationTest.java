package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;
import se.swedsoft.bookkeeping.print.report.SSProjectsPrinter;
import se.swedsoft.bookkeeping.print.report.SSSaleReportPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter;
import org.fribok.bookkeeping.service.invoice.InvoiceService;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.print.report.sales.SSInvoicePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSOCRInvoicePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSReminderPrinter;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void saleReportAcceptsDecimalProductCounts() throws Exception {
        SSSaleReportPrinter printer = new SSSaleReportPrinter(
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31),
                SSSaleReportPrinter.SortingMode.Product, true);

        assertPreviewImageContainsRenderedContent(printer);
    }

    @Test
    void invoiceReportPreviewImageContainsRenderedContent() throws Exception {
        SSInvoicePrinter printer = new SSInvoicePrinter(invoice(), Locale.forLanguageTag("sv-SE"));

        assertPreviewImageContainsRenderedContent(printer);

        List<PrintedText> printedText = allPrintedText(printer.getPrinter());
        assertThat(printedText.stream().map(PrintedText::text))
                .doesNotContain("Fortsätter")
                .doesNotContain("invoicereport.continuing");

        PrintedText invoiceRow = findPrintedText(printedText, "Preview row");
        PrintedText netSum = findPrintedText(printedText, "Nettosumma");
        assertThat(netSum.y()).isGreaterThanOrEqualTo(invoiceRow.bottom());
    }

    @Test
    void multipageInvoicePrintsTotalsOnlyOnLastPage() throws Exception {
        SSInvoice invoice = invoice();
        invoice.getRows().clear();
        for (int rowNumber = 1; rowNumber <= 45; rowNumber++) {
            SSSaleRow row = new SSSaleRow();
            row.setDescription("Multipage row " + rowNumber);
            row.setQuantity(1);
            row.setUnitprice(new BigDecimal(rowNumber));
            row.setTaxCode(SSTaxCode.TAXRATE_1);
            invoice.getRows().add(row);
        }
        SSInvoicePrinter printer = new SSInvoicePrinter(invoice, Locale.forLanguageTag("sv-SE"));

        printer.generateReport();

        JasperPrint print = printer.getPrinter();
        assertThat(print.getPages()).hasSizeGreaterThanOrEqualTo(2);
        List<PrintedText> firstPage = printedTextOnPage(print, 0);
        List<PrintedText> lastPage = printedTextOnPage(print, print.getPages().size() - 1);
        assertThat(firstPage.stream().map(PrintedText::text)).doesNotContain("Nettosumma", "ATT BETALA");
        assertThat(lastPage.stream().map(PrintedText::text)).contains("Nettosumma", "ATT BETALA");
        assertThat(allPrintedText(print).stream().map(PrintedText::text))
                .contains("Multipage row 1", "Multipage row 45");
    }

    @Test
    void invoiceServiceExportsHeadlessPdf() throws Exception {
        Path output = Files.createTempFile("bokfri-invoice-", ".pdf");
        Files.delete(output);

        new InvoiceService(SSDB.getInstance()).exportPdf(
                invoice(), output, Locale.forLanguageTag("sv-SE"), false);

        try {
            assertThat(Files.size(output)).isGreaterThan(1_000);
            assertThat(Files.readAllBytes(output)).startsWith((byte) '%', (byte) 'P',
                    (byte) 'D', (byte) 'F', (byte) '-');
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void swedishOcrInvoiceKeepsInvoiceNumberAndPlacesTotalsAfterRows() throws Exception {
        SSInvoice invoice = invoice();
        invoice.setOCRNumber("12346");
        SSOCRInvoicePrinter printer = new SSOCRInvoicePrinter(
                invoice, Locale.forLanguageTag("sv-SE"), false);

        assertPreviewImageContainsRenderedContent(printer);

        List<PrintedText> printedText = allPrintedText(printer.getPrinter());
        assertThat(printedText.stream().map(PrintedText::text))
                .contains("Fakturanummer", "1234", "Leveransadress")
                .doesNotContain("Invoice number", "12346");

        PrintedText invoiceRow = findPrintedText(printedText, "Preview row");
        PrintedText netSum = findPrintedText(printedText, "Nettosumma");
        assertThat(netSum.y()).isGreaterThanOrEqualTo(invoiceRow.bottom());
    }

    @Test
    void paymentReminderKeepsDifficultInvoiceValuesInSeparateTextAreas() throws Exception {
        SSInvoice invoice = invoice();
        invoice.setOCRNumber("1234567890123456789012345");
        invoice.setLocalDate(LocalDate.of(2023, 1, 1));
        invoice.setLocalDueDate(LocalDate.of(2023, 1, 2));
        invoice.setNumRemainders(11);
        invoice.setDelayInterest(new BigDecimal("12.75"));

        SSCustomer customer = new SSCustomer();
        customer.setNumber("C-REMINDER");
        customer.setName("Reminder Customer AB");
        customer.setInvoiceCurrency(new SSCurrency("SEK", "Svenska kronor"));
        customer.getInvoiceAddress().setName(customer.getName());

        HashMap<Integer, BigDecimal> previousSaldos = SSInvoiceMath.iSaldoMap;

        try {
            SSInvoiceMath.iSaldoMap = new HashMap<>();
            SSInvoiceMath.iSaldoMap.put(invoice.getNumber(), new BigDecimal("1234567.89"));
            SSReminderPrinter printer = new SSReminderPrinter(List.of(invoice), customer,
                    Locale.forLanguageTag("sv-SE"), LocalDate.of(2026, 5, 20));

            assertPreviewImageContainsRenderedContent(printer);

            List<PrintedText> printedText = allPrintedText(printer.getPrinter());
            PrintedText sectionStart = findPrintedText(printedText, "Fakturauppgifter");
            PrintedText total = findPrintedText(printedText, "TOTAL FORDRAN");
            List<PrintedText> invoiceBlock = printedText.stream()
                    .filter(text -> text.y() >= sectionStart.y() && text.bottom() <= total.y())
                    .filter(text -> !text.text().isBlank())
                    .toList();

            assertThat(printedText.stream().map(PrintedText::text)
                    .map(text -> text.replace('\u00a0', ' ')))
                    .contains("1234567890123456789012345", "1234", "12,75 %", "1 234 567,89");
            assertThat(overlappingTextPairs(invoiceBlock)).isEmpty();
        } finally {
            SSInvoiceMath.iSaldoMap = previousSaldos;
        }
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

    private static List<PrintedText> allPrintedText(JasperPrint print) {
        List<PrintedText> text = new ArrayList<>();

        print.getPages().forEach(page -> collectPrintedText(page.getElements(), text, 0, 0));
        return text;
    }

    private static List<PrintedText> printedTextOnPage(JasperPrint print, int pageIndex) {
        List<PrintedText> text = new ArrayList<>();
        collectPrintedText(print.getPages().get(pageIndex).getElements(), text, 0, 0);
        return text;
    }

    private static void collectPrintedText(
            List<JRPrintElement> elements, List<PrintedText> text, int offsetX, int offsetY) {
        for (JRPrintElement element : elements) {
            int x = offsetX + element.getX();
            int y = offsetY + element.getY();

            if (element instanceof JRPrintText printText) {
                text.add(new PrintedText(printText.getFullText(), x, y,
                        element.getWidth(), element.getHeight()));
            } else if (element instanceof JRPrintFrame frame) {
                collectPrintedText(frame.getElements(), text, x, y);
            }
        }
    }

    private static List<String> overlappingTextPairs(List<PrintedText> printedText) {
        List<String> overlaps = new ArrayList<>();

        for (int firstIndex = 0; firstIndex < printedText.size(); firstIndex++) {
            PrintedText first = printedText.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < printedText.size(); secondIndex++) {
                PrintedText second = printedText.get(secondIndex);

                if (first.overlaps(second)) {
                    overlaps.add(first.text() + " / " + second.text());
                }
            }
        }
        return overlaps;
    }

    private static PrintedText findPrintedText(List<PrintedText> printedText, String expected) {
        return printedText.stream()
                .filter(text -> expected.equals(text.text()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing printed text: " + expected));
    }

    private record PrintedText(String text, int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean overlaps(PrintedText other) {
            return x < other.right() && right() > other.x()
                    && y < other.bottom() && bottom() > other.y();
        }
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
