package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.calc.math.SSCustomerBalanceMath;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSAccountsRecievablePrinter;
import se.swedsoft.bookkeeping.print.report.SSCustomerclaimPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitCustomerBalancePrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 2, 20);

    @Test
    void cutoffIncludesBoundaryPaymentsCreditsAndExcludesCashFutureAndUndatedInvoices() {
        SSInvoice invoice = invoice(1, "100.125");
        SSCreditInvoice credit = credit(invoice, "20.005", DATE);
        List<SSInpayment> payments = List.of(payment(1, "25.005", DATE),
                payment(1, "900", DATE.plusDays(1)), payment(1, "900", null),
                payment(1, null, DATE));
        List<SSCreditInvoice> credits = List.of(credit, credit(invoice, "900", DATE.plusDays(1)),
                credit(invoice, "900", null));
        SSInvoice cash = invoice(2, "999");
        cash.setType(SSInvoiceType.CASH);
        SSInvoice future = invoice(3, "999");
        future.setLocalDate(DATE.plusDays(1));
        SSInvoice undated = invoice(4, "999");
        undated.setLocalDate(null);
        SSInvoice settled = invoice(5, "10");
        List<SSInpayment> allPayments = new ArrayList<>(payments);
        allPayments.add(payment(5, "10", DATE));
        Claims claims = new Claims(company(true), List.of(cash, invoice, future, undated, settled),
                credits, allPayments);
        SSDefaultTableModel model = claims.model();
        assertThat(model.getRowCount()).isEqualTo(2); // zero balances remain visible
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("55.12");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("110.24");
        assertThat((BigDecimal) model.getValueAt(1, 6)).isZero();
        assertThat(invoice.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.125");
    }

    @Test
    void receivablesKeepUnroundedBalancesAndNestedRowsUseExplicitHeaders() throws Exception {
        SSInvoice invoice = invoice(1, "100.125");
        SSCustomer customer = customer("C1");
        List<SSCustomer> customers = new ArrayList<>(List.of(customer("Z"), customer));
        Receivables printer = new Receivables(company(true), customers, List.of(invoice),
                List.of(credit(invoice, "20.005", DATE)), List.of(payment(1, "25.005", DATE)));
        SSDefaultTableModel model = printer.model();
        assertThat(model.getValueAt(0, 0)).isEqualTo("C1");
        assertThat((BigDecimal) model.getValueAt(0, 2)).isEqualByComparingTo("110.230");
        assertThat((BigDecimal) model.getValueAt(1, 2)).isZero();
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 3);
        JRDesignField field = new JRDesignField();
        field.setName("invoice.localsaldo");
        field.setValueClass(BigDecimal.class);
        assertThat(rows.next()).isTrue();
        assertThat((BigDecimal) rows.getFieldValue(field)).isEqualByComparingTo("110.230");
        assertThat(customers.get(0).getNumber()).isEqualTo("Z");
    }

    @Test
    void companyRoundingAndTaxAreExplicitAndCreditsStayInInvoiceCurrency() {
        SSInvoice invoice = invoice(1, "100.125");
        invoice.setTaxFree(false);
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(BigDecimal.ZERO);
        invoice.setTaxRate3(BigDecimal.ZERO);
        invoice.getRows().get(0).setTaxCode(se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_1);
        SSCreditInvoice credit = credit(invoice, "10.25", DATE);
        credit.setCurrencyRate(new BigDecimal("99")); // legacy subtraction does not convert credit currency
        SSCustomerBalanceMath precise = new SSCustomerBalanceMath(true, List.of(), List.of(credit), DATE);
        assertThat(precise.total(invoice)).isEqualByComparingTo("125.155");
        assertThat(precise.saldo(invoice)).isEqualByComparingTo("114.905");
        SSCustomerBalanceMath rounded = new SSCustomerBalanceMath(false, List.of(), List.of(credit), DATE);
        assertThat(rounded.total(invoice)).isEqualByComparingTo("125");
        assertThat(rounded.saldo(invoice)).isEqualByComparingTo("115");
        precise.creditSums().clear();
        assertThat(precise.saldo(invoice)).isEqualByComparingTo("114.905");
    }

    @Test
    void overpaymentsAndMultipleLinkedCreditsRetainNegativeBalancesAndNullRateFallback() {
        SSInvoice invoice = invoice(1, "10");
        invoice.setCurrencyRate(null);
        SSCreditInvoice unlinked = credit(invoice, "999", DATE);
        unlinked.setCrediting(null);
        List<SSCreditInvoice> credits = List.of(credit(invoice, "2", DATE),
                credit(invoice, "3", DATE), unlinked);
        List<SSInpayment> payments = List.of(payment(1, "4", DATE), payment(1, "7", DATE));
        SSDefaultTableModel model = new Claims(company(true), List.of(invoice), credits, payments).model();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("-6.00");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("-6.00");
    }

    @Test
    void emptyAndPopulatedPdfsRenderWithoutSingletonCompanyOrCustomerCaches() throws Exception {
        for (boolean populated : List.of(false, true)) {
            List<SSInvoice> invoices = populated ? List.of(invoice(1, "123.45")) : List.of();
            List<SSPrinter> printers = List.of(
                    new Claims(company(true), invoices, List.of(), List.of()),
                    new Receivables(company(true), populated ? List.of(customer("C1"), customer("EMPTY"))
                            : List.of(), invoices, List.of(), List.of()));
            for (SSPrinter printer : printers) {
                ReportService service = new ReportService();
                Path pdf = service.exportPdf(service.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit customer company");
                    if (populated) assertThat(text).containsPattern("123[.,]45");
                }
            }
        }
    }

    private static SSInvoice invoice(int number, String amount) {
        SSInvoice invoice = new SSInvoice();
        invoice.setNumber(number);
        invoice.setLocalDate(DATE);
        invoice.setCustomerNr("C1");
        invoice.setCustomerName("Customer snapshot");
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxFree(true);
        SSSaleRow row = new SSSaleRow() {
            @Override public se.swedsoft.bookkeeping.data.SSProduct getProduct() {
                throw new AssertionError("Implicit product lookup");
            }
        };
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal(amount));
        invoice.getRows().add(row);
        return invoice;
    }

    private static SSCreditInvoice credit(SSInvoice invoice, String amount, LocalDate date) {
        SSCreditInvoice credit = new SSCreditInvoice();
        credit.setCrediting(invoice);
        credit.setLocalDate(date);
        credit.setTaxFree(true);
        credit.getRows().addAll(invoice(999, amount).getRows());
        return credit;
    }

    private static SSInpayment payment(int invoice, String amount, LocalDate date) {
        SSInpayment payment = new SSInpayment();
        payment.setLocalDate(date);
        SSInpaymentRow row = new SSInpaymentRow();
        row.setInvoiceNr(invoice);
        row.setValue(amount == null ? null : new BigDecimal(amount));
        payment.getRows().add(row);
        return payment;
    }

    private static SSCustomer customer(String number) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(number);
        customer.setName(number);
        return customer;
    }

    private static SSNewCompany company(boolean rounding) {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit customer company");
        company.setRoundingOff(rounding);
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    private static class Claims extends SSCustomerclaimPrinter {
        Claims(SSNewCompany company, List<SSInvoice> invoices, List<SSCreditInvoice> credits,
                List<SSInpayment> payments) {
            super(company, year(), invoices, credits, payments, DATE);
        }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Receivables extends SSAccountsRecievablePrinter {
        Receivables(SSNewCompany company, List<SSCustomer> customers, List<SSInvoice> invoices,
                List<SSCreditInvoice> credits, List<SSInpayment> payments) {
            super(company, year(), customers, invoices, credits, payments, DATE);
        }
        SSDefaultTableModel model() { return getModel(); }
    }
}
