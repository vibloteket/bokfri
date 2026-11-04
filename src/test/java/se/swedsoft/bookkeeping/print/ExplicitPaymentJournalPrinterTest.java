package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitPaymentJournalPrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 2, 20);

    @Test
    void explicitVoucherLookupsPreserveRowsTotalsAndCallerOrdering() throws Exception {
        SSInpayment first = inpayment(1);
        SSInpayment later = inpayment(2);
        List<SSInpayment> payments = new ArrayList<>(List.of(later, first));
        Inpayments printer = new Inpayments(payments);
        verify(printer.model());
        assertThat(payments.get(0)).isSameAs(later);
        assertThat(first.getVoucher().getRows().get(0).getAccountNr()).isEqualTo(1930);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit journal company")
                .containsEntry("lastvoucher", 9).containsEntry("periodText", DATE.format(
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)));
    }

    @Test
    void outpaymentVoucherLookupsAndSortingAreAlsoExplicit() throws Exception {
        SSOutpayment first = outpayment(1);
        SSOutpayment later = outpayment(2);
        List<SSOutpayment> payments = new ArrayList<>(List.of(later, first));
        verify(new Outpayments(payments).model());
        assertThat(payments.get(0)).isSameAs(later);
    }

    private static void verify(SSDefaultTableModel model) throws Exception {
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 3)).isEqualByComparingTo("25");
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 4);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "row.account", Integer.class)).isEqualTo(1930);
        assertThat(field(rows, "row.description", String.class)).isEqualTo("Explicit bank");
        assertThat(field(rows, "row.project", String.class)).isEqualTo("P1");
        assertThat(field(rows, "row.resultunit", String.class)).isEqualTo("R1");
        assertThat((BigDecimal) field(rows, "row.debet", BigDecimal.class)).isEqualByComparingTo("25");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "row.account", Integer.class)).isNull();
        assertThat(field(rows, "row.description", String.class)).isNull();
        assertThat(field(rows, "row.project", String.class)).isNull();
        assertThat(field(rows, "row.resultunit", String.class)).isNull();
    }

    @Test
    void emptyAndPopulatedJournalsRenderWithExplicitOuterAndNestedHeaders() throws Exception {
        for (boolean populated : List.of(false, true)) {
            for (SSPrinter printer : List.of(new Inpayments(populated ? List.of(inpayment(1)) : List.of()),
                    new Outpayments(populated ? List.of(outpayment(1)) : List.of()))) {
                ReportService reports = new ReportService();
                Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit journal company");
                    if (populated) assertThat(text).contains("Explicit bank", "P1", "R1");
                }
            }
        }
    }

    private static Object field(JRDataSource rows, String name, Class<?> type) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName(name);
        field.setValueClass(type);
        return rows.getFieldValue(field);
    }

    private static SSVoucher voucher() {
        SSVoucher voucher = new SSVoucher();
        for (boolean known : List.of(true, false)) {
            SSVoucherRow row = new SSVoucherRow() {
                @Override public SSAccount getAccount() { throw new AssertionError("Implicit account lookup"); }
                @Override public SSNewProject getProject() { throw new AssertionError("Implicit project lookup"); }
                @Override public SSNewResultUnit getResultUnit() { throw new AssertionError("Implicit result lookup"); }
            };
            row.setAccountNr(known ? 1930 : 9999);
            row.setProjectNr(known ? "P1" : "UNKNOWN");
            row.setResultUnitNr(known ? "R1" : "UNKNOWN");
            row.setDebet(new BigDecimal("25"));
            voucher.getRows().add(row);
        }
        return voucher;
    }

    private static SSInpayment inpayment(int number) {
        SSInpayment payment = new SSInpayment();
        payment.setNumber(number);
        payment.setLocalDate(DATE);
        payment.setVoucher(voucher());
        SSInpaymentRow row = new SSInpaymentRow();
        row.setValue(new BigDecimal("12.5"));
        row.setCurrencyRate(new BigDecimal("2"));
        payment.getRows().add(row);
        return payment;
    }

    private static SSOutpayment outpayment(int number) {
        SSOutpayment payment = new SSOutpayment();
        payment.setNumber(number);
        payment.setLocalDate(DATE);
        payment.setVoucher(voucher());
        SSOutpaymentRow row = new SSOutpaymentRow();
        row.setValue(new BigDecimal("12.5"));
        row.setCurrencyRate(new BigDecimal("2"));
        payment.getRows().add(row);
        return payment;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit journal company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        SSAccount account = new SSAccount(1930);
        account.setDescription("Explicit bank");
        year.getAccountPlan().getAccounts().add(account);
        SSVoucher a = new SSVoucher();
        a.setNumber(9);
        SSVoucher b = new SSVoucher();
        b.setNumber(2);
        year.setVouchers(List.of(a, b));
        return year;
    }

    private static List<SSNewProject> projects() {
        SSNewProject project = new SSNewProject();
        project.setNumber("P1");
        return List.of(project);
    }

    private static List<SSNewResultUnit> resultUnits() {
        SSNewResultUnit unit = new SSNewResultUnit();
        unit.setNumber("R1");
        return List.of(unit);
    }

    private static class Inpayments extends SSInpaymentjournalPrinter {
        Inpayments(List<SSInpayment> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Outpayments extends SSOutpaymentjournalPrinter {
        Outpayments(List<SSOutpayment> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
