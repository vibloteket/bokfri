package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.calc.SSResultCalculator;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.print.report.SSBalancePrinter;
import se.swedsoft.bookkeeping.print.report.SSMainBookPrinter;
import se.swedsoft.bookkeeping.print.report.SSResultPrinter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitFinancialPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void headersUseExplicitCompanyAndMaximumVoucherNumber() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit company ÅÄÖ");
        SSNewAccountingYear year = year();
        year.setVouchers(List.of(new SSVoucher(7), new SSVoucher(19), new SSVoucher(2)));
        SSAccount account = new SSAccount(1930);

        for (SSPrinter printer : List.of(
                new SSBalancePrinter(company, year, year.getLocalFrom(), year.getLocalTo()),
                new SSResultPrinter(company, year, year.getLocalFrom(), year.getLocalTo(), false, false,
                        new SSResultCalculator.Context(List.of(year), List.of(), List.of())),
                new SSMainBookPrinter(company, year, account, account,
                        year.getLocalFrom(), year.getLocalTo(), null, null))) {
            assertThat(printer.getParameters()).containsEntry("company", "Explicit company ÅÄÖ")
                    .containsEntry("lastvoucher", 19);
        }
    }

    @Test
    void emptyExplicitYearRendersBothPdfsWithoutLegacyDatabase() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName("Empty company ÅÄÖ");
        SSNewAccountingYear year = year();
        SSAccount account = new SSAccount(1930);
        ReportService reports = new ReportService();
        int index = 0;
        for (SSPrinter printer : List.of(
                new SSBalancePrinter(company, year, year.getLocalFrom(), year.getLocalTo()),
                new SSResultPrinter(company, year, year.getLocalFrom(), year.getLocalTo(), false, false,
                        new SSResultCalculator.Context(List.of(year), List.of(), List.of())),
                new SSMainBookPrinter(company, year, account, account,
                        year.getLocalFrom(), year.getLocalTo(), null, null))) {
            assertThat(printer.getParameters()).containsEntry("lastvoucher", 0);
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("empty-" + index++ + ".pdf"), false);
            assertThat(Files.size(pdf)).isPositive();
            assertThat(Files.readAllBytes(pdf)).startsWith(new byte[] {'%', 'P', 'D', 'F', '-'});
        }
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2024, 1, 1));
        year.setLocalTo(LocalDate.of(2024, 12, 31));
        year.setAccountPlan(new SSAccountPlan());
        year.setVouchers(List.of());
        return year;
    }
}
