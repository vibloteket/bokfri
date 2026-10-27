package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BokfriCliRevenueReportTest {
    @TempDir Path temporaryDirectory;

    @Test
    void normalizedRevenuePdfsMatchLegacyAcrossPeriodsAndKeepCompanyIsolation() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        int companyId;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = addCompany(runtime, "Revenue selected company");
            companyId = company.getId();
            yearId = addYear(runtime).getId();
            addDocuments(runtime, "Selected", "100.25");
            addCompany(runtime, "Other company must not leak");
            addYear(runtime);
            addDocuments(runtime, "Other", "9999.99");
        }
        String[] context = {"--data-dir", data.toString(), "--company-id", Integer.toString(companyId),
                "--year-id", Integer.toString(yearId), "--format", "json"};
        Map<String, String> expected = new LinkedHashMap<>();
        for (String report : List.of("customer-revenue", "supplier-revenue", "product-revenue")) {
            for (String[] period : periods()) {
                Path pdf = temporaryDirectory.resolve("legacy-" + report + "-" + period[0] + ".pdf");
                Result result = execute(arguments(context, report, period, pdf));
                assertThat(result.code()).as(result.error()).isZero();
                String text = text(pdf);
                assertThat(text).contains("Revenue selected company", "Selected").doesNotContain("Other");
                if (period[0].equals("2024-02-01")) {
                    assertThat(text).containsPattern(report.equals("supplier-revenue") ? "380[.,]50" : "342[.,]45");
                }
                expected.put(report + period[0], text);
            }
        }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        for (String report : List.of("customer-revenue", "supplier-revenue", "product-revenue")) {
            for (String[] period : periods()) {
                Path pdf = temporaryDirectory.resolve("normalized-" + report + "-" + period[0] + ".pdf");
                String[] args = arguments(context, report, period, pdf);
                Result result = execute(args);
                assertThat(result.code()).as(result.error()).isZero();
                assertThat(text(pdf)).isEqualTo(expected.get(report + period[0]));
                var json = new ObjectMapper().readTree(result.output());
                assertThat(json.path("from").asText()).isEqualTo(period[0]);
                assertThat(json.path("to").asText()).isEqualTo(period[1]);
                assertThat(json.path("selection").path("companyName").asText())
                        .isEqualTo("Revenue selected company");
                byte[] original = Files.readAllBytes(pdf);
                assertThat(execute(args).code()).isEqualTo(1);
                assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                List<String> overwrite = new ArrayList<>(List.of(args));
                overwrite.add("--overwrite");
                Result overwritten = execute(overwrite.toArray(String[]::new));
                assertThat(overwritten.code()).as(overwritten.error()).isZero();
                assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                assertThat(SSDB.getInstance().getCurrentYear()).isNull();
            }
        }
    }

    private static List<String[]> periods() {
        return List.of(new String[] {"2024-02-01", "2024-02-20"},
                new String[] {"2023-12-01", "2024-03-31"},
                new String[] {"2024-02-10", "2024-02-20"});
    }

    private static String[] arguments(String[] context, String report, String[] period, Path pdf) {
        List<String> args = new ArrayList<>(List.of(context));
        args.addAll(List.of(report, "--from", period[0], "--to", period[1], "--output", pdf.toString()));
        return args.toArray(String[]::new);
    }

    private static SSNewCompany addCompany(BokfriRuntime runtime, String name) throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setName(name);
        runtime.database().addCompany(company);
        runtime.selectCompany(company.getId());
        return company;
    }

    private static SSNewAccountingYear addYear(BokfriRuntime runtime) throws Exception {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2024, 1, 1));
        year.setLocalTo(LocalDate.of(2024, 12, 31));
        SSAccountPlan plan = new SSAccountPlan();
        plan.getAccounts().add(new SSAccount(3010));
        plan.getAccounts().add(new SSAccount(1930));
        year.setAccountPlan(plan);
        runtime.database().addAccountingYear(year);
        runtime.database().setCurrentYear(year);
        return year;
    }

    private static void addDocuments(BokfriRuntime runtime, String name, String price) throws Exception {
        SSProduct product = new SSProduct();
        product.setNumber("P1");
        product.setDescription(name + " product");
        runtime.database().addProduct(product);
        SSProduct inactiveProduct = new SSProduct();
        inactiveProduct.setNumber("P2");
        inactiveProduct.setDescription(name + " inactive product");
        runtime.database().addProduct(inactiveProduct);
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C1");
        customer.setName(name + " customer");
        runtime.database().addCustomer(customer);
        SSCustomer unused = new SSCustomer();
        unused.setNumber("C2");
        unused.setName(name + " inactive customer");
        runtime.database().addCustomer(unused);
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber("S1");
        supplier.setName(name + " supplier");
        runtime.database().addSupplier(supplier);
        SSSupplier unusedSupplier = new SSSupplier();
        unusedSupplier.setNumber("S2");
        unusedSupplier.setName(name + " inactive supplier");
        runtime.database().addSupplier(unusedSupplier);
        for (String date : List.of("2023-12-31", "2024-02-10", "2024-02-20", "2024-02-21")) {
            SSInvoice invoice = new SSInvoice();
            configure(invoice, customer.getNumber(), date, price);
            runtime.database().addInvoice(invoice);
            SSSupplierInvoice purchase = new SSSupplierInvoice();
            configure(purchase, supplier.getNumber(), date, price);
            runtime.database().addSupplierInvoice(purchase);
        }
        SSCreditInvoice credit = new SSCreditInvoice();
        configure(credit, customer.getNumber(), "2024-02-20", "10.25");
        runtime.database().addCreditInvoice(credit);
        SSSupplierCreditInvoice supplierCredit = new SSSupplierCreditInvoice();
        configure(supplierCredit, supplier.getNumber(), "2024-02-20", "10.25");
        runtime.database().addSupplierCreditInvoice(supplierCredit);
    }

    private static void configure(SSInvoice invoice, String customer, String date, String price) {
        invoice.setCustomerNr(customer);
        invoice.setLocalDate(LocalDate.parse(date));
        invoice.setCurrencyRate(new BigDecimal("2"));
        SSSaleRow row = new SSSaleRow();
        row.setProductNr("P1");
        row.setUnitprice(new BigDecimal(price));
        row.setQuantity(1);
        row.setDiscount(new BigDecimal("10"));
        invoice.getRows().add(row);
    }

    private static void configure(SSSupplierInvoice invoice, String supplier, String date, String price) {
        invoice.setSupplierNr(supplier);
        invoice.setLocalDate(LocalDate.parse(date));
        invoice.setCurrencyRate(new BigDecimal("2"));
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setUnitprice(new BigDecimal(price));
        row.setQuantity(1);
        invoice.getRows().add(row);
    }

    private Result execute(String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = BokfriCli.execute(arguments, new PrintWriter(output, true, StandardCharsets.UTF_8),
                new PrintWriter(error, true, StandardCharsets.UTF_8));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private static String text(Path pdf) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }

    private record Result(int code, String output, String error) {}
}
