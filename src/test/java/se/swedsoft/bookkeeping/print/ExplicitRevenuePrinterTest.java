package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.print.report.SSCustomerRevenuePrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierRevenuePrinter;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitRevenuePrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate FROM = LocalDate.of(2024, 2, 10);
    private static final LocalDate TO = LocalDate.of(2024, 2, 20);

    @Test
    void customerTotalsPreserveDiscountCurrencyCreditsAndInclusiveBoundaries() throws Exception {
        SSInvoice invoice = sale(new SSInvoice(), "A", "2024-02-10", "100.25", "2");
        invoice.getRows().get(0).setDiscount(new BigDecimal("10"));
        invoice.getRows().add(new SSSaleRow()); // Missing row price/quantity is ignored.
        CustomerProbe printer = new CustomerProbe(List.of(customer("A"), customer("C"), customer("EMPTY")),
                List.of(invoice, sale(new SSInvoice(), "A", "2024-02-20", "20.75", null),
                        sale(new SSInvoice(), "A", "2024-02-09", "900", "1"),
                        sale(new SSInvoice(), "A", "2024-02-21", "900", "1"),
                        sale(new SSInvoice(), null, "2024-02-10", "900", "1"),
                        sale(new SSInvoice(), "A", null, "900", "1")),
                List.of(sale(new SSCreditInvoice(), "A", "2024-02-20", "10.25", "2"),
                        sale(new SSCreditInvoice(), "C", "2024-02-10", "5.50", "1")));
        assertThat(printer.total(0)).isEqualByComparingTo("180.70");
        assertThat(printer.total(1)).isEqualByComparingTo("-5.50");
        assertThat(printer.total(2)).isEqualByComparingTo("0");
        assertThat(invoice.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.25");
        assertThat(invoice.getCurrencyRate()).isEqualByComparingTo("2");
    }

    @Test
    void supplierTotalsPreserveCurrencyCreditsAndEmptyRegisters() throws Exception {
        SSSupplierInvoice invoice = purchase(new SSSupplierInvoice(), "A", "2024-02-10", "100.25", "2");
        invoice.getRows().add(new SSSupplierInvoiceRow());
        SupplierProbe printer = new SupplierProbe(List.of(supplier("A"), supplier("C"), supplier("EMPTY")),
                List.of(invoice, purchase(new SSSupplierInvoice(), "A", "2024-02-20", "20.75", null),
                        purchase(new SSSupplierInvoice(), "A", "2024-02-09", "900", "1"),
                        purchase(new SSSupplierInvoice(), "A", "2024-02-21", "900", "1"),
                        purchase(new SSSupplierInvoice(), null, "2024-02-10", "900", "1"),
                        purchase(new SSSupplierInvoice(), "A", null, "900", "1")),
                List.of(purchase(new SSSupplierCreditInvoice(), "A", "2024-02-20", "10.25", "2"),
                        purchase(new SSSupplierCreditInvoice(), "C", "2024-02-10", "5.50", "1")));
        assertThat(printer.total(0)).isEqualByComparingTo("200.75");
        assertThat(printer.total(1)).isEqualByComparingTo("-5.50");
        assertThat(printer.total(2)).isEqualByComparingTo("0");
        assertThat(invoice.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.25");
    }

    @Test
    void emptyAndPopulatedPdfsRenderWithExplicitOuterAndNestedHeaders() throws Exception {
        List<SSPrinter> printers = List.of(
                new CustomerProbe(List.of(), List.of(), List.of()),
                new SupplierProbe(List.of(), List.of(), List.of()),
                new CustomerProbe(List.of(customer("No activity")), List.of(), List.of()),
                new SupplierProbe(List.of(supplier("No activity")), List.of(), List.of()),
                new CustomerProbe(List.of(customer("Active customer")),
                        List.of(sale(new SSInvoice(), "Active customer", "2024-02-10", "123.45", "1")), List.of()),
                new SupplierProbe(List.of(supplier("Active supplier")),
                        List.of(purchase(new SSSupplierInvoice(), "Active supplier", "2024-02-20", "123.45", "1")),
                        List.of()));
        ReportService reports = new ReportService();
        for (int index = 0; index < printers.size(); index++) {
            SSPrinter printer = printers.get(index);
            assertThat(printer.getParameters()).containsEntry("company", "Explicit revenue company")
                    .containsEntry("lastvoucher", 0);
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("revenue-" + index + ".pdf"), false);
            assertThat(Files.size(pdf)).isPositive();
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                        .contains("Explicit revenue company");
            }
        }
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit revenue company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    static SSCustomer customer(String number) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(number);
        customer.setName(number);
        return customer;
    }

    static SSSupplier supplier(String number) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(number);
        supplier.setName(number);
        return supplier;
    }

    static <T extends SSInvoice> T sale(T invoice, String customer, String date, String price, String rate) {
        invoice.setCustomerNr(customer);
        invoice.setLocalDate(date == null ? null : LocalDate.parse(date));
        invoice.setCurrencyRate(rate == null ? null : new BigDecimal(rate));
        SSSaleRow row = new SSSaleRow();
        row.setUnitprice(new BigDecimal(price));
        row.setQuantity(1);
        invoice.getRows().add(row);
        return invoice;
    }

    static <T extends SSSupplierInvoice> T purchase(T invoice, String supplier, String date, String price, String rate) {
        invoice.setSupplierNr(supplier);
        invoice.setLocalDate(date == null ? null : LocalDate.parse(date));
        invoice.setCurrencyRate(rate == null ? null : new BigDecimal(rate));
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setUnitprice(new BigDecimal(price));
        row.setQuantity(1);
        invoice.getRows().add(row);
        return invoice;
    }

    private static BigDecimal sum(JRDataSource data) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName("month.value");
        field.setValueClass(BigDecimal.class);
        BigDecimal total = BigDecimal.ZERO;
        while (data.next()) total = total.add((BigDecimal) data.getFieldValue(field));
        return total;
    }

    private static class CustomerProbe extends SSCustomerRevenuePrinter {
        CustomerProbe(List<SSCustomer> customers, List<SSInvoice> invoices, List<SSCreditInvoice> credits) {
            super(company(), year(), customers, invoices, credits, FROM, TO);
        }
        BigDecimal total(int row) throws Exception { return sum((JRDataSource) getModel().getValueAt(row, 2)); }
    }

    private static class SupplierProbe extends SSSupplierRevenuePrinter {
        SupplierProbe(List<SSSupplier> suppliers, List<SSSupplierInvoice> invoices,
                List<SSSupplierCreditInvoice> credits) {
            super(company(), year(), suppliers, invoices, credits, FROM, TO);
        }
        BigDecimal total(int row) throws Exception { return sum((JRDataSource) getModel().getValueAt(row, 2)); }
    }
}
