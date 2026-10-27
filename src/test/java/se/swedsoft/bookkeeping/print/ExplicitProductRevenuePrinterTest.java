package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.print.report.SSProductRevenuePrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitProductRevenuePrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate FROM = LocalDate.of(2024, 2, 10);
    private static final LocalDate TO = LocalDate.of(2024, 2, 20);

    @Test
    void rowTotalsPreserveDiscountCurrencyCreditsAndInclusiveBoundariesWithoutProductLookups() throws Exception {
        SSInvoice invoice = sale(new SSInvoice(), "ACTIVE", "2024-02-10", "100.25", "2");
        invoice.getRows().get(0).setDiscount(new BigDecimal("10"));
        invoice.getRows().get(0).setQuantity(2);
        invoice.getRows().add(sale(new SSInvoice(), "UNREGISTERED", "2024-02-10", "7.25", "1")
                .getRows().get(0));
        SSSaleRow missingAmount = new SSSaleRow();
        missingAmount.setProductNr("ACTIVE");
        invoice.getRows().add(missingAmount);
        Probe printer = new Probe(List.of(product("ACTIVE"), product("CREDIT"), product("EMPTY")),
                List.of(invoice, sale(new SSInvoice(), "ACTIVE", "2024-02-20", "20.75", null),
                        sale(new SSInvoice(), "ACTIVE", "2024-02-09", "900", "1"),
                        sale(new SSInvoice(), "ACTIVE", "2024-02-21", "900", "1"),
                        sale(new SSInvoice(), null, "2024-02-10", "900", "1"),
                        sale(new SSInvoice(), "ACTIVE", null, "900", "1")),
                List.of(sale(new SSCreditInvoice(), "ACTIVE", "2024-02-20", "10.25", "2"),
                        sale(new SSCreditInvoice(), "CREDIT", "2024-02-10", "5.50", null),
                        sale(new SSCreditInvoice(), "ACTIVE", "2024-02-21", "900", "1"),
                        sale(new SSCreditInvoice(), "ACTIVE", null, "900", "1")), FROM, TO);
        assertThat(printer.values(0)).singleElement()
                .satisfies(value -> assertThat(value).isEqualByComparingTo("361.15"));
        assertThat(printer.values(1)).singleElement()
                .satisfies(value -> assertThat(value).isEqualByComparingTo("-5.50"));
        assertThat(printer.values(2)).singleElement().satisfies(value -> assertThat(value).isZero());
        assertThat(invoice.getRows()).hasSize(3);
        assertThat(invoice.getRows().get(0).getQuantity()).isEqualTo(new BigDecimal("2"));
        assertThat(invoice.getRows().get(0).getProductNr()).isEqualTo("ACTIVE");
        assertThat(invoice.getRows().get(0).getUnitprice()).isEqualByComparingTo("100.25");
        assertThat(invoice.getRows().get(0).getDiscount()).isEqualByComparingTo("10");
        assertThat(invoice.getCurrencyRate()).isEqualByComparingTo("2");
    }

    @Test
    void monthlyBucketsIncludeYearRolloverAndPreserveSuppliedRegisterOrder() throws Exception {
        Probe printer = new Probe(List.of(product("Z"), product("A")),
                List.of(sale(new SSInvoice(), "Z", "2023-12-31", "12", "1"),
                        sale(new SSInvoice(), "Z", "2024-01-01", "10", "1"),
                        sale(new SSInvoice(), "Z", "2024-02-29", "29", "1")),
                List.of(sale(new SSCreditInvoice(), "Z", "2024-01-31", "3", "1")),
                LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 29));
        assertThat(printer.number(0)).isEqualTo("Z");
        assertThat(printer.number(1)).isEqualTo("A");
        assertThat(printer.values(0)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("12"), new BigDecimal("7"), new BigDecimal("29"));
        assertThat(printer.values(1)).allSatisfy(value -> assertThat(value).isZero());
    }

    @Test
    void populatedInactiveAndEmptyPdfsRenderWithExplicitOuterAndNestedHeaders() throws Exception {
        for (List<SSProduct> products : List.of(List.<SSProduct>of(), List.of(product("ACTIVE"), product("EMPTY")))) {
            Probe printer = new Probe(products,
                    List.of(sale(new SSInvoice(), "ACTIVE", "2024-02-10", "123.45", "1")), List.of(),
                    LocalDate.of(2024, 2, 1), TO);
            assertThat(printer.getParameters()).containsEntry("company", "Explicit product company")
                    .containsEntry("lastvoucher", 0);
            ReportService reports = new ReportService();
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("products-" + products.size() + ".pdf"), false);
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                assertThat(text).contains("Explicit product company");
                if (!products.isEmpty()) assertThat(text).contains("ACTIVE", "EMPTY", "123.45");
            }
        }
    }

    private static SSProduct product(String number) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setDescription(number);
        return product;
    }

    private static <T extends SSInvoice> T sale(T invoice, String product, String date, String price, String rate) {
        invoice.setLocalDate(date == null ? null : LocalDate.parse(date));
        invoice.setCurrencyRate(rate == null ? null : new BigDecimal(rate));
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
            @Override public SSProduct getProduct(List<SSProduct> products) {
                throw new AssertionError("Unnecessary product lookup");
            }
        };
        row.setProductNr(product);
        row.setUnitprice(new BigDecimal(price));
        row.setQuantity(1);
        invoice.getRows().add(row);
        return invoice;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit product company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    private static class Probe extends SSProductRevenuePrinter {
        Probe(List<SSProduct> products, List<SSInvoice> invoices, List<SSCreditInvoice> credits,
                LocalDate from, LocalDate to) {
            super(company(), year(), products, invoices, credits, from, to);
        }
        Object number(int row) { return getModel().getValueAt(row, 0); }
        List<BigDecimal> values(int row) throws Exception {
            JRDataSource data = (JRDataSource) getModel().getValueAt(row, 2);
            JRDesignField field = new JRDesignField();
            field.setName("month.value");
            field.setValueClass(BigDecimal.class);
            List<BigDecimal> values = new ArrayList<>();
            while (data.next()) values.add((BigDecimal) data.getFieldValue(field));
            return values;
        }
    }
}
