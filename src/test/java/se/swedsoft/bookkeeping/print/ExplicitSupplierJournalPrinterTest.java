package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.journals.SSSupplierInvoicejournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSSuppliercreditinvoicejournalPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitSupplierJournalPrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 2, 20);

    @Test
    void explicitVoucherLookupsPreserveRowsTotalsAndCallerOrdering() throws Exception {
        SSSupplierInvoice first = invoice(1);
        SSSupplierInvoice later = invoice(2);
        List<SSSupplierInvoice> payments = new ArrayList<>(List.of(later, first));
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
        SSSupplierCreditInvoice first = credit(1);
        SSSupplierCreditInvoice later = credit(2);
        List<SSSupplierCreditInvoice> payments = new ArrayList<>(List.of(later, first));
        verify(new Credits(payments).model());
        assertThat(payments.get(0)).isSameAs(later);
    }

    private static void verify(SSDefaultTableModel model) throws Exception {
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("125");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("25.03");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("250");
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
    void nullableStoredTaxRoundingAndCurrencyDoNotUseCustomerSaleRounding() {
        SSSupplierInvoice invoice = invoice(1);
        invoice.setTaxSum(null);
        invoice.setRoundingSum(null);
        invoice.setCurrencyRate(null);
        SSDefaultTableModel model = new Invoices(List.of(invoice)).model();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("100.125");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isZero();
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("100.125");
        SSSupplierCreditInvoice credit = credit(1);
        credit.setRoundingSum(null);
        assertThat((BigDecimal) new Credits(List.of(credit)).model().getValueAt(0, 6))
                .isEqualByComparingTo("125.155");
    }

    @Test
    void quantitiesMissingAmountsAndNullDatesRetainStoredTotalSemantics() {
        SSSupplierInvoice invoice = invoice(1);
        invoice.setLocalDate(null);
        invoice.getRows().get(0).setQuantity(2);
        invoice.getRows().get(0).setUnitFreight(new BigDecimal("999"));
        invoice.getRows().add(new SSSupplierInvoiceRow());
        SSDefaultTableModel model = new Invoices(List.of(invoice)).model();
        assertThat(model.getValueAt(0, 3)).isNull();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("225.125");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("450.250");
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

    private static SSSupplierInvoice invoice(int number) { return sale(new SSSupplierInvoice(), number); }
    private static SSSupplierCreditInvoice credit(int number) { return sale(new SSSupplierCreditInvoice(), number); }

    private static <T extends SSSupplierInvoice> T sale(T invoice, int number) {
        invoice.setNumber(number);
        invoice.setLocalDate(DATE);
        invoice.setSupplierNr("S1");
        invoice.setSupplierName("Explicit supplier");
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxSum(new BigDecimal("25.03"));
        invoice.setRoundingSum(new BigDecimal("-0.155"));
        invoice.setVoucher(voucher());
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal("100.125"));
        invoice.getRows().add(row);
        return invoice;
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

    private static class Invoices extends SSSupplierInvoicejournalPrinter {
        Invoices(List<SSSupplierInvoice> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Credits extends SSSuppliercreditinvoicejournalPrinter {
        Credits(List<SSSupplierCreditInvoice> payments) { super(company(), year(), payments, 3, DATE, projects(), resultUnits()); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
