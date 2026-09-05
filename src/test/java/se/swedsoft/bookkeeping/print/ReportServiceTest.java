package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;

import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for the UI-independent report boundary. */
@Tag("integration")
class ReportServiceTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @Test
    void rendersAndExportsInvoiceWithoutSwing() throws Exception {
        ReportService service = new ReportService();
        RenderedReport report = service.renderInvoice(invoice(), Locale.forLanguageTag("sv-SE"));
        Path output = temporaryDirectory.resolve("nested/invoice.pdf");

        Path exported = service.exportPdf(report, output, false);

        assertThat(report.pageCount()).isPositive();
        assertThat(exported).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(exported)).startsWith(
                (byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
    }

    @Test
    void exportRefusesExistingFileUnlessOverwriteIsEnabled() throws Exception {
        ReportService service = new ReportService();
        RenderedReport report = service.renderInvoice(invoice(), Locale.forLanguageTag("sv-SE"));
        Path output = Files.writeString(temporaryDirectory.resolve("invoice.pdf"), "existing");

        assertThatThrownBy(() -> service.exportPdf(report, output, false))
                .isInstanceOf(FileAlreadyExistsException.class);

        service.exportPdf(report, output, true);
        assertThat(Files.readAllBytes(output)).startsWith(
                (byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
    }

    @Test
    void rendersRepresentativeFinancialReportWithoutSwingOrGlobalYearLookup() {
        SSNewAccountingYear year = SSDB.getInstance().getCurrentYear();
        ReportService service = new ReportService();

        RenderedReport report = service.renderBalance(
                year, year.getLocalFrom(), year.getLocalTo());

        assertThat(report.pageCount()).isPositive();
    }

    private static SSInvoice invoice() {
        SSInvoice invoice = new SSInvoice();
        invoice.setNumber(12001);
        invoice.setCustomerNr("C-12");
        invoice.setCustomerName("Report Service Customer AB");
        invoice.setLocalDate(LocalDate.of(2026, 9, 4));
        invoice.setLocalDueDate(LocalDate.of(2026, 10, 4));
        invoice.setCurrency(new SSCurrency("SEK", "Svenska kronor"));
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(new BigDecimal("12"));
        invoice.setTaxRate3(new BigDecimal("6"));
        SSSaleRow row = new SSSaleRow();
        row.setProductNr("P-12");
        row.setDescription("Report service row");
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal("125.00"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.getRows().add(row);
        return invoice;
    }
}
