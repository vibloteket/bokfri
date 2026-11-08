package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.cli.BokfriCli;
import org.fribok.bookkeeping.cli.BokfriRuntime;
import org.fribok.bookkeeping.service.demo.DemoCompanyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedDemoRecreateTest {
    @TempDir Path temporary;

    @Test
    void reproducesLegacyDemoAccountingRegistersSnapshotsAndCountersAndCanRepeat() throws Exception {
        Map<String, Object> expected = null;
        for (boolean normalized : List.of(false, true)) {
            Path data = fixture(normalized);
            for (int attempt = 0; attempt < 2; attempt++) {
                try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                    var result = runtime.recreateDemo();
                    assertThat(result.removedCompanies()).isEqualTo(1);
                    assertThat(result.vouchers()).isEqualTo(10);
                    assertThat(runtime.getCompanies()).extracting(SSNewCompany::getName)
                            .containsExactlyInAnyOrder("Unrelated company", DemoCompanyService.NAME);
                    Map<String, Object> actual = snapshot(runtime, result.company());
                    if (expected == null) expected = actual;
                    else {
                        for (String field : expected.keySet()) {
                            assertThat(actual.get(field)).as(field + " normalized=" + normalized + " attempt=" + attempt)
                                    .isEqualTo(expected.get(field));
                        }
                    }
                    if (normalized) {
                        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
                    }
                }
            }
        }
    }

    @Test
    void cliPreviewIsReadOnlyAndCommitPreservesOrdinarySelectionAndCatalogue() throws Exception {
        Path data = fixture(true);
        Path config = temporary.resolve("ordinary.config");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            var ordinary = runtime.getCompanies().stream().filter(c -> c.getName().equals("Unrelated company"))
                    .findFirst().orElseThrow();
            SSDBConfig.setCompanyId(config.toFile(), ordinary.getId());
        }
        byte[] selected = Files.readAllBytes(config);
        Map<String, String> before = fingerprint(data);
        Result preview = cli(data, config, false, null);
        assertThat(preview.code()).as(preview.error()).isZero();
        assertThat(preview.output()).contains("\"commit\":false");
        assertThat(fingerprint(data)).isEqualTo(before);
        Result applied = cli(data, config, true, null);
        assertThat(applied.code()).as(applied.error()).isZero();
        assertThat(applied.output()).contains("\"selectionUpdated\":false", "\"vouchers\":10");
        assertThat(Files.readAllBytes(config)).isEqualTo(selected);
        var after = fingerprint(data);
        assertThat(after.get("BOKFRI_SCHEMA_HISTORY")).isEqualTo(before.get("BOKFRI_SCHEMA_HISTORY"));
        try (Connection connection = open(data)) {
            assertThat(new NormalizedAccountPlanCatalogStore(connection).getAccountPlans()).hasSize(6);
        }
    }

    @Test
    void cliMovesSavedDemoSelectionToReplacementButExplicitOverrideDoesNotWriteConfiguration() throws Exception {
        Path data = fixture(true);
        Path config = temporary.resolve("demo.config");
        int demoId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            demoId = runtime.findDemoCompanies().get(0).getId();
        }
        SSDBConfig.setCompanyId(config.toFile(), demoId);
        Result applied = cli(data, config, true, null);
        assertThat(applied.code()).as(applied.error()).isZero();
        assertThat(applied.output()).contains("\"selectionUpdated\":true");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            var demo = runtime.findDemoCompanies().get(0);
            assertThat(SSDBConfig.getCompanyId(config.toFile())).isEqualTo(demo.getId());
            assertThat(SSDBConfig.getYearId(config.toFile(), demo.getId())).isNotNull();
            demoId = demo.getId();
        }
        byte[] selection = Files.readAllBytes(config);
        Result overridden = cli(data, config, true, demoId);
        assertThat(overridden.code()).as(overridden.error()).isZero();
        assertThat(overridden.output()).contains("\"selectionUpdated\":false");
        assertThat(Files.readAllBytes(config)).isEqualTo(selection);
    }

    @Test
    void lateSqlFailureRestoresRemovedDemoAndAllNewRowsAndAllowsSameRuntimeRetry() throws Exception {
        Path data = fixture(true);
        try (Connection connection = open(data); var statement = connection.createStatement()) {
            long cutoff;
            try (var rows = statement.executeQuery("SELECT MAX(id) FROM company")) {
                rows.next();
                cutoff = rows.getLong(1);
            }
            statement.executeUpdate("ALTER TABLE inpayment ADD CONSTRAINT fail_demo CHECK(company_id<=" + cutoff + ")");
        }
        var before = fingerprint(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            var selected = runtime.findDemoCompanies().get(0);
            runtime.selectCompany(selected.getId());
            var selectedYear = runtime.getYears(selected).get(0);
            runtime.selectYear(selected, selectedYear.getId());
            assertThatThrownBy(runtime::recreateDemo).isInstanceOf(java.sql.SQLException.class);
            assertThat(runtime.currentCompany().getId()).isEqualTo(selected.getId());
            assertThat(runtime.normalizedStore().getCurrentYear().getId()).isEqualTo(selectedYear.getId());
            try (Connection connection = open(data)) {
                assertThat(NormalizedCatalogRecoveryService.fingerprintExistingTables(connection)).isEqualTo(before);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE inpayment DROP CONSTRAINT fail_demo");
                }
            }
            var recreated = runtime.recreateDemo();
            assertThat(recreated.removedCompanies()).isEqualTo(1);
            assertThat(runtime.currentCompany().getId()).isEqualTo(recreated.company().getId());
            assertThat(runtime.normalizedStore().getCurrentYear().getLocalTo())
                    .isEqualTo(java.time.LocalDate.of(2027, 6, 30));
        }
    }

    @Test
    void refusesSameNameWrongIdentityWithoutDeletingAnything() throws Exception {
        Path data = fixture(true);
        try (Connection connection = open(data); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE company SET corporate_id='not-the-demo' WHERE name='Bokfri Demo AB'");
        }
        var before = fingerprint(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(runtime.findDemoCompanies()).isEmpty();
            assertThatThrownBy(runtime::recreateDemo).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Company name already exists");
        }
        assertThat(fingerprint(data)).isEqualTo(before);
    }

    @Test
    void v18PreviewStillWorksButCommitIsGuardedWithoutSchemaOrDataChanges() throws Exception {
        Path data = fixture(true);
        try (Connection connection = open(data); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE account_plan_catalog_account");
            statement.executeUpdate("DROP TABLE account_plan_catalog");
            statement.executeUpdate("DELETE FROM bokfri_schema_history WHERE version=19");
        }
        var before = fingerprint(data);
        Path config = temporary.resolve("v18.config");
        assertThat(cli(data, config, false, null).code()).isZero();
        Result result = cli(data, config, true, null);
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.error()).contains("NORMALIZED_ACCOUNT_PLAN_CATALOG_REQUIRED");
        assertThat(fingerprint(data)).isEqualTo(before);
    }

    @Test
    void emptyAndDemoOnlyDatabasesNeedNoTemporaryFallbackCompany() throws Exception {
        Path data = fixture(true);
        try (Connection connection = open(data)) {
            connection.setAutoCommit(false);
            var store = new NormalizedAccountingStore(connection, Clock.systemUTC());
            for (var company : store.getCompanies()) store.deleteCompany(company);
            connection.commit();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                var result = runtime.recreateDemo();
                assertThat(result.removedCompanies()).isEqualTo(attempt);
                assertThat(runtime.getCompanies()).hasSize(1);
                assertThat(runtime.getYears(result.company())).hasSize(2);
            }
        }
    }

    @Test
    void removesBothRecognizedIdentitiesButPreservesCorporateIdOnlyLookalikeAndGlobalCatalogue() throws Exception {
        Path data = fixture(true);
        SemanticFingerprint catalogue;
        try (Connection connection = open(data)) {
            catalogue = new AccountPlanCatalogStagingConverter().fingerprintNormalized(connection);
        }
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany legacy = new SSNewCompany();
            legacy.setName(DemoCompanyService.LEGACY_NAME);
            legacy.setCorporateID(DemoCompanyService.LEGACY_CORPORATE_ID);
            runtime.createCompany(legacy);
            SSNewCompany lookalike = new SSNewCompany();
            lookalike.setName("Only the corporate ID matches");
            lookalike.setCorporateID(DemoCompanyService.CORPORATE_ID);
            runtime.createCompany(lookalike);
            assertThat(runtime.recreateDemo().removedCompanies()).isEqualTo(2);
            assertThat(runtime.getCompanies()).extracting(SSNewCompany::getName)
                    .containsExactlyInAnyOrder("Unrelated company", "Only the corporate ID matches", DemoCompanyService.NAME);
        }
        try (Connection connection = open(data)) {
            assertThat(new AccountPlanCatalogStagingConverter().fingerprintNormalized(connection)).isEqualTo(catalogue);
        }
    }

    @Test
    void missingPostingAccountIsRejectedBeforeRemovingAnyDemo() throws Exception {
        Path data = fixture(true);
        try (Connection connection = open(data); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM account_plan_catalog_account WHERE number=1510 "
                    + "AND plan_legacy_id=(SELECT legacy_id FROM account_plan_catalog WHERE name='BAS 2026 - Aktiebolag')");
        }
        var before = fingerprint(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThatThrownBy(runtime::recreateDemo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Demo account is unavailable: 1510");
        }
        assertThat(fingerprint(data)).isEqualTo(before);
    }

    private Path fixture(boolean normalized) throws Exception {
        Path data = temporary.resolve("data-" + normalized);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany ordinary = new SSNewCompany();
            ordinary.setName("Unrelated company");
            ordinary.setCorporateID("556000-1111");
            runtime.database().addCompany(ordinary);
        }
        if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        return data;
    }

    private Map<String, Object> snapshot(BokfriRuntime runtime, SSNewCompany company) throws Exception {
        runtime.selectCompany(company.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", List.of(company.getName(), company.getCorporateID(), company.getVATNumber(),
                company.getCurrency().getName(), company.getPaymentTerm().getName(),
                company.getStandardUnit().getName(), company.getContactPerson(), company.getDefaultAccounts()));
        List<Object> years = new ArrayList<>();
        for (var year : runtime.getYears(company).stream().sorted(Comparator.comparing(y -> y.getLocalFrom())).toList()) {
            runtime.selectYear(company, year.getId());
            runtime.hydrateYear(year);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("dates", List.of(year.getLocalFrom(), year.getLocalTo()));
            values.put("accounts", year.getAccountPlan().getAccounts().stream()
                    .map(a -> a.getNumber() + ":" + a.getDescription() + ":" + a.isActive()).sorted().toList());
            values.put("opening", year.getInBalance().entrySet().stream()
                    .map(e -> e.getKey().getNumber() + ":" + decimal(e.getValue())).sorted().toList());
            values.put("vouchers", runtime.getVouchers().stream().sorted(Comparator.comparing(SSVoucher::getNumber))
                    .map(this::voucher).toList());
            years.add(values);
        }
        out.put("years", years);
        out.put("customers", runtime.getCustomers().stream().map(c -> c.getNumber() + ":" + c.getName()
                + ":" + c.getOurContactPerson() + ":" + c.getStoredInvoiceCurrency().getName()).sorted().toList());
        out.put("suppliers", runtime.getSuppliers().stream().map(s -> s.getNumber() + ":" + s.getName()
                + ":" + s.getOurContact() + ":" + s.getStoredCurrency().getName()).sorted().toList());
        out.put("products", runtime.getProducts().stream().map(p -> p.getNumber() + ":" + p.getDescription()
                + ":" + decimal(p.getSellingPrice()) + ":" + p.getUnit().getName()
                + ":" + new java.util.TreeMap<>(p.getStoredDefaultAccounts())).sorted().toList());
        List<Object> invoices = new ArrayList<>();
        for (var invoice : runtime.getInvoices().stream().sorted(Comparator.comparing(i -> i.getNumber())).toList()) {
            invoices.add(List.of(invoice.getNumber(), invoice.getCustomerNr(), invoice.isEntered(),
                    decimal(runtime.invoiceBalance(invoice)), voucher(invoice.getStoredVoucher()),
                    invoice.getRows().stream().map(row -> row.getProductNr() + ":" + row.getDescription() + ":"
                            + decimal(row.getQuantity()) + ":" + decimal(row.getUnitprice()) + ":"
                            + row.getAccountNr() + ":" + row.getUnit().getName()).toList()));
        }
        out.put("invoices", invoices);
        out.put("inpayments", runtime.getInpayments().stream().map(p -> List.of(p.getNumber(), p.getLocalDate(),
                p.isEntered(), p.getDefaultAccounts(), voucher(p.getVoucher()), voucher(p.getStoredDifference()))).toList());
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (String counter : List.of("invoice", "inpayment", "invoicejournal", "inpaymentjournal")) {
            counters.put(counter, runtime.counterValue(counter));
        }
        out.put("counters", counters);
        return out;
    }

    private Object voucher(SSVoucher voucher) {
        if (voucher == null) return "null";
        return java.util.Arrays.asList(voucher.getNumber(), voucher.getLocalDate(), voucher.getDescription(),
                voucher.getRows().stream().map(row -> row.getAccountNr() + ":" + decimal(row.getDebet())
                        + ":" + decimal(row.getCredit()) + ":" + row.isCrossed()).toList());
    }

    private String decimal(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private Map<String, String> fingerprint(Path data) throws Exception {
        try (Connection connection = open(data)) {
            return NormalizedCatalogRecoveryService.fingerprintExistingTables(connection);
        }
    }

    private Connection open(Path data) throws Exception {
        return DriverManager.getConnection("jdbc:hsqldb:file:" + data.resolve("db/JFSDB")
                + ";ifexists=true;shutdown=true", "sa", "");
    }

    private Result cli(Path data, Path config, boolean commit, Integer company) {
        var arguments = new ArrayList<>(List.of("--data-dir", data.toString(), "--config", config.toString(),
                "--format", "json", "demo", "recreate"));
        if (commit) arguments.add("--commit");
        if (company != null) {
            arguments.add("--company-id");
            arguments.add(company.toString());
        }
        StringWriter out = new StringWriter();
        StringWriter error = new StringWriter();
        int code = BokfriCli.execute(arguments.toArray(String[]::new), new PrintWriter(out), new PrintWriter(error));
        return new Result(code, out.toString(), error.toString());
    }

    private record Result(int code, String output, String error) {}
}
