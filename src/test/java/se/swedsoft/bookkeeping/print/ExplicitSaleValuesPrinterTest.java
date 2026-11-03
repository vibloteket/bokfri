package se.swedsoft.bookkeeping.print;

import org.fribok.bookkeeping.service.SaleValuesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSSalevaluesPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitSaleValuesPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void fullMonthBucketsRetainRatesCreditsNullDatesAndMissingAmounts() {
        List<SaleValuesData.Row> tenders = List.of(row("2024-02-01", "10.25", "2"),
                row("2024-02-29", "20", "3"), row("2024-03-01", "999", "1"),
                row(null, "999", "1"), row("2024-02-10", null, null));
        SaleValuesData values = new SaleValuesData(tenders,
                List.of(row("2024-02-15", "7.25", "4")),
                List.of(row("2024-02-10", "100", "2")),
                List.of(row("2024-02-20", "30", "3")));
        SSDefaultTableModel model = new Probe(values, "2024-02-10", "2024-02-20").model();
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 1)).isEqualTo(80.5);
        assertThat(model.getValueAt(0, 2)).isEqualTo(29.0);
        assertThat(model.getValueAt(0, 3)).isEqualTo(110.0);
    }

    @Test
    void yearRolloverAndFirstDayEndPreserveExistingMonthSplitting() {
        SaleValuesData values = new SaleValuesData(List.of(row("2023-12-31", "5", "1"),
                row("2024-01-01", "7", "1"), row("2024-02-01", "999", "1")),
                List.of(), List.of(), List.of());
        SSDefaultTableModel model = new Probe(values, "2023-12-15", "2024-02-01").model();
        assertThat(model.getRowCount()).isEqualTo(2);
        assertThat(model.getValueAt(0, 1)).isEqualTo(5.0);
        assertThat(model.getValueAt(1, 1)).isEqualTo(7.0);
        assertThat(model.getValueAt(1, 2)).isEqualTo(0.0);
        assertThat(model.getValueAt(1, 3)).isEqualTo(0.0);
    }

    @Test
    void projectionCopiesListsAndRetainsDoubleSummation() {
        List<SaleValuesData.Row> rows = new ArrayList<>(List.of(row("2024-02-01", "0.1", "3"),
                row("2024-02-02", "0.2", "2")));
        SaleValuesData values = new SaleValuesData(rows, List.of(), List.of(), List.of());
        rows.clear();
        assertThat(values.tenders()).hasSize(2);
        assertThatThrownBy(() -> values.tenders().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new Probe(values, "2024-02-01", "2024-02-29").model().getValueAt(0, 1))
                .isEqualTo(0.1 * 3 + 0.2 * 2);
    }

    @Test
    void emptyAndPopulatedPdfsRenderExplicitHeadersAndSummary() throws Exception {
        for (boolean populated : List.of(false, true)) {
            SaleValuesData values = new SaleValuesData(populated
                    ? List.of(row("2024-02-01", "123.45", "1")) : List.of(),
                    List.of(), List.of(), List.of());
            Probe printer = new Probe(values, "2024-02-01", "2024-02-29");
            ReportService reports = new ReportService();
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("values-" + populated + ".pdf"), false);
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                assertThat(text).contains("Explicit sale values company");
                if (populated) assertThat(text).containsPattern("123[.,]45");
            }
        }
    }

    private static SaleValuesData.Row row(String date, String amount, String rate) {
        return new SaleValuesData.Row(date == null ? null : LocalDate.parse(date),
                amount == null ? null : new BigDecimal(amount), rate == null ? null : new BigDecimal(rate));
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit sale values company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    private static class Probe extends SSSalevaluesPrinter {
        Probe(SaleValuesData values, String from, String to) {
            super(company(), year(), values, LocalDate.parse(from), LocalDate.parse(to));
        }

        SSDefaultTableModel model() { return getModel(); }
    }
}
