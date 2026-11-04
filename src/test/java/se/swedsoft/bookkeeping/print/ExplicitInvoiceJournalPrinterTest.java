package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.journals.SSInvoicejournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSCreditinvoicejournalPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitInvoiceJournalPrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 2, 20);

    @Test
    void explicitVoucherLookupsPreserveRowsTotalsAndCallerOrdering() throws Exception {
        SSInvoice first = invoice(1);
        SSInvoice later = invoice(2);
        List<SSInvoice> payments = new ArrayList<>(List.of(later, first));
        Invoices printer = new Invoices(payments);
        verify(printer.model());
        assertThat(payments.get(0)).isSameAs(later);
        assertThat(first.getVoucher().getRows().get(0).getAccountNr()).isEqualTo(1930);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit journal company")
                .containsEntry("lastvoucher", 9).containsEntry("periodText", DATE.format(
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)));
    }

    @Test
    void creditVoucherLookupsAndSortingAreAlsoExplicit() throws Exception {
        SSCreditInvoice first = credit(1);
        SSCreditInvoice later = credit(2);
        List<SSCreditInvoice> payments = new ArrayList<>(List.of(later, first));
        verify(new Credits(payments).model());
        assertThat(payments.get(0)).isSameAs(later);
    }

    private static void verify(SSDefaultTableModel model) throws Exception {
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("125.155");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("25.03");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("250.310");
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 9);
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
            for (SSPrinter printer : List.of(new Invoices(populated ? List.of(invoice(1)) : List.of()),
                    new Credits(populated ? List.of(credit(1)) : List.of()))) {
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

    @Test
    void companyRoundingAndNullCurrencyRateAreExplicitForInvoicesAndCredits() {
        SSNewCompany rounded = company();
        rounded.setRoundingOff(false);
        class InvoiceProbe extends SSInvoicejournalPrinter {
            InvoiceProbe() { super(rounded, year(), List.of(invoice(1)), 3, DATE, projects(), resultUnits()); }
            SSDefaultTableModel model() { return getModel(); }
        }
        class CreditProbe extends SSCreditinvoicejournalPrinter {
            CreditProbe() { super(rounded, year(), List.of(credit(1)), 3, DATE, projects(), resultUnits()); }
            SSDefaultTableModel model() { return getModel(); }
        }
        assertThat((BigDecimal) new InvoiceProbe().model().getValueAt(0, 6)).isEqualByComparingTo("125");
        assertThat((BigDecimal) new CreditProbe().model().getValueAt(0, 6)).isEqualByComparingTo("125");
        SSInvoice invoice = invoice(1);
        invoice.setCurrencyRate(null);
        assertThat((BigDecimal) new Invoices(List.of(invoice)).model().getValueAt(0, 8))
                .isEqualByComparingTo("125.155");
    }

    @Test
    void discountedRowsMissingAmountsAndNullDatesRetainExistingTotals() {
        SSInvoice invoice = invoice(1);
        invoice.setLocalDate(null);
        invoice.getRows().get(0).setQuantity(2);
        invoice.getRows().get(0).setDiscount(new BigDecimal("10"));
        invoice.getRows().add(new SSSaleRow());
        SSDefaultTableModel model = new Invoices(List.of(invoice)).model();
        assertThat(model.getValueAt(0, 3)).isNull();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("225.285");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("45.06");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("450.570");
    }

    @Test
    void explicitCreditCreationDefersVoucherGenerationAndResetsBookingState() {
        SSInvoice original = invoice(1);
        original.setEntered(true);
        SSCreditInvoice credit = new SSCreditInvoice(original, company());
        assertThat(credit.getCreditingNr()).isEqualTo(1);
        assertThat(credit.isEntered()).isFalse();
        assertThat(credit.getVoucher().getRows()).isEmpty();
        assertThat(credit.getRows()).hasSize(1);
        assertThat(credit.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.125");
        assertThat(original.isEntered()).isTrue();
        assertThat(original.getVoucher().getRows()).hasSize(2);
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

    private static SSInvoice invoice(int number) { return sale(new SSInvoice(), number); }
    private static SSCreditInvoice credit(int number) { return sale(new SSCreditInvoice(), number); }

    private static <T extends SSInvoice> T sale(T invoice, int number) {
        invoice.setNumber(number);
        invoice.setLocalDate(DATE);
        invoice.setCustomerNr("C1");
        invoice.setCustomerName("Explicit customer");
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxFree(false);
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(BigDecimal.ZERO);
        invoice.setTaxRate3(BigDecimal.ZERO);
        invoice.setVoucher(voucher());
        SSSaleRow row = new SSSaleRow();
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal("100.125"));
        row.setTaxCode(se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_1);
        invoice.getRows().add(row);
        return invoice;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit journal company");
        company.setRoundingOff(true);
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

    private static class Invoices extends SSInvoicejournalPrinter {
        Invoices(List<SSInvoice> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Credits extends SSCreditinvoicejournalPrinter {
        Credits(List<SSCreditInvoice> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
