package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.print.report.SSAccountPlanPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitAccountPlanPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void explicitHeaderUsesMaximumVoucherAndOnlyActiveAccountFields() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit account-plan company");
        SSNewAccountingYear year = new SSNewAccountingYear();
        SSVoucher high = new SSVoucher();
        high.setNumber(19);
        SSVoucher low = new SSVoucher();
        low.setNumber(2);
        year.setVouchers(List.of(high, low));
        SSAccountPlan plan = new SSAccountPlan();
        plan.setName("Explicit account plan");
        SSAccount active = new SSAccount(1930);
        active.setDescription("Active account description");
        active.setVATCode("VAT-CODE");
        active.setSRUCode("SRU-CODE");
        active.setReportCode("R-X");
        SSAccount inactive = new SSAccount(1940);
        inactive.setDescription("Inactive account description");
        inactive.setActive(false);
        plan.setAccounts(new ArrayList<>(List.of(active, inactive)));
        var printer = new SSAccountPlanPrinter(plan, company, year);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit account-plan company")
                .containsEntry("lastvoucher", 19);
        String text = pdfText(printer, "active");
        assertThat(text).contains("Explicit account-plan company", "Explicit account plan",
                "1930", "Active account description", "VAT-CODE", "SRU-CODE", "R-X")
                .doesNotContain("Inactive account description", "1940");
        assertThat(plan.getAccounts()).containsExactly(active, inactive);
        assertThat(year.getVouchers()).containsExactly(high, low);
    }

    @Test
    void emptyPlanAndNullableCodesRenderWithoutLegacySelection() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName("Empty plan company");
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        SSAccountPlan plan = new SSAccountPlan();
        plan.setName("Empty explicit plan");
        var empty = new SSAccountPlanPrinter(plan, company, year);
        assertThat(empty.getParameters()).containsEntry("lastvoucher", 0);
        assertThat(pdfText(empty, "empty")).contains("Empty plan company", "Empty explicit plan");
        SSAccount nullable = new SSAccount(1930);
        nullable.setDescription(null);
        nullable.setVATCode(null);
        nullable.setSRUCode(null);
        nullable.setReportCode(null);
        SSAccountPlan nullablePlan = new SSAccountPlan();
        nullablePlan.setName("Nullable explicit plan");
        nullablePlan.setAccounts(new ArrayList<>(List.of(nullable)));
        assertThat(pdfText(new SSAccountPlanPrinter(nullablePlan, company, year), "nullable"))
                .contains("Nullable explicit plan", "1930");
    }

    private String pdfText(SSPrinter printer, String name) throws Exception {
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(name + ".pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }
}
