package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.print.report.SSVoucherPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitVoucherPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void explicitOuterAndNestedHeadersAndRowsNeverUseImplicitReferences() throws Exception {
        SSAccount account = new SSAccount(1930);
        account.setDescription("Explicit voucher account");
        List<SSAccount> accounts = new ArrayList<>(List.of(account));
        SSAccountPlan plan = new SSAccountPlan();
        plan.setAccounts(accounts);
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit voucher company");
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setAccountPlan(plan);
        SSVoucher voucher = voucher();
        SSVoucher later = new SSVoucher();
        later.setNumber(19);
        year.setVouchers(List.of(later, voucher));
        SSNewProject project = new SSNewProject();
        project.setNumber("P-X");
        SSNewResultUnit unit = new SSNewResultUnit();
        unit.setNumber("U-X");
        List<SSNewProject> projects = new ArrayList<>(List.of(project));
        List<SSNewResultUnit> units = new ArrayList<>(List.of(unit));
        var printer = new SSVoucherPrinter(voucher, "Explicit voucher title", company, year, projects, units);
        accounts.clear();
        projects.clear();
        units.clear();
        String text = pdfText(printer, "explicit");
        assertThat(text).contains("Explicit voucher company", "Explicit voucher title",
                "Explicit voucher account", "P-X", "U-X");
        assertThat(printer.getParameters()).containsEntry("company", "Explicit voucher company")
                .containsEntry("lastvoucher", 19);
        Map<?, ?> nested = (Map<?, ?>) printer.getParameters().get("Parameters");
        assertThat(nested.get("company")).isEqualTo("Explicit voucher company");
        assertThat(nested.get("lastvoucher")).isEqualTo(19);
        var outer = printer.getDataSource();
        outer.reset();
        assertThat(outer.next()).isTrue();
        assertThat((BigDecimal) field(outer, "voucher.debet")).isEqualByComparingTo("25.50");
        assertThat((BigDecimal) field(outer, "voucher.credet")).isEqualByComparingTo("25.50");
        JRDataSource rows = (JRDataSource) field(outer, "voucher.rows");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "voucherrow.account")).isEqualTo(1930);
        assertThat(field(rows, "voucherrow.description")).isEqualTo("Explicit voucher account");
        assertThat(field(rows, "voucherrow.project")).isEqualTo("P-X");
        assertThat(field(rows, "voucherrow.resultunit")).isEqualTo("U-X");
        assertThat(field(rows, "voucherrow.isadded")).isEqualTo(true);
        assertThat(field(rows, "voucherrow.ismarked")).isEqualTo(false);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "voucherrow.account")).isNull();
        assertThat(field(rows, "voucherrow.description")).isNull();
        assertThat(field(rows, "voucherrow.project")).isNull();
        assertThat(field(rows, "voucherrow.resultunit")).isNull();
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "voucherrow.iscrossed")).isEqualTo(true);
        assertThat(voucher.getRows()).hasSize(4);
    }

    @Test
    void emptyVoucherAndNullDateRenderWithExplicitEmptyContext() throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName("Empty voucher company");
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        SSVoucher voucher = new SSVoucher();
        voucher.setLocalDate(null);
        var printer = new SSVoucherPrinter(voucher, "Empty voucher title", company, year, List.of(), List.of());
        assertThat(printer.getParameters()).containsEntry("lastvoucher", 0);
        assertThat(pdfText(printer, "empty")).contains("Empty voucher company", "Empty voucher title");
    }

    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher();
        voucher.setNumber(7);
        voucher.setDescription("Explicit stored voucher");
        SSVoucherRow debit = row(1930, "P-X", "U-X");
        debit.setDebet(new BigDecimal("25.50"));
        debit.setAdded(true);
        SSVoucherRow credit = row(9999, "missing-project", "missing-unit");
        credit.setCredit(new BigDecimal("25.50"));
        SSVoucherRow crossed = row(1930, null, null);
        crossed.setDebet(new BigDecimal("999.99"));
        crossed.setCrossed(true);
        voucher.addVoucherRow(debit);
        voucher.addVoucherRow(credit);
        voucher.addVoucherRow(crossed);
        SSVoucherRow validCredit = row(1930, null, null);
        validCredit.setCredit(new BigDecimal("25.50"));
        voucher.addVoucherRow(validCredit);
        return voucher;
    }

    private static SSVoucherRow row(int account, String project, String unit) {
        SSVoucherRow row = new SSVoucherRow() {
            @Override public SSAccount getAccount() { throw new AssertionError("Implicit account lookup"); }
            @Override public SSNewProject getProject() { throw new AssertionError("Implicit project lookup"); }
            @Override public SSNewResultUnit getResultUnit() { throw new AssertionError("Implicit unit lookup"); }
        };
        row.setAccountNr(account);
        row.setProjectNr(project);
        row.setResultUnitNr(unit);
        return row;
    }

    private static Object field(JRDataSource rows, String name) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName(name);
        return rows.getFieldValue(field);
    }

    private String pdfText(SSPrinter printer, String name) throws Exception {
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(name + ".pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }
}
