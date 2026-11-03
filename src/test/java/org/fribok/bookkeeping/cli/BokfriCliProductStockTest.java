package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSIndelivery;
import se.swedsoft.bookkeeping.data.SSIndeliveryRow;
import se.swedsoft.bookkeeping.data.SSInventory;
import se.swedsoft.bookkeeping.data.SSInventoryRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSOutdelivery;
import se.swedsoft.bookkeeping.data.SSOutdeliveryRow;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSPurchaseOrder;
import se.swedsoft.bookkeeping.data.SSPurchaseOrderRow;
import se.swedsoft.bookkeeping.data.SSStock;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BokfriCliProductStockTest {
    @TempDir Path temporaryDirectory;

    @Test
    void allStockSourcesAndRegisterExportsMatchLegacyWithCompanyIsolation() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        int companyId;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = company(runtime, "Selected stock company");
            companyId = company.getId();
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setLocalFrom(LocalDate.of(2024, 1, 1));
            year.setLocalTo(LocalDate.of(2024, 12, 31));
            runtime.database().addAccountingYear(year);
            yearId = year.getId();
            documents(runtime);
            company(runtime, "Other stock company");
            documents(runtime);
        }
        String expectedPdf = null;
        List<List<String>> expectedSheet = null;
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                runtime.selectCompany(companyId);
                List<SSProduct> products = runtime.getProducts();
                SSStock stock = normalized ? runtime.normalizedStock(products) : new SSStock(true);
                SSProduct a = products.stream().filter(p -> p.getNumber().equals("A")).findFirst().orElseThrow();
                assertThat(stock.getQuantity(a)).isEqualTo(25);
                assertThat(stock.getReserved(a)).isEqualTo(10);
                assertThat(stock.getOrdered(a)).isEqualTo(10);
                assertThat(stock.getAvaiable(a)).isEqualTo(15);
                assertThat(stock.getQuantity(products.stream().filter(p -> p.getNumber().equals("B"))
                        .findFirst().orElseThrow())).isZero();
            }
            for (boolean pdf : List.of(true, false)) {
                Path output = temporaryDirectory.resolve("products-" + normalized + (pdf ? ".pdf" : ".xlsx"));
                List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(), "--company-id",
                        Integer.toString(companyId), "--year-id", Integer.toString(yearId), "--format", "json",
                        "product", pdf ? "list" : "export", "--output", output.toString()));
                Result result = execute(args);
                assertThat(result.code()).as(result.error()).isZero();
                if (pdf) {
                    try (var document = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
                        String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                        assertThat(text).contains("Selected stock company", "Product A", "Parcel").doesNotContain("Other");
                        if (normalized) assertThat(text).isEqualTo(expectedPdf);
                        else expectedPdf = text;
                    }
                } else {
                    List<List<String>> sheet = cells(output);
                    if (normalized) assertThat(sheet).isEqualTo(expectedSheet);
                    else expectedSheet = sheet;
                    assertThat(sheet.get(1).get(13)).isEqualTo("25");
                    assertThat(sheet.get(1).get(14)).isEqualTo("15");
                }
                if (normalized) {
                    byte[] original = Files.readAllBytes(output);
                    assertThat(execute(args).code()).isEqualTo(1);
                    assertThat(Files.readAllBytes(output)).isEqualTo(original);
                    args.add("--overwrite");
                    Result overwritten = execute(args);
                    assertThat(overwritten.code()).as(overwritten.error()).isZero();
                    assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                    assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                }
            }
        }
    }

    @Test
    void fractionalStockQuantitiesFailExactlyRatherThanTruncating() throws Exception {
        Path data = temporaryDirectory.resolve("fractional");
        int companyId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            companyId = company(runtime, "Fractional company").getId();
            SSProduct product = new SSProduct();
            product.setNumber("A");
            product.setStockProduct(true);
            runtime.database().addProduct(product);
            SSOrder order = new SSOrder(new SSInvoice());
            order.getRows().add(sale("A", "1.5"));
            runtime.database().addOrder(order);
            runtime.database().clearLists();
            assertThatThrownBy(() -> new SSStock(true)).isInstanceOf(ArithmeticException.class);
        }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            runtime.selectCompany(companyId);
            assertThatThrownBy(() -> runtime.normalizedStock(runtime.getProducts()))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    private static SSNewCompany company(BokfriRuntime runtime, String name) throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName(name);
        runtime.database().addCompany(company);
        runtime.selectCompany(company.getId());
        return company;
    }

    private static void documents(BokfriRuntime runtime) {
        for (String number : List.of("A", "B", "P")) {
            SSProduct product = new SSProduct();
            product.setNumber(number);
            product.setDescription(number.equals("P") ? "Parcel" : "Product " + number);
            product.setStockProduct(!number.equals("B"));
            product.setSellingPrice(BigDecimal.TEN);
            if (number.equals("P")) {
                SSProductRow component = new SSProductRow();
                component.setProduct("A");
                component.setQuantity(2);
                product.setParcelRows(List.of(component));
            }
            runtime.database().addProduct(product);
        }
        SSInvoice invoice = new SSInvoice();
        invoice.setStockInfluencing(true);
        invoice.getRows().add(sale("A", "3"));
        invoice.getRows().add(sale("P", "2"));
        invoice.getRows().add(sale("B", "999"));
        runtime.database().addInvoice(invoice);
        SSInvoice disabled = new SSInvoice();
        disabled.setStockInfluencing(false);
        disabled.getRows().add(sale("A", "999.5"));
        runtime.database().addInvoice(disabled);
        SSCreditInvoice credit = new SSCreditInvoice();
        credit.setStockInfluencing(true);
        credit.getRows().add(sale("A", "1"));
        credit.getRows().add(sale("P", "1"));
        runtime.database().addCreditInvoice(credit);
        for (boolean influencing : List.of(true, false)) {
            SSSupplierInvoice purchase = new SSSupplierInvoice();
            purchase.setStockInfluencing(influencing);
            purchase.getRows().add(purchase("A", influencing ? 20 : 999));
            purchase.getRows().add(purchase("P", 2));
            runtime.database().addSupplierInvoice(purchase);
        }
        SSSupplierCreditInvoice supplierCredit = new SSSupplierCreditInvoice();
        supplierCredit.setStockInfluencing(true);
        supplierCredit.getRows().add(purchase("A", 2));
        supplierCredit.getRows().add(purchase("P", 1));
        runtime.database().addSupplierCreditInvoice(supplierCredit);
        for (boolean linked : List.of(false, true)) {
            SSOrder order = new SSOrder(new SSInvoice());
            order.setInvoiceNr(linked ? invoice.getNumber() : null);
            order.getRows().add(sale("A", linked ? "999" : "4"));
            order.getRows().add(sale("P", "3"));
            runtime.database().addOrder(order);
            SSPurchaseOrder purchaseOrder = new SSPurchaseOrder();
            purchaseOrder.setStockInfluencing(true);
            purchaseOrder.setInvoiceNr(linked ? 1 : null);
            SSPurchaseOrderRow row = new SSPurchaseOrderRow();
            row.setProductNr("A");
            row.setQuantity(linked ? 999 : 6);
            purchaseOrder.getRows().add(row);
            SSPurchaseOrderRow parcel = new SSPurchaseOrderRow();
            parcel.setProductNr("P");
            parcel.setQuantity(2);
            purchaseOrder.getRows().add(parcel);
            runtime.database().addPurchaseOrder(purchaseOrder);
        }
        SSPurchaseOrder disabledOrder = new SSPurchaseOrder();
        disabledOrder.setStockInfluencing(false);
        SSPurchaseOrderRow ignored = new SSPurchaseOrderRow();
        ignored.setProductNr("A");
        ignored.setQuantity(999);
        disabledOrder.getRows().add(ignored);
        runtime.database().addPurchaseOrder(disabledOrder);
        SSInventory inventory = new SSInventory();
        SSInventoryRow inventoryRow = new SSInventoryRow();
        inventoryRow.setProductNr("A");
        inventoryRow.setChange(5);
        inventoryRow.setStockQuantity(500);
        inventory.getRows().add(inventoryRow);
        runtime.database().addInventory(inventory);
        SSIndelivery in = new SSIndelivery();
        SSIndeliveryRow inRow = new SSIndeliveryRow();
        inRow.setProductNr("A");
        inRow.setChange(7);
        in.getRows().add(inRow);
        runtime.database().addIndelivery(in);
        SSOutdelivery out = new SSOutdelivery();
        SSOutdeliveryRow outRow = new SSOutdeliveryRow();
        outRow.setProductNr("A");
        outRow.setChange(3);
        out.getRows().add(outRow);
        runtime.database().addOutdelivery(out);
    }

    private static SSSaleRow sale(String product, String quantity) {
        SSSaleRow row = new SSSaleRow();
        row.setProductNr(product);
        row.setQuantity(new BigDecimal(quantity));
        return row;
    }

    private static SSSupplierInvoiceRow purchase(String product, int quantity) {
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setProductNr(product);
        row.setQuantity(quantity);
        return row;
    }

    private static List<List<String>> cells(Path file) throws Exception {
        try (var input = Files.newInputStream(file);
                var workbook = new org.dhatim.fastexcel.reader.ReadableWorkbook(input)) {
            List<List<String>> values = new ArrayList<>();
            for (var row : workbook.getFirstSheet().read()) {
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < 16; column++) {
                    var cell = row.getCell(column);
                    cells.add(cell == null ? "" : cell.getType() == org.dhatim.fastexcel.reader.CellType.NUMBER
                            ? new BigDecimal(cell.getRawValue()).stripTrailingZeros().toPlainString()
                            : cell.getRawValue());
                }
                values.add(cells);
            }
            return values;
        }
    }

    private Result execute(List<String> args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = BokfriCli.execute(args.toArray(String[]::new),
                new PrintWriter(output, true, StandardCharsets.UTF_8),
                new PrintWriter(error, true, StandardCharsets.UTF_8));
        return new Result(code, error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String error) {}
}
