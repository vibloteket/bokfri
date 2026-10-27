package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.print.ReportService;
import se.swedsoft.bookkeeping.print.report.SSResultPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BokfriRuntimeResultReportTest {
    @TempDir Path temporaryDirectory;

    @Test
    void normalizedComparisonPdfsMatchLegacyAndExcludeOtherCompanies() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        int companyId;
        int yearId;
        List<String> expected = new ArrayList<>();
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = runtime.getCompanies().get(0);
            companyId = company.getId();
            runtime.selectCompany(companyId);
            SSNewAccountingYear previous = addYear(runtime, 2023);
            addVoucher(runtime, previous, "40.00");
            SSNewAccountingYear year = addYear(runtime, 2024);
            yearId = year.getId();
            addVoucher(runtime, year, "60.00");
            year.getBudget().setYear(year);
            year.getBudget().setSumForAccount(year.getAccounts().get(0), new BigDecimal("1200.00"));
            runtime.database().updateAccountingYear(year);
            for (int mode = 0; mode < 3; mode++) {
                expected.add(render(new SSResultPrinter(year, LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 2, 29), mode == 1, mode == 2), "legacy-" + mode));
            }
            assertThat(expected).allSatisfy(text -> assertThat(text).contains("Explicit revenue"));
            SSNewCompany other = new SSNewCompany();
            other.setName("Other company must not leak");
            runtime.database().addCompany(other);
            runtime.selectCompany(other.getId());
            addVoucher(runtime, addYear(runtime, 2023), "9999.00");
        }
        SSDB.getInstance().setCurrentCompany(null);
        SSDB.getInstance().setCurrentYear(null);
        new NormalizedMigrationService(java.time.Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = runtime.selectCompany(companyId);
            SSNewAccountingYear year = runtime.selectYear(company, yearId);
            var context = runtime.resultReportContext(year);
            assertThat(context.years()).extracting(SSNewAccountingYear::getId).contains(yearId);
            SSAccount revenue = year.getAccounts().stream().filter(account -> account.getNumber() == 3010)
                    .findFirst().orElseThrow();
            assertThat(year.getBudget().getSumForAccount(revenue,
                    LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)))
                    .isEqualByComparingTo("100.00");
            for (int mode = 0; mode < 3; mode++) {
                assertThat(render(new SSResultPrinter(company, year, LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 2, 29), mode == 1, mode == 2, context), "normalized-" + mode))
                        .isEqualTo(expected.get(mode)).doesNotContain("9999", "Other company must not leak");
            }
            assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
            assertThat(SSDB.getInstance().getCurrentYear()).isNull();
        }
    }

    private SSNewAccountingYear addYear(BokfriRuntime runtime, int number) throws Exception {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(number, 1, 1));
        year.setLocalTo(LocalDate.of(number, 12, 31));
        SSAccountPlan plan = new SSAccountPlan();
        SSAccount revenue = new SSAccount(3010);
        revenue.setDescription("Explicit revenue");
        plan.getAccounts().add(revenue);
        plan.getAccounts().add(new SSAccount(1930));
        year.setAccountPlan(plan);
        runtime.database().addAccountingYear(year);
        runtime.database().setCurrentYear(year);
        return year;
    }

    private void addVoucher(BokfriRuntime runtime, SSNewAccountingYear year, String amount) throws Exception {
        SSVoucher voucher = new SSVoucher(1);
        voucher.setLocalDate(LocalDate.of(year.getLocalFrom().getYear(), 2, 28));
        SSVoucherRow revenue = new SSVoucherRow();
        revenue.setAccount(year.getAccounts().get(0));
        revenue.setCredit(new BigDecimal(amount));
        SSVoucherRow bank = new SSVoucherRow();
        bank.setAccount(year.getAccounts().get(1));
        bank.setDebet(new BigDecimal(amount));
        voucher.getRows().addAll(List.of(revenue, bank));
        runtime.addVoucher(voucher);
    }

    private String render(SSResultPrinter printer, String name) throws Exception {
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(name + ".pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }
}
