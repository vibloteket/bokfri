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

    @Test
    void paymentListPdfsMatchLegacyForPopulatedAndEmptyCompanies() throws Exception {
        Path data = temporaryDirectory.resolve("payment-lists");
        List<int[]> contexts = new ArrayList<>();
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            int selected = addCompany(runtime, "Selected payment company").getId();
            contexts.add(new int[] {selected, addYear(runtime).getId()});
            addDocuments(runtime, "Selected", "100.25");
            addBalanceDocuments(runtime);
            addCompany(runtime, "Other payment company");
            addYear(runtime);
            addDocuments(runtime, "Other", "9999.99");
            addBalanceDocuments(runtime);
            SSInpayment in = new SSInpayment();
            in.setText("Other-only payment");
            runtime.database().addInpayment(in);
            SSOutpayment out = new SSOutpayment();
            out.setText("Other-only payment");
            runtime.database().addOutpayment(out);
            int empty = addCompany(runtime, "Empty payment company").getId();
            contexts.add(new int[] {empty, addYear(runtime).getId()});
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int[] context : contexts) {
                for (String kind : List.of("inpayment", "outpayment")) {
                    Path pdf = temporaryDirectory.resolve(kind + context[0] + normalized + ".pdf");
                    List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(), "--company-id",
                            Integer.toString(context[0]), "--year-id", Integer.toString(context[1]),
                            "--format", "json", kind, "pdf-list", "--output", pdf.toString()));
                    Result result = execute(args.toArray(String[]::new));
                    assertThat(result.code()).as(result.error()).isZero();
                    String text = text(pdf);
                    assertThat(text).contains(context == contexts.get(0) ? "Selected payment company"
                            : "Empty payment company").doesNotContain("Other");
                    if (normalized) {
                        assertThat(text).isEqualTo(expected.get(kind + context[0]));
                        byte[] original = Files.readAllBytes(pdf);
                        assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                        assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                        args.add("--overwrite");
                        Result overwritten = execute(args.toArray(String[]::new));
                        assertThat(overwritten.code()).as(overwritten.error()).isZero();
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    } else {
                        expected.put(kind + context[0], text);
                    }
                }
            }
        }
    }

    @Test
    void paymentJournalPdfsMatchLegacyAndCommitOnlyAfterSuccessfulExport() throws Exception {
        Path data = temporaryDirectory.resolve("payment-journals");
        int selected;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected journal company").getId();
            yearId = addYear(runtime).getId();
            addJournalPayments(runtime, "Selected");
            addCompany(runtime, "Other journal company");
            addYear(runtime);
            addJournalPayments(runtime, "Other");
        }
        List<String> context = List.of("--data-dir", data.toString(), "--company-id", Integer.toString(selected),
                "--year-id", Integer.toString(yearId), "--format", "json");
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (String kind : List.of("inpayment", "outpayment")) {
                Path pdf = temporaryDirectory.resolve(kind + "-journal-" + normalized + ".pdf");
                List<String> args = new ArrayList<>(context);
                args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29",
                        "--output", pdf.toString()));
                Result preview = execute(args.toArray(String[]::new));
                assertThat(preview.code()).as(preview.error()).isZero();
                assertThat(new ObjectMapper().readTree(preview.output()).path("committed").asBoolean()).isFalse();
                String text = text(pdf);
                assertThat(text).contains("Selected journal company", "Selected payment").doesNotContain("Other");
                if (normalized) {
                    assertThat(text).isEqualTo(expected.get(kind));
                    byte[] original = Files.readAllBytes(pdf);
                    args.add("--commit");
                    assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                    assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                    List<String> show = new ArrayList<>(context);
                    show.addAll(List.of(kind, "show", "1"));
                    Result unchanged = execute(show.toArray(String[]::new));
                    assertThat(unchanged.code()).as(unchanged.error()).isZero();
                    assertThat(new ObjectMapper().readTree(unchanged.output()).path("entered").asBoolean()).isFalse();
                    args.remove("--commit");
                    args.add("--overwrite");
                    Result overwritten = execute(args.toArray(String[]::new));
                    assertThat(overwritten.code()).as(overwritten.error()).isZero();
                    assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                    assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                } else expected.put(kind, text);
            }
        }
        for (String kind : List.of("inpayment", "outpayment")) {
            List<String> args = new ArrayList<>(context);
            args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29", "--commit",
                    "--output", temporaryDirectory.resolve(kind + "-committed.pdf").toString()));
            Result committed = execute(args.toArray(String[]::new));
            assertThat(committed.code()).as(committed.error()).isZero();
            var json = new ObjectMapper().readTree(committed.output());
            assertThat(json.path("committed").asBoolean()).isTrue();
            assertThat(json.path("voucherNumber").asInt()).isPositive();
            assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
        }
    }

    @Test
    void invoiceJournalPdfsMatchLegacyAndCommitOnlyAfterSuccessfulExport() throws Exception {
        Path data = temporaryDirectory.resolve("invoice-journals");
        int selected;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected journal company").getId();
            yearId = addYear(runtime).getId();
            addInvoiceJournalDocuments(runtime, "Selected");
            addCompany(runtime, "Other journal company");
            addYear(runtime);
            addInvoiceJournalDocuments(runtime, "Other");
        }
        List<String> context = List.of("--data-dir", data.toString(), "--company-id", Integer.toString(selected),
                "--year-id", Integer.toString(yearId), "--format", "json");
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (String kind : List.of("invoice", "credit-invoice")) {
                Path pdf = temporaryDirectory.resolve(kind + "-journal-" + normalized + ".pdf");
                List<String> args = new ArrayList<>(context);
                args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29",
                        "--output", pdf.toString()));
                Result preview = execute(args.toArray(String[]::new));
                assertThat(preview.code()).as(preview.error()).isZero();
                assertThat(new ObjectMapper().readTree(preview.output()).path("committed").asBoolean()).isFalse();
                String text = text(pdf);
                assertThat(text).contains("Selected journal company", "Selected invoice").doesNotContain("Other");
                if (normalized) {
                    assertThat(text).isEqualTo(expected.get(kind));
                    byte[] original = Files.readAllBytes(pdf);
                    args.add("--commit");
                    assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                    assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                    try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                        runtime.selectCompany(selected);
                        List<? extends SSInvoice> invoices = kind.equals("invoice")
                                ? runtime.getInvoices() : runtime.getCreditInvoices();
                        assertThat(invoices).allSatisfy(invoice -> assertThat(invoice.isEntered()).isFalse());
                    }
                    args.remove("--commit");
                    args.add("--overwrite");
                    Result overwritten = execute(args.toArray(String[]::new));
                    assertThat(overwritten.code()).as(overwritten.error()).isZero();
                    assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                    assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                } else expected.put(kind, text);
            }
        }
        for (String kind : List.of("invoice", "credit-invoice")) {
            List<String> args = new ArrayList<>(context);
            args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29", "--commit",
                    "--output", temporaryDirectory.resolve(kind + "-committed.pdf").toString()));
            Result committed = execute(args.toArray(String[]::new));
            assertThat(committed.code()).as(committed.error()).isZero();
            var json = new ObjectMapper().readTree(committed.output());
            assertThat(json.path("committed").asBoolean()).isTrue();
            assertThat(json.path("voucherNumber").asInt()).isPositive();
            assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
        }
    }

    @Test
    void supplierJournalPdfsMatchLegacyAndCommitOnlyAfterSuccessfulExport() throws Exception {
        Path data = temporaryDirectory.resolve("supplier-journals");
        int selected;
        int yearId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected journal company").getId();
            yearId = addYear(runtime).getId();
            addSupplierJournalDocuments(runtime, "Selected");
            addCompany(runtime, "Other journal company");
            addYear(runtime);
            addSupplierJournalDocuments(runtime, "Other");
        }
        List<String> context = List.of("--data-dir", data.toString(), "--company-id", Integer.toString(selected),
                "--year-id", Integer.toString(yearId), "--format", "json");
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (String kind : List.of("supplier-invoice", "supplier-credit-invoice")) {
                Path pdf = temporaryDirectory.resolve(kind + "-journal-" + normalized + ".pdf");
                List<String> args = new ArrayList<>(context);
                args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29",
                        "--output", pdf.toString()));
                Result preview = execute(args.toArray(String[]::new));
                assertThat(preview.code()).as(preview.error()).isZero();
                assertThat(new ObjectMapper().readTree(preview.output()).path("committed").asBoolean()).isFalse();
                String text = text(pdf);
                assertThat(text).contains("Selected journal company", "Selected invoice").doesNotContain("Other");
                if (normalized) {
                    assertThat(text).isEqualTo(expected.get(kind));
                    byte[] original = Files.readAllBytes(pdf);
                    args.add("--commit");
                    assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                    assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                    try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                        runtime.selectCompany(selected);
                        List<? extends SSSupplierInvoice> invoices = kind.equals("supplier-invoice")
                                ? runtime.getSupplierInvoices() : runtime.getSupplierCreditInvoices();
                        assertThat(invoices).allSatisfy(invoice -> assertThat(invoice.isEntered()).isFalse());
                    }
                    args.remove("--commit");
                    args.add("--overwrite");
                    Result overwritten = execute(args.toArray(String[]::new));
                    assertThat(overwritten.code()).as(overwritten.error()).isZero();
                    assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                    assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                } else expected.put(kind, text);
            }
        }
        for (String kind : List.of("supplier-invoice", "supplier-credit-invoice")) {
            List<String> args = new ArrayList<>(context);
            args.addAll(List.of(kind, "journal", "--from", "2024-02-01", "--to", "2024-02-29", "--commit",
                    "--output", temporaryDirectory.resolve(kind + "-committed.pdf").toString()));
            Result committed = execute(args.toArray(String[]::new));
            assertThat(committed.code()).as(committed.error()).isZero();
            var json = new ObjectMapper().readTree(committed.output());
            assertThat(json.path("committed").asBoolean()).isTrue();
            assertThat(json.path("voucherNumber").asInt()).isPositive();
            assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
        }
    }

    private static void addSupplierJournalDocuments(BokfriRuntime runtime, String name) {
        SSSupplierInvoice original = new SSSupplierInvoice();
        for (SSSupplierInvoice invoice : List.of(original, new SSSupplierCreditInvoice())) {
            invoice.setLocalDate(LocalDate.of(2024, 2, 10));
            invoice.setSupplierNr("S1");
            invoice.setSupplierName(name + " invoice");
            invoice.setTaxSum(BigDecimal.ZERO);
            invoice.setRoundingSum(BigDecimal.ZERO);
            invoice.setCurrencyRate(BigDecimal.ONE);
            invoice.setDefaultAccounts(new java.util.HashMap<>(Map.of(
                    se.swedsoft.bookkeeping.data.common.SSDefaultAccount.SupplierDebt, 1930)));
            SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
            row.setQuantity(1);
            row.setUnitprice(new BigDecimal("100"));
            row.setAccountNr(3010);
            invoice.getRows().add(row);
            if (invoice instanceof SSSupplierCreditInvoice credit) {
                credit.setCrediting(original);
                runtime.database().addSupplierCreditInvoice(credit);
            } else runtime.database().addSupplierInvoice(invoice);
        }
    }

    private static void addInvoiceJournalDocuments(BokfriRuntime runtime, String name) {
        SSInvoice original = new SSInvoice();
        for (SSInvoice invoice : List.of(original, new SSCreditInvoice())) {
            invoice.setLocalDate(LocalDate.of(2024, 2, 10));
            invoice.setCustomerNr("C1");
            invoice.setCustomerName(name + " invoice");
            invoice.setTaxFree(true);
            invoice.setCurrencyRate(BigDecimal.ONE);
            invoice.setDefaultAccounts(new java.util.HashMap<>(Map.of(
                    se.swedsoft.bookkeeping.data.common.SSDefaultAccount.CustomerClaim, 1930)));
            SSSaleRow row = new SSSaleRow();
            row.setQuantity(1);
            row.setUnitprice(new BigDecimal("100"));
            row.setAccountNr(3010);
            invoice.getRows().add(row);
            if (invoice instanceof SSCreditInvoice credit) {
                credit.setCrediting(original);
                runtime.database().addCreditInvoice(credit);
            } else runtime.database().addInvoice(invoice);
        }
    }

    private static void addJournalPayments(BokfriRuntime runtime, String name) {
        SSInpayment in = new SSInpayment();
        in.setLocalDate(LocalDate.of(2024, 2, 10));
        in.setText(name + " payment");
        in.setDefaultAccounts(new java.util.HashMap<>(Map.of(
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.CustomerClaim, 3010,
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.InPayment, 1930)));
        SSInpaymentRow inRow = new SSInpaymentRow();
        inRow.setInvoiceNr(99);
        inRow.setValue(new BigDecimal("25"));
        inRow.setCurrencyRate(BigDecimal.ONE);
        inRow.setInvoiceCurrencyRate(BigDecimal.ONE);
        in.getRows().add(inRow);
        runtime.database().addInpayment(in);
        SSOutpayment out = new SSOutpayment();
        out.setLocalDate(LocalDate.of(2024, 2, 10));
        out.setText(name + " payment");
        out.setDefaultAccounts(new java.util.HashMap<>(Map.of(
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.SupplierDebt, 3010,
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.OutPayment, 1930)));
        SSOutpaymentRow outRow = new SSOutpaymentRow();
        outRow.setInvoiceNr(99);
        outRow.setValue(new BigDecimal("25"));
        outRow.setCurrencyRate(BigDecimal.ONE);
        outRow.setInvoiceCurrencyRate(BigDecimal.ONE);
        out.getRows().add(outRow);
        runtime.database().addOutpayment(out);
    }

    @Test
    void customerInvoiceListPdfsMatchLegacyForFiltersEmptyAndNoYearCompanies() throws Exception {
        Path data = temporaryDirectory.resolve("customer-invoice-lists");
        int selected;
        int empty;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected invoice register").getId();
            addCustomerInvoiceListDocuments(runtime, "Selected");
            addCompany(runtime, "Other invoice register");
            addCustomerInvoiceListDocuments(runtime, "Other");
            empty = addCompany(runtime, "Empty invoice register").getId();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int company : List.of(selected, empty)) {
                for (String kind : List.of("invoice", "credit-invoice")) {
                    for (String period : kind.equals("invoice") ? List.of("all", "bounded", "outside") : List.of("all")) {
                        Path pdf = temporaryDirectory.resolve(kind + company + normalized + period + ".pdf");
                        List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(), "--company-id",
                                Integer.toString(company), "--format", "json", kind, "list", "--output", pdf.toString()));
                        if (!period.equals("all")) args.addAll(List.of("--from", period.equals("bounded")
                                ? "2024-02-10" : "2025-01-01", "--to", period.equals("bounded")
                                ? "2024-02-20" : "2025-12-31"));
                        Result result = execute(args.toArray(String[]::new));
                        assertThat(result.code()).as(result.error()).isZero();
                        var json = new ObjectMapper().readTree(result.output());
                        int count = company == empty ? 0 : kind.equals("credit-invoice") ? 1
                                : period.equals("all") ? 5 : period.equals("bounded") ? 3 : 1;
                        assertThat(json.path("count").asInt()).isEqualTo(count);
                        String text = text(pdf);
                        assertThat(text).contains(company == selected ? "Selected invoice register"
                                : "Empty invoice register").doesNotContain("Other");
                        if (count > 0) assertThat(text).contains("Selected row");
                        String key = kind + company + period;
                        if (normalized) {
                            assertThat(text).isEqualTo(expected.get(key));
                            byte[] original = Files.readAllBytes(pdf);
                            assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                            assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                            args.add("--overwrite");
                            Result overwritten = execute(args.toArray(String[]::new));
                            assertThat(overwritten.code()).as(overwritten.error()).isZero();
                            assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                            assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                        } else expected.put(key, text);
                    }
                }
            }
        }
    }

    private static void addCustomerInvoiceListDocuments(BokfriRuntime runtime, String name) {
        for (String date : List.of("2023-12-31", "2024-02-10", "2024-02-20", "2024-02-21", "undated")) {
            SSInvoice invoice = customerListSale(new SSInvoice(), name);
            invoice.setLocalDate(date.equals("undated") ? null : LocalDate.parse(date));
            runtime.database().addInvoice(invoice);
        }
        SSCreditInvoice credit = customerListSale(new SSCreditInvoice(), name);
        credit.setCreditingNr(1);
        credit.setLocalDate(LocalDate.of(2024, 2, 20));
        runtime.database().addCreditInvoice(credit);
    }

    private static <T extends SSInvoice> T customerListSale(T invoice, String name) {
        invoice.setCustomerNr("C1");
        invoice.setCustomerName(name + " customer snapshot");
        invoice.setTaxFree(true);
        invoice.setCurrencyRate(new BigDecimal("2"));
        SSSaleRow row = new SSSaleRow();
        row.setProductNr("P1");
        row.setDescription(name + " row");
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.25"));
        row.setDiscount(new BigDecimal("10"));
        invoice.getRows().add(row);
        return invoice;
    }

    @Test
    void supplierInvoiceListPdfsMatchLegacyForEmptyAndNoYearCompanies() throws Exception {
        Path data = temporaryDirectory.resolve("supplier-invoice-lists");
        int selected;
        int empty;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected invoice register").getId();
            addSupplierInvoiceListDocuments(runtime, "Selected");
            addCompany(runtime, "Other invoice register");
            addSupplierInvoiceListDocuments(runtime, "Other");
            empty = addCompany(runtime, "Empty invoice register").getId();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int company : List.of(selected, empty)) {
                for (String kind : List.of("supplier-invoice", "supplier-credit-invoice")) {
                    Path pdf = temporaryDirectory.resolve(kind + company + normalized + ".pdf");
                    List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(), "--company-id",
                            Integer.toString(company), "--format", "json", kind, "list", "--output", pdf.toString()));
                    Result result = execute(args.toArray(String[]::new));
                    assertThat(result.code()).as(result.error()).isZero();
                    var json = new ObjectMapper().readTree(result.output());
                    int count = company == empty ? 0 : kind.equals("supplier-credit-invoice") ? 1 : 5;
                    assertThat(json.path("count").asInt()).isEqualTo(count);
                    String text = text(pdf);
                    assertThat(text).contains(company == selected ? "Selected invoice register"
                            : "Empty invoice register").doesNotContain("Other");
                    if (count > 0) assertThat(text).contains("Selected row");
                    String key = kind + company;
                    if (normalized) {
                        assertThat(text).isEqualTo(expected.get(key));
                        byte[] original = Files.readAllBytes(pdf);
                        assertThat(execute(args.toArray(String[]::new)).code()).isEqualTo(1);
                        assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                        args.add("--overwrite");
                        Result overwritten = execute(args.toArray(String[]::new));
                        assertThat(overwritten.code()).as(overwritten.error()).isZero();
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    } else expected.put(key, text);
                }
            }
        }
    }

    @Test
    void creditInvoiceDocumentsMatchLegacyWithoutYearAndStayCompanyScoped() throws Exception {
        Path data = temporaryDirectory.resolve("credit-documents");
        int selected;
        int empty;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            selected = addCompany(runtime, "Selected credit company").getId();
            addCreditDocuments(runtime, "Selected");
            addCompany(runtime, "Other credit company");
            addCreditDocuments(runtime, "Other");
            empty = addCompany(runtime, "Empty credit company").getId();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int number : List.of(1, 2)) {
                String[] context = {"--data-dir", data.toString(), "--company-id",
                        Integer.toString(selected), "--format", "json", "credit-invoice"};
                Result shown = execute(concat(context, "show", Integer.toString(number)));
                assertThat(shown.code()).as(shown.error()).isZero();
                String key = "show" + number;
                if (normalized) {
                    var actual = new ObjectMapper().readTree(shown.output());
                    var legacy = new ObjectMapper().readTree(expected.get(key));
                    for (String field : List.of("number", "creditingNumber", "customerNumber", "customerName",
                            "date", "total", "rounding", "selection")) {
                        assertThat(actual.get(field)).as(field).isEqualTo(legacy.get(field));
                    }
                    assertThat(actual.path("rows").get(0).path("description").asText()).isEqualTo("Selected row");
                    assertThat(actual.path("rows").get(0).path("discount").decimalValue())
                            .isEqualByComparingTo(legacy.path("rows").get(0).path("discount").decimalValue());
                } else expected.put(key, shown.output());
                for (String language : List.of("sv-SE", "en-US")) {
                    Path pdf = temporaryDirectory.resolve("credit-" + number + language + normalized + ".pdf");
                    String[] args = concat(context, "pdf", Integer.toString(number), "--language", language,
                            "--output", pdf.toString());
                    Result result = execute(args);
                    assertThat(result.code()).as(result.error()).isZero();
                    String content = text(pdf);
                    assertThat(content).contains("Selected credit company", "Selected row",
                            "Selected invoice address").doesNotContain("Other");
                    if (number == 1) assertThat(content).contains("987654321", "VAT-SELECTED");
                    key = number + language;
                    if (normalized) {
                        assertThat(content).isEqualTo(expected.get(key));
                        byte[] original = Files.readAllBytes(pdf);
                        assertThat(execute(args).code()).isEqualTo(1);
                        assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                        Result overwritten = execute(concat(args, "--overwrite"));
                        assertThat(overwritten.code()).as(overwritten.error()).isZero();
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    } else expected.put(key, content);
                }
            }
            for (String operation : List.of("show", "pdf")) {
                Path missing = temporaryDirectory.resolve("missing-credit" + normalized + ".pdf");
                String[] args = {"--data-dir", data.toString(), "--company-id", Integer.toString(empty),
                        "--format", "json", "credit-invoice", operation, "1"};
                if (operation.equals("pdf")) args = concat(args, "--output", missing.toString());
                Result result = execute(args);
                assertThat(result.code()).isEqualTo(1);
                assertThat(result.error()).contains("CREDIT_INVOICE_NOT_FOUND");
                assertThat(missing).doesNotExist();
            }
        }
    }

    @Test
    void invoiceDocumentPdfsMatchLegacyForStandardAndOcrWithoutYear() throws Exception {
        Path data = temporaryDirectory.resolve("invoice-documents");
        int selected;
        int empty;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = addCompany(runtime, "Selected invoice company");
            selected = company.getId();
            company.setBankGiroNumber("123-4567");
            company.setPlusGiroNumber("765-4321");
            company.setBIC("TESTBIC");
            company.setIBAN("SE123456");
            company.setBank("Selected bank");
            company.setTelefax("FAX-SELECTED");
            company.setEMail("selected@example.test");
            company.setHomepage("https://example.test");
            company.setResidence("Selected residence");
            company.getAddress().setAddress2("Selected address line two");
            company.getAddress().setCountry("Selected country");
            company.setRoundingOff(false);
            runtime.database().updateCompany(company);
            addInvoiceDocuments(runtime, "Selected");
            addCompany(runtime, "Other invoice company");
            addInvoiceDocuments(runtime, "Other");
            empty = addCompany(runtime, "Empty invoice company").getId();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (int number : List.of(1, 2)) {
                for (String language : List.of("sv-SE", "en-US")) {
                    for (String variant : List.of("standard", "ocr", "background")) {
                        String key = number + language + variant;
                        Path pdf = temporaryDirectory.resolve("invoice-" + key + normalized + ".pdf");
                        String[] args = {"--data-dir", data.toString(), "--company-id",
                                Integer.toString(selected), "--format", "json", "invoice", "pdf",
                                Integer.toString(number), "--language", language, "--output", pdf.toString()};
                        if (!variant.equals("standard")) args = concat(args, "--ocr");
                        if (variant.equals("background")) args = concat(args, "--ocr-background");
                        Result result = execute(args);
                        assertThat(result.code()).as(result.error()).isZero();
                        var json = new ObjectMapper().readTree(result.output());
                        assertThat(json.path("invoiceNumber").asInt()).isEqualTo(number);
                        assertThat(json.path("language").asText()).isEqualTo(language);
                        assertThat(json.path("ocr").asBoolean()).isEqualTo(!variant.equals("standard"));
                        assertThat(json.path("bytes").asLong()).isEqualTo(Files.size(pdf));
                        if (!variant.equals("standard")) {
                            SSInvoice reference = new SSInvoice();
                            reference.setNumber(number);
                            assertThat(json.path("ocrNumber").asText())
                                    .isEqualTo(se.swedsoft.bookkeeping.calc.SSOCRNumber.getOCRNumber(reference));
                        } else assertThat(json.has("ocrNumber")).isFalse();
                        String content = text(pdf);
                        assertThat(content).contains("Selected invoice company", "Selected invoice address",
                                "123-4567", "FAX-SELECTED", "selected@example.test", "SE123456", "TESTBIC")
                                .doesNotContain("Other");
                        assertThat(content).contains(language.equals("en-US")
                                ? "Selected localized product" : "Selected row");
                        if (number == 1) assertThat(content).contains("VAT-SELECTED");
                        if (normalized) {
                            assertThat(content).isEqualTo(expected.get(key));
                            byte[] original = Files.readAllBytes(pdf);
                            Result refused = execute(args);
                            assertThat(refused.code()).isEqualTo(1);
                            assertThat(refused.error()).contains("OUTPUT_EXISTS");
                            assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                            Result overwritten = execute(concat(args, "--overwrite"));
                            assertThat(overwritten.code()).as(overwritten.error()).isZero();
                            assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                            assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                        } else expected.put(key, content);
                    }
                }
            }
            for (String variant : List.of("standard", "ocr")) {
                Path missing = temporaryDirectory.resolve("missing-invoice" + variant + normalized + ".pdf");
                String[] args = {"--data-dir", data.toString(), "--company-id", Integer.toString(empty),
                        "--format", "json", "invoice", "pdf", "1", "--output", missing.toString()};
                if (variant.equals("ocr")) args = concat(args, "--ocr");
                Result result = execute(args);
                assertThat(result.code()).isEqualTo(1);
                assertThat(result.error()).contains("INVOICE_NOT_FOUND");
                assertThat(missing).doesNotExist();
            }
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                runtime.selectCompany(selected);
                // Exporting an OCR form must not persist a new reference or alter stored rows.
                for (SSInvoice invoice : runtime.getInvoices()) {
                    assertThat(invoice.getOCRNumber()).isEqualTo("987654321");
                    assertThat(invoice.getRows().get(0).getDescription()).isEqualTo("Selected row");
                }
            }
        }
    }

    private static void addInvoiceDocuments(BokfriRuntime runtime, String name) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C1");
        customer.setName(name + " current customer");
        customer.setVATNumber("VAT-" + name.toUpperCase(java.util.Locale.ROOT));
        runtime.database().addCustomer(customer);
        SSProduct product = new SSProduct();
        product.setNumber("P1");
        product.setDescription(java.util.Locale.forLanguageTag("en-US"), name + " localized product");
        runtime.database().addProduct(product);
        for (int number : List.of(1, 2)) {
            SSInvoice invoice = customerListSale(new SSInvoice(), name);
            invoice.setLocalDate(LocalDate.of(2024, 2, 20));
            invoice.getInvoiceAddress().setName(name + " invoice address");
            invoice.setOCRNumber("987654321");
            if (number == 2) {
                invoice.setCustomerNr("missing");
                invoice.setType(SSInvoiceType.CASH);
            }
            runtime.database().addInvoice(invoice);
        }
    }

    @Test
    void reminderPdfsAndValidationMatchLegacyWithCreditsPaymentsAndCompanyIsolation() throws Exception {
        Path data = temporaryDirectory.resolve("reminder-documents");
        int selected;
        int year;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = addCompany(runtime, "Selected reminder company");
            selected = company.getId();
            company.setReminderfee(new BigDecimal("42.50"));
            company.setBankGiroNumber("123-4567");
            company.getStandardTexts().put(se.swedsoft.bookkeeping.data.SSStandardText.Reminder,
                    "Selected reminder standard text");
            runtime.database().updateCompany(company);
            year = addYear(runtime).getId();
            addReminderDocuments(runtime, "Selected");
            addCompany(runtime, "Other reminder company");
            addYear(runtime);
            addReminderDocuments(runtime, "Other");
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (boolean normalized : List.of(false, true)) {
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            String[] context = {"--data-dir", data.toString(), "--company-id", Integer.toString(selected),
                    "--year-id", Integer.toString(year), "--format", "json", "invoice", "reminder"};
            for (String language : List.of("sv-SE", "en-US")) {
                Path pdf = temporaryDirectory.resolve("reminder-" + language + normalized + ".pdf");
                String[] args = concat(context, "1", "--date", "2024-03-15", "--language", language,
                        "--output", pdf.toString());
                Result result = execute(args);
                assertThat(result.code()).as(result.error()).isZero();
                var json = new ObjectMapper().readTree(result.output());
                assertThat(json.path("balance").asText()).isEqualTo("55.00");
                assertThat(json.path("date").asText()).isEqualTo("2024-03-15");
                assertThat(json.path("dueDate").asText()).isEqualTo("2024-02-15");
                String content = text(pdf);
                assertThat(content).contains("Selected reminder company", "Selected current customer",
                        "Selected reminder standard text", "987654321", "123-4567").doesNotContain("Other");
                if (normalized) {
                    assertThat(content).isEqualTo(expected.get(language));
                    byte[] original = Files.readAllBytes(pdf);
                    assertThat(execute(args).code()).isEqualTo(1);
                    assertThat(Files.readAllBytes(pdf)).isEqualTo(original);
                    Result overwritten = execute(concat(args, "--overwrite"));
                    assertThat(overwritten.code()).as(overwritten.error()).isZero();
                    assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                    assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                } else expected.put(language, content);
            }
            for (String scenario : List.of("unentered", "settled", "not-overdue", "missing-date",
                    "missing-customer", "missing-invoice", "cash")) {
                int number = switch (scenario) {
                    case "unentered" -> 2;
                    case "settled" -> 3;
                    case "missing-date" -> 4;
                    case "missing-customer" -> 5;
                    case "missing-invoice" -> 999;
                    case "cash" -> 6;
                    default -> 1;
                };
                String code = switch (scenario) {
                    case "unentered" -> "REMINDER_INVOICE_NOT_ENTERED";
                    case "settled", "cash" -> "REMINDER_INVOICE_SETTLED";
                    case "missing-customer" -> "INVOICE_CUSTOMER_NOT_FOUND";
                    case "missing-invoice" -> "INVOICE_NOT_FOUND";
                    default -> "REMINDER_INVOICE_NOT_OVERDUE";
                };
                Path pdf = temporaryDirectory.resolve(scenario + normalized + ".pdf");
                Result result = execute(concat(context, Integer.toString(number), "--date",
                        scenario.equals("not-overdue") ? "2024-02-15" : "2024-03-15",
                        "--output", pdf.toString()));
                assertThat(result.code()).as(scenario).isEqualTo(1);
                assertThat(result.error()).contains(code);
                assertThat(pdf).doesNotExist();
            }
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                runtime.selectCompany(selected);
                SSInvoice invoice = runtime.findInvoice(1).orElseThrow();
                assertThat(invoice.getNumReminders()).isEqualTo(2);
                assertThat(invoice.getOCRNumber()).isEqualTo("987654321");
                assertThat(runtime.invoiceBalance(invoice)).isEqualByComparingTo("55");
            }
        }
    }

    private static void addReminderDocuments(BokfriRuntime runtime, String name) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C1");
        customer.setName(name + " current customer");
        customer.getInvoiceAddress().setName(name + " current customer");
        var currency = new se.swedsoft.bookkeeping.data.common.SSCurrency("XTS", "Test currency");
        if (runtime.database().getCurrencies().stream().noneMatch(value -> "XTS".equals(value.getName()))) {
            runtime.database().addCurrency(currency);
        }
        customer.setInvoiceCurrency(currency);
        runtime.database().addCustomer(customer);
        for (int number = 1; number <= 6; number++) {
            SSInvoice invoice = new SSInvoice();
            invoice.setCustomerNr(number == 5 ? "missing" : "C1");
            invoice.setCustomerName(name + " snapshot");
            invoice.setLocalDate(LocalDate.of(2024, 2, 1));
            invoice.setLocalDueDate(number == 4 ? null : LocalDate.of(2024, 2, 15));
            invoice.setTaxFree(true);
            invoice.setDelayInterest(new BigDecimal("8.5"));
            invoice.setNumRemainders(2);
            invoice.setOCRNumber("987654321");
            if (number != 2) invoice.setEntered();
            if (number == 6) invoice.setType(SSInvoiceType.CASH);
            SSSaleRow row = new SSSaleRow();
            row.setDescription(name + " row");
            row.setQuantity(1);
            row.setUnitprice(new BigDecimal("100"));
            invoice.getRows().add(row);
            runtime.database().addInvoice(invoice);
        }
        SSCreditInvoice credit = new SSCreditInvoice();
        credit.setCreditingNr(1);
        credit.setCustomerNr("C1");
        credit.setTaxFree(true);
        credit.setLocalDate(LocalDate.of(2024, 2, 20));
        SSSaleRow creditRow = new SSSaleRow();
        creditRow.setQuantity(1);
        creditRow.setUnitprice(new BigDecimal("20"));
        credit.getRows().add(creditRow);
        runtime.database().addCreditInvoice(credit);
        for (int number : List.of(1, 3)) {
            SSInpayment payment = new SSInpayment();
            payment.setLocalDate(LocalDate.of(2024, 2, 21));
            SSInpaymentRow row = new SSInpaymentRow();
            row.setInvoiceNr(number);
            row.setValue(new BigDecimal(number == 1 ? "25" : "100"));
            payment.getRows().add(row);
            runtime.database().addInpayment(payment);
        }
    }

    private static String[] concat(String[] prefix, String... suffix) {
        List<String> args = new ArrayList<>(List.of(prefix));
        args.addAll(List.of(suffix));
        return args.toArray(String[]::new);
    }

    private static void addCreditDocuments(BokfriRuntime runtime, String name) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C1");
        customer.setName(name + " current customer");
        customer.setVATNumber("VAT-" + name.toUpperCase(java.util.Locale.ROOT));
        runtime.database().addCustomer(customer);
        SSInvoice invoice = customerListSale(new SSInvoice(), name);
        invoice.setOCRNumber("987654321");
        runtime.database().addInvoice(invoice);
        for (int number : List.of(1, 2)) {
            SSCreditInvoice credit = customerListSale(new SSCreditInvoice(), name);
            credit.setLocalDate(LocalDate.of(2024, 2, 20));
            credit.getInvoiceAddress().setName(name + " invoice address");
            credit.setCreditingNr(number == 1 ? invoice.getNumber() : 999);
            if (number == 2) credit.setCustomerNr("missing");
            runtime.database().addCreditInvoice(credit);
        }
    }

    private static void addSupplierInvoiceListDocuments(BokfriRuntime runtime, String name) {
        for (String date : List.of("2023-12-31", "2024-02-10", "2024-02-20", "2024-02-21", "undated")) {
            SSSupplierInvoice invoice = supplierListSale(new SSSupplierInvoice(), name);
            invoice.setLocalDate(date.equals("undated") ? null : LocalDate.parse(date));
            runtime.database().addSupplierInvoice(invoice);
        }
        SSSupplierCreditInvoice credit = supplierListSale(new SSSupplierCreditInvoice(), name);
        credit.setCreditingNr(1);
        credit.setLocalDate(LocalDate.of(2024, 2, 20));
        runtime.database().addSupplierCreditInvoice(credit);
    }

    private static <T extends SSSupplierInvoice> T supplierListSale(T invoice, String name) {
        invoice.setSupplierNr("S1");
        invoice.setSupplierName(name + " supplier snapshot");
        invoice.setTaxSum(new BigDecimal("25.03"));
        invoice.setRoundingSum(new BigDecimal("-0.155"));
        invoice.setCurrencyRate(new BigDecimal("2"));
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setProductNr("P1");
        row.setDescription(name + " row");
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.25"));
        row.setAccountNr(4010);
        invoice.getRows().add(row);
        return invoice;
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
