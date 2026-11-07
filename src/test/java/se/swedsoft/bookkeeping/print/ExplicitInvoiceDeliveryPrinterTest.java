package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRRewindableDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.print.report.sales.SSDeliverynotePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSPickingslipPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitInvoiceDeliveryPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void deliveryVariantsUseExplicitCompanyCustomerAndProducts() throws Exception {
        for (boolean hide : List.of(false, true)) {
            SSOrder order = order();
            order.setHideUnitprice(hide);
            SSCustomer customer = new SSCustomer();
            customer.setVATNumber("VAT-EXPLICIT");
            SSProduct product = product("P1", "Shelf B");
            product.setDescription(Locale.ENGLISH, "Localized delivery product");
            List<SSProduct> products = new ArrayList<>(List.of(product));
            var printer = new SSDeliverynotePrinter(order, Locale.ENGLISH, company(), customer, products);
            products.clear();
            assertThat(printer.getParameters()).containsEntry("lastvoucher", 0)
                    .containsEntry("company", "Explicit delivery company")
                    .containsEntry("order.vatnumber", "VAT-EXPLICIT");
            String text = pdfText(printer, "delivery" + hide);
            assertThat(text).contains("Explicit delivery company", "Stored delivery address",
                    "Localized delivery product", "Missing product row").doesNotContain("Stored product row");
            var nested = (java.util.Map<?, ?>) printer.getParameters().get("subreport.parameters");
            assertThat(nested.get("company")).isEqualTo("Explicit delivery company");
            assertThat(nested.get("lastvoucher")).isEqualTo(0);
            var rows = (JRRewindableDataSource) printer.getParameters().get("subreport.datasource");
            rows.moveFirst();
            assertThat(rows.next()).isTrue();
            assertThat(field(rows, "product.unitprice")).isEqualTo(new BigDecimal("123.45"));
            assertThat(field(rows, "product.weight")).isEqualTo(BigDecimal.ZERO);
            assertThat(field(rows, "product.volume")).isEqualTo(BigDecimal.ZERO);
            assertThat(field(rows, "product.unit")).isNull();
        }
    }

    @Test
    void pickingSortsByExplicitWarehouseWithoutMutatingRows() throws Exception {
        SSOrder order = order();
        order.getRows().add(row("P2", "Second product"));
        order.getRows().add(row("P3", "Third product"));
        List<SSSaleRow> original = new ArrayList<>(order.getRows());
        List<SSProduct> products = new ArrayList<>(List.of(product("P1", "Shelf B"),
                product("P2", "Shelf A"), product("P3", null)));
        var printer = new SSPickingslipPrinter(order, Locale.ENGLISH, company(), products);
        products.clear();
        String text = pdfText(printer, "picking");
        assertThat(text).contains("Explicit delivery company", "Shelf A", "Shelf B", "Missing product row")
                .doesNotContain("Wrong cached shelf");
        var nested = (java.util.Map<?, ?>) printer.getParameters().get("subreport.parameters");
        assertThat(nested.get("company")).isEqualTo("Explicit delivery company");
        assertThat(nested.get("lastvoucher")).isEqualTo(0);
        var rows = (JRRewindableDataSource) printer.getParameters().get("subreport.datasource");
        rows.moveFirst();
        List<Object> numbers = new ArrayList<>();
        while (rows.next()) numbers.add(field(rows, "product.number"));
        assertThat(numbers).containsExactly("P3", "P2", "P1", "missing");
        assertThat(order.getRows()).containsExactlyElementsOf(original);
    }

    @Test
    void deliveryToleratesDeletedCustomerAndNullDates() throws Exception {
        SSOrder order = order();
        order.setLocalDate(null);
        var printer = new SSDeliverynotePrinter(order, Locale.ENGLISH, company(), null, List.of());
        assertThat(printer.getParameters()).doesNotContainKey("order.vatnumber");
        assertThat(pdfText(printer, "missing-customer")).contains("Stored product row", "Missing product row");
    }

    private static Object field(JRRewindableDataSource rows, String name) throws Exception {
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

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit delivery company");
        return company;
    }

    private static SSProduct product(String number, String warehouse) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setWarehouseLocation(warehouse);
        product.setWeight(null);
        product.setVolume(null);
        product.setUnit(null);
        return product;
    }

    private static SSOrder order() {
        SSOrder order = new SSOrder();
        order.setNumber(7);
        order.getDeliveryAddress().setName("Stored delivery address");
        order.getRows().add(row("P1", "Stored product row"));
        order.getRows().add(row("missing", "Missing product row"));
        return order;
    }

    private static SSSaleRow row(String number, String description) {
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
        };
        SSProduct stale = product(number, "Wrong cached shelf");
        stale.setWeight(new BigDecimal("999"));
        stale.setVolume(new BigDecimal("999"));
        row.setProduct(stale);
        row.setDescription(description);
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("123.45"));
        return row;
    }
}
