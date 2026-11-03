package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.fribok.bookkeeping.service.SaleValuesData;
import se.swedsoft.bookkeeping.data.SSMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSTender;
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
        for (String report : List.of("customer-revenue", "supplier-revenue", "product-revenue", "sales-report", "sale-values")) {
            for (String[] period : periods()) {
                Path pdf = temporaryDirectory.resolve("legacy-" + report + "-" + period[0] + ".pdf");
                Result result = execute(arguments(context, report, period, pdf));
                assertThat(result.code()).as(result.error()).isZero();
                String text = text(pdf);
                assertThat(text).contains("Revenue selected company").doesNotContain("Other");
                if (!report.equals("sale-values")) assertThat(text).contains("Selected");
                if (period[0].equals("2024-02-01") && !report.equals("sales-report") && !report.equals("sale-values")) {
                    assertThat(text).containsPattern(report.equals("supplier-revenue") ? "380[.,]50" : "342[.,]45");
                }
                expected.put(report + period[0], text);
            }
        }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            runtime.selectCompany(companyId);
            SaleValuesData values = runtime.normalizedSaleValues();
            assertThat(values.tenders()).hasSize(4);
            assertThat(values.orders()).hasSize(4);
            SSMonth february = new SSMonth(LocalDate.of(2024, 2, 1));
            assertThat(SaleValuesData.total(values.tenders(), february))
                    .isCloseTo(541.35, org.assertj.core.data.Offset.offset(0.000001));
            assertThat(SaleValuesData.total(values.orders(), february))
                    .isCloseTo(812.025, org.assertj.core.data.Offset.offset(0.000001));
            assertThat(SaleValuesData.total(values.invoices(), february)
                    - SaleValuesData.total(values.credits(), february))
                    .isCloseTo(522.9, org.assertj.core.data.Offset.offset(0.000001));
        }
        for (String report : List.of("customer-revenue", "supplier-revenue", "product-revenue", "sales-report", "sale-values")) {
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

    @Test
    void normalizedBalancesMatchLegacyAcrossCutoffsAndKeepCompanyIsolation() throws Exception {
        Path data = temporaryDirectory.resolve("customer-balances");
        int companyId;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = addCompany(runtime, "Selected balance company");
            companyId = company.getId();
            yearId = addYear(runtime).getId();
            addDocuments(runtime, "Selected", "100.25");
            addBalanceDocuments(runtime);
            addCompany(runtime, "Other company must not leak");
            addYear(runtime);
            addDocuments(runtime, "Other", "9999.99");
            addBalanceDocuments(runtime);
        }
        List<String> cutoffs = List.of("2023-01-01", "2024-02-10", "2024-02-20", "2024-02-29");
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (String report : List.of("accounts-receivable", "customer-claims", "accounts-payable", "supplier-debts")) {
                for (String date : cutoffs) {
                    Path pdf = temporaryDirectory.resolve(report + normalized + date + ".pdf");
                    List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(),
                            "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(yearId),
                            "--format", "json", report, "--date", date, "--output", pdf.toString()));
                    Result result = execute(args.toArray(String[]::new));
                    assertThat(result.code()).as(result.error()).isZero();
                    String text = text(pdf);
                    assertThat(text).contains("Selected balance company").doesNotContain("Other");
                    if (normalized) {
                        assertThat(text).isEqualTo(expected.get(report + date));
                        var json = new ObjectMapper().readTree(result.output());
                        assertThat(json.path("date").asText()).isEqualTo(date);
                        assertThat(json.path("selection").path("companyName").asText())
                                .isEqualTo("Selected balance company");
                        byte[] original = Files.readAllBytes(pdf);
                        assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                        assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                        args.add("--overwrite");
                        Result overwritten = execute(args.toArray(String[]::new));
                        assertThat(overwritten.code()).as(overwritten.error()).isZero();
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    } else {
                        expected.put(report + date, text);
                    }
                }
            }
        }
    }

    @Test
    void partyRegisterPdfsMatchLegacyWithoutYearAndKeepCompanyIsolation() throws Exception {
        Path data = temporaryDirectory.resolve("party-registers");
        int selected;
        int empty;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected register company").getId();
            addPartyRegisters(runtime, "Selected");
            addCompany(runtime, "Other register company");
            addPartyRegisters(runtime, "Other");
            empty = addCompany(runtime, "Empty register company").getId();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int company : List.of(selected, empty)) {
                for (String register : List.of("customer", "supplier")) {
                    Path pdf = temporaryDirectory.resolve(register + company + normalized + ".pdf");
                    List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(),
                            "--company-id", Integer.toString(company), "--format", "json",
                            register, "list", "--output", pdf.toString()));
                    Result result = execute(args.toArray(String[]::new));
                    assertThat(result.code()).as(result.error()).isZero();
                    String text = text(pdf);
                    assertThat(text).contains(company == selected ? "Selected register company"
                            : "Empty register company").doesNotContain("Other");
                    if (company == selected) assertThat(text).contains("Selected party");
                    if (normalized) {
                        assertThat(text).isEqualTo(expected.get(register + company));
                        var json = new ObjectMapper().readTree(result.output());
                        assertThat(json.path("count").asInt()).isEqualTo(company == selected ? 1 : 0);
                        byte[] original = Files.readAllBytes(pdf);
                        assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                        assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                        args.add("--overwrite");
                        Result overwritten = execute(args.toArray(String[]::new));
                        assertThat(overwritten.code()).as(overwritten.error()).isZero();
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    } else {
                        expected.put(register + company, text);
                    }
                }
            }
        }
    }

    private static void addPartyRegisters(BokfriRuntime runtime, String name) throws Exception {
        SSCustomer customer = new SSCustomer();
        customer.setNumber("P1");
        customer.setName(name + " party");
        customer.setYourContactPerson(name + " contact");
        customer.setPhone1("012345");
        runtime.database().addCustomer(customer);
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber("P1");
        supplier.setName(name + " party");
        supplier.setYourContact(name + " contact");
        supplier.setPhone1("012345");
        runtime.database().addSupplier(supplier);
    }

    private static void addBalanceDocuments(BokfriRuntime runtime) throws Exception {
        // Legacy insert methods do not invalidate every cached register list.
        runtime.database().clearLists();
        SSInvoice invoice = runtime.getInvoices().get(1);
        SSCreditInvoice credit = new SSCreditInvoice();
        configure(credit, "C1", "2024-02-20", "10.25");
        credit.setCrediting(invoice);
        runtime.database().addCreditInvoice(credit);
        for (String date : List.of("2024-02-10", "2024-02-20", "2024-02-21")) {
            SSInpayment payment = new SSInpayment();
            payment.setLocalDate(LocalDate.parse(date));
            SSInpaymentRow row = new SSInpaymentRow();
            row.setInvoiceNr(invoice.getNumber());
            row.setValue(new BigDecimal("25.005"));
            payment.getRows().add(row);
            runtime.database().addInpayment(payment);
        }
        SSSupplierInvoice purchase = runtime.getSupplierInvoices().get(1);
        SSSupplierCreditInvoice supplierCredit = new SSSupplierCreditInvoice();
        configure(supplierCredit, "S1", "2024-02-20", "10.25");
        supplierCredit.setTaxSum(new BigDecimal("2.50"));
        supplierCredit.setRoundingSum(new BigDecimal("0.25"));
        supplierCredit.setCrediting(purchase);
        runtime.database().addSupplierCreditInvoice(supplierCredit);
        for (String date : List.of("2024-02-10", "2024-02-20", "2024-02-21")) {
            SSOutpayment payment = new SSOutpayment();
            payment.setLocalDate(LocalDate.parse(date));
            SSOutpaymentRow row = new SSOutpaymentRow();
            row.setInvoiceNr(purchase.getNumber());
            row.setValue(new BigDecimal("25.005"));
            payment.getRows().add(row);
            runtime.database().addOutpayment(payment);
        }
        SSInvoice cash = new SSInvoice();
        configure(cash, "C1", "2024-02-10", "999");
        cash.setType(SSInvoiceType.CASH);
        runtime.database().addInvoice(cash);
    }

    private static List<String[]> periods() {
        return List.of(new String[] {"2024-02-01", "2024-02-20"},
                new String[] {"2023-12-01", "2024-03-31"},
                new String[] {"2024-02-10", "2024-02-20"});
    }

    private static String[] arguments(String[] context, String report, String[] period, Path pdf) {
        List<String> args = new ArrayList<>(List.of(context));
        args.addAll(List.of(report, "--from", period[0], "--to", period[1], "--output", pdf.toString()));
        if (report.equals("sales-report")) args.addAll(List.of("--sort", "AverageSellingPrice", "--descending"));
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
        product.setSellingPrice(new BigDecimal("120"));
        product.setStockPrice(new BigDecimal("25"));
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
            SSTender tender = new SSTender();
            tender.setLocalDate(LocalDate.parse(date));
            tender.setCurrencyRate(new BigDecimal("2"));
            tender.getRows().addAll(invoice.getRows());
            runtime.database().addTender(tender);
            SSOrder order = new SSOrder(invoice);
            order.setCurrencyRate(new BigDecimal("3"));
            runtime.database().addOrder(order);
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
        row.setProductNr("P1");
        row.setUnitFreight(new BigDecimal("3"));
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
