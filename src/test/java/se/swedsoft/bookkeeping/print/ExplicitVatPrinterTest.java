package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.print.report.SSVATControl2015Printer;
import se.swedsoft.bookkeeping.print.report.SSVATReport2015Printer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitVatPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void explicitTotalsResolveSelectedAccountsAndSkipInvalidCrossedRows() {
        SSAccount sales = account(3010, "MP1");
        SSAccount input = account(2640, "I");
        SSVoucherRow crossed = row(3010, null, "700");
        crossed.setCrossed(true);
        SSVoucher voucher = voucher(7, "2024-01-01", row(3010, null, "100.99"),
                row(3010, "0.99", null), row(2640, "10.25", null), crossed,
                row(9999, null, "999"), row(3010, null, null), row(3010, null, "0"));
        var credit = SSVoucherMath.getCreditMinusDebetSum(List.of(voucher), List.of(sales, input));
        var debit = SSVoucherMath.getDebetMinusCreditSum(List.of(voucher), List.of(sales, input));
        assertThat(credit).hasSize(2);
        assertThat(credit.get(sales)).isEqualByComparingTo("100.00");
        assertThat(credit.get(input)).isEqualByComparingTo("-10.25");
        assertThat(debit.get(sales)).isEqualByComparingTo("-100.00");
        assertThat(debit.get(input)).isEqualByComparingTo("10.25");
        assertThat(credit.keySet().stream().filter(key -> key.getNumber() == 3010).findFirst().orElseThrow())
                .isSameAs(sales);
        assertThat(SSVoucherMath.getCreditMinusDebetSum(List.of(), List.of(sales))).isEmpty();
    }

    @Test
    void startVoucherRemainsAnIndexAfterDateFilteringWithLegacyFallback() {
        SSNewAccountingYear year = year();
        for (int start : List.of(1, 0, 99)) {
            assertThat(new ControlProbe(company(), year, start).turnover()).isEqualByComparingTo("301.54");
        }
        ControlProbe second = new ControlProbe(company(), year, 2);
        assertThat(second.turnover()).isEqualByComparingTo("200.55");
        assertThatThrownBy(() -> second.getVoucher(null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VatService");
    }

    @Test
    void populatedReportSubreportAndControlRenderWithOnlyExplicitRows() throws Exception {
        SSNewAccountingYear year = year();
        List<SSPrinter> printers = List.of(
                new SSVATReport2015Printer(company(), year, LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 31), 1),
                new ControlProbe(company(), year, 1));
        ReportService reports = new ReportService();
        for (int index = 0; index < printers.size(); index++) {
            SSPrinter printer = printers.get(index);
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("vat-" + index + ".pdf"), false);
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                        .contains("Explicit VAT company").doesNotContain("900");
            }
        }
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit VAT company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2024, 1, 1));
        year.setLocalTo(LocalDate.of(2024, 12, 31));
        SSAccountPlan plan = new SSAccountPlan();
        plan.getAccounts().add(account(3010, "MP1"));
        year.setAccountPlan(plan);
        year.setVouchers(List.of(voucher(500, "2024-02-01", row(3010, null, "900")),
                voucher(7, "2024-01-01", row(3010, null, "100.99")),
                voucher(99, "2024-01-02", row(3010, null, "200.55"))));
        return year;
    }

    private static SSAccount account(int number, String vatCode) {
        SSAccount account = new SSAccount(number);
        account.setVATCode(vatCode);
        return account;
    }

    private static SSVoucher voucher(int number, String date, SSVoucherRow... rows) {
        SSVoucher voucher = new SSVoucher(number);
        voucher.setLocalDate(LocalDate.parse(date));
        voucher.getRows().addAll(List.of(rows));
        return voucher;
    }

    private static SSVoucherRow row(int number, String debit, String credit) {
        SSVoucherRow row = new SSVoucherRow() {
            @Override public SSAccount getAccount() { throw new AssertionError("Legacy account lookup"); }
            @Override public boolean isValid() { throw new AssertionError("Legacy validity lookup"); }
        };
        row.setAccountNr(number);
        row.setDebet(debit == null ? null : new BigDecimal(debit));
        row.setCredit(credit == null ? null : new BigDecimal(credit));
        return row;
    }

    private static class ControlProbe extends SSVATControl2015Printer {
        ControlProbe(SSNewCompany company, SSNewAccountingYear year, int start) {
            super(company, year, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), start);
        }

        BigDecimal turnover() {
            return (BigDecimal) getModel().getValueAt(0, 2);
        }
    }
}
