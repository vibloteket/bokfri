package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.calc.math.SSSupplierBalanceMath;
import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSAccountsPayablePrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierdebtPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitSupplierBalancePrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 2, 20);

    @Test
    void cutoffIncludesBoundaryCreditsPaymentsAndRetainsSettledInvoices() {
        SSSupplierInvoice invoice = invoice(1, "100.125");
        SSSupplierInvoice future = invoice(2, "999");
        future.setLocalDate(DATE.plusDays(1));
        SSSupplierInvoice undated = invoice(3, "999");
        undated.setLocalDate(null);
        SSSupplierInvoice settled = invoice(4, "10");
        List<SSSupplierCreditInvoice> credits = List.of(credit(invoice, "20.005", DATE),
                credit(invoice, "900", DATE.plusDays(1)), credit(invoice, "900", null));
        List<SSOutpayment> payments = List.of(payment(1, "25.005", DATE), payment(1, "900", DATE.plusDays(1)),
                payment(1, "900", null), payment(1, null, DATE), payment(4, "10", DATE));
        SSDefaultTableModel model = new Debts(List.of(undated, settled, future, invoice), credits, payments).model();
        assertThat(model.getRowCount()).isEqualTo(2);
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("55.12");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("110.24");
        assertThat((BigDecimal) model.getValueAt(1, 6)).isZero();
        assertThat(invoice.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.125");
    }

    @Test
    void payablesRetainUnroundedRemaindersAndNestedRowsWithoutRegisterMutation() throws Exception {
        SSSupplierInvoice invoice = invoice(1, "100.125");
        List<SSSupplier> suppliers = new ArrayList<>(List.of(supplier("Z"), supplier("S1")));
        SSDefaultTableModel model = new Payables(suppliers, List.of(invoice),
                List.of(credit(invoice, "20.005", DATE)), List.of(payment(1, "25.005", DATE))).model();
        assertThat(model.getValueAt(0, 0)).isEqualTo("S1");
        assertThat((BigDecimal) model.getValueAt(0, 2)).isEqualByComparingTo("110.230");
        assertThat((BigDecimal) model.getValueAt(1, 2)).isZero();
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 3);
        JRDesignField field = new JRDesignField();
        field.setName("supplierinvoice.localsaldo");
        field.setValueClass(BigDecimal.class);
        assertThat(rows.next()).isTrue();
        assertThat((BigDecimal) rows.getFieldValue(field)).isEqualByComparingTo("110.230");
        assertThat(suppliers.get(0).getNumber()).isEqualTo("Z");
    }

    @Test
    void storedTaxAndRoundingAmountsAreUsedWithoutCompanyRoundingOrCreditRateConversion() {
        SSSupplierInvoice invoice = invoice(1, "100.125");
        invoice.setTaxSum(new BigDecimal("25.03"));
        invoice.setRoundingSum(new BigDecimal("-0.155"));
        SSSupplierCreditInvoice credit = credit(invoice, "10.25", DATE);
        credit.setTaxSum(new BigDecimal("2.50"));
        credit.setRoundingSum(new BigDecimal("0.25"));
        credit.setCurrencyRate(new BigDecimal("99"));
        SSSupplierBalanceMath balances = new SSSupplierBalanceMath(List.of(), List.of(credit), DATE);
        assertThat(SSSupplierInvoiceMath.getTotalSum(invoice)).isEqualByComparingTo("125");
        assertThat(balances.saldo(invoice)).isEqualByComparingTo("112");
        balances.creditSums().clear();
        assertThat(balances.saldo(invoice)).isEqualByComparingTo("112");
        assertThat(SSSupplierInvoiceMath.getTotalSum(invoice(2, "4.125")))
                .isEqualByComparingTo("4.125"); // nullable tax and rounding stay absent
    }

    @Test
    void multipleCreditsOverpaymentsAndNullRateRetainNegativeBalances() {
        SSSupplierInvoice invoice = invoice(1, "10");
        invoice.setCurrencyRate(null);
        SSSupplierCreditInvoice unlinked = credit(invoice, "999", DATE);
        unlinked.setCrediting(null);
        SSDefaultTableModel model = new Debts(List.of(invoice), List.of(credit(invoice, "2", DATE),
                credit(invoice, "3", DATE), unlinked),
                List.of(payment(1, "4", DATE), payment(1, "7", DATE))).model();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("-6.00");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("-6.00");
    }

    @Test
    void emptyAndPopulatedPdfsUseExplicitOuterAndNestedHeaders() throws Exception {
        for (boolean populated : List.of(false, true)) {
            List<SSSupplierInvoice> invoices = populated ? List.of(invoice(1, "123.45")) : List.of();
            for (SSPrinter printer : List.of(new Debts(invoices, List.of(), List.of()),
                    new Payables(populated ? List.of(supplier("S1"), supplier("EMPTY")) : List.of(),
                            invoices, List.of(), List.of()))) {
                ReportService service = new ReportService();
                Path pdf = service.exportPdf(service.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit supplier company");
                    if (populated) assertThat(text).containsPattern("123[.,]45");
                }
            }
        }
    }

    private static SSSupplierInvoice invoice(int number, String amount) {
        SSSupplierInvoice invoice = new SSSupplierInvoice();
        invoice.setNumber(number);
        invoice.setLocalDate(DATE);
        invoice.setSupplierNr("S1");
        invoice.setSupplierName("Supplier snapshot");
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxSum(null);
        invoice.setRoundingSum(null);
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal(amount));
        invoice.getRows().add(row);
        return invoice;
    }

    private static SSSupplierCreditInvoice credit(SSSupplierInvoice invoice, String amount, LocalDate date) {
        SSSupplierCreditInvoice credit = new SSSupplierCreditInvoice();
        credit.setCrediting(invoice);
        credit.setLocalDate(date);
        credit.setTaxSum(null);
        credit.setRoundingSum(null);
        credit.getRows().addAll(invoice(999, amount).getRows());
        return credit;
    }

    private static SSOutpayment payment(int invoice, String amount, LocalDate date) {
        SSOutpayment payment = new SSOutpayment();
        payment.setLocalDate(date);
        SSOutpaymentRow row = new SSOutpaymentRow();
        row.setInvoiceNr(invoice);
        row.setValue(amount == null ? null : new BigDecimal(amount));
        payment.getRows().add(row);
        return payment;
    }

    private static SSSupplier supplier(String number) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(number);
        supplier.setName(number);
        return supplier;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit supplier company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    private static class Debts extends SSSupplierdebtPrinter {
        Debts(List<SSSupplierInvoice> invoices, List<SSSupplierCreditInvoice> credits, List<SSOutpayment> payments) {
            super(company(), year(), invoices, credits, payments, DATE);
        }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Payables extends SSAccountsPayablePrinter {
        Payables(List<SSSupplier> suppliers, List<SSSupplierInvoice> invoices,
                List<SSSupplierCreditInvoice> credits, List<SSOutpayment> payments) {
            super(company(), year(), suppliers, invoices, credits, payments, DATE);
        }
        SSDefaultTableModel model() { return getModel(); }
    }
}
