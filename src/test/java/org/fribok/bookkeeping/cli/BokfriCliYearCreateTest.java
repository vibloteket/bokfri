package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BokfriCliYearCreateTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsIndependentYearsByIdAndNameWithLegacyParityAndGlobalYearIds() throws Exception {
        List<Map<String, Object>> expected = null;
        for (boolean normalized : List.of(false, true)) {
            Path data = temporaryDirectory.resolve("parity-" + normalized);
            Fixture fixture = fixture(data);
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            List<Map<String, Object>> templatesBefore;
            Map<String, Object> existingBefore;
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                templatesBefore = runtime.getAccountPlans().stream().map(this::planSnapshot).toList();
                SSNewCompany company = runtime.selectCompany(fixture.company());
                existingBefore = yearSnapshot(runtime, runtime.selectYear(company, fixture.existingYear()));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> input : List.of(
                    Map.<String, Object>of("from", "2025-01-01", "to", "2025-12-31", "accountPlanId", fixture.plan()),
                    Map.<String, Object>of("from", "2026-04-01", "to", "2027-03-31",
                            "accountPlanName", "Year template"),
                    Map.<String, Object>of("from", "2028-02-29", "to", "2028-02-29", "accountPlanId", fixture.plan()),
                    Map.<String, Object>of("from", "2028-03-01", "to", "2028-03-01",
                            "accountPlanName", "Empty template"))) {
                Result result = create(data, fixture.company(), input);
                assertThat(result.code()).as(result.error()).isZero();
                var json = mapper.readTree(result.output());
                int id = json.path("id").asInt();
                assertThat(id).isGreaterThan(fixture.otherYear());
                assertThat(json.path("companyId").asInt()).isEqualTo(fixture.company());
                assertThat(json.path("from").asText()).isEqualTo(input.get("from"));
                assertThat(json.path("to").asText()).isEqualTo(input.get("to"));
                try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                    SSNewCompany company = runtime.selectCompany(fixture.company());
                    SSNewAccountingYear year = runtime.hydrateYear(runtime.selectYear(company, id));
                    assertThat(year.getAccountPlan().getId()).isNull();
                    assertThat(year.getAccountPlan().getBaseName()).isEqualTo(year.getAccountPlan().getName());
                    assertThat(year.getInBalance()).isEmpty();
                    assertThat(year.getVouchers()).isEmpty();
                    assertThat(year.getBudget().getBudget().values()).allMatch(Map::isEmpty);
                    results.add(yearSnapshot(runtime, year));
                }
            }
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                assertThat(runtime.getAccountPlans().stream().map(this::planSnapshot).toList())
                        .isEqualTo(templatesBefore);
                SSNewCompany company = runtime.selectCompany(fixture.company());
                assertThat(yearSnapshot(runtime, runtime.selectYear(company, fixture.existingYear())))
                        .isEqualTo(existingBefore);
                assertThat(runtime.getYears(company)).hasSize(5);
                SSNewCompany other = runtime.selectCompany(fixture.otherCompany());
                assertThat(runtime.getYears(other)).hasSize(1);
                assertThat(runtime.getYears(other).get(0).getId()).isEqualTo(fixture.otherYear());
            }
            if (normalized) {
                assertThat(results).isEqualTo(expected);
                assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                assertThat(SSDB.getInstance().getCurrentYear()).isNull();
            } else expected = results;
        }
    }

    @Test
    void invalidDatesOverlapsAndMissingOrAmbiguousTemplatesDoNotCreateYears() throws Exception {
        for (boolean normalized : List.of(false, true)) {
            Path data = temporaryDirectory.resolve("invalid-" + normalized);
            Fixture fixture = fixture(data);
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                runtime.database().addAccountPlan(new SSAccountPlan("Ambiguous"));
                runtime.database().addAccountPlan(new SSAccountPlan("Ambiguous"));
            }
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            for (String[] dates : List.of(new String[]{"2025-12-31", "2025-01-01"},
                    new String[]{"2024-12-31", "2025-01-01"}, new String[]{"2023-12-31", "2024-01-01"},
                    new String[]{"2024-06-01", "2024-06-01"}, new String[]{"2023-01-01", "2026-12-31"},
                    new String[]{"2024-01-01", "2024-12-31"})) {
                Result result = create(data, fixture.company(), Map.of("from", dates[0], "to", dates[1],
                        "accountPlanId", fixture.plan()));
                assertThat(result.code()).isEqualTo(1);
                assertThat(result.error()).contains(dates[0].equals("2025-12-31")
                        ? "Accounting year dates are invalid" : "Accounting year overlaps an existing year");
            }
            for (Map<String, Object> selector : List.of(Map.<String, Object>of("accountPlanId", 999999),
                    Map.<String, Object>of("accountPlanName", "missing"),
                    Map.<String, Object>of("accountPlanName", "Ambiguous"),
                    Map.<String, Object>of("accountPlanId", fixture.plan(), "accountPlanName", "Empty template"),
                    Map.<String, Object>of())) {
                Map<String, Object> input = new LinkedHashMap<>(selector);
                input.put("from", "2025-01-01");
                input.put("to", "2025-12-31");
                Result result = create(data, fixture.company(), input);
                assertThat(result.code()).isEqualTo(1);
                assertThat(result.error()).contains("ACCOUNT_PLAN_NOT_FOUND");
            }
            Result missingDate = create(data, fixture.company(), Map.of("to", "2025-12-31",
                    "accountPlanId", fixture.plan()));
            assertThat(missingDate.code()).isEqualTo(1);
            Result unknownCompany = create(data, 999999, Map.of("from", "2025-01-01", "to", "2025-12-31",
                    "accountPlanId", fixture.plan()));
            assertThat(unknownCompany.code()).isEqualTo(1);
            assertThat(unknownCompany.error()).contains("COMPANY_NOT_FOUND");
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                SSNewCompany company = runtime.selectCompany(fixture.company());
                assertThat(runtime.getYears(company)).hasSize(1);
            }
        }
    }

    @Test
    void failedAccountInsertRollsBackYearPlanAndAccountsAndKeepsSelectionForRetry() throws Exception {
        Path data = temporaryDirectory.resolve("rollback");
        Fixture fixture = fixture(data);
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = runtime.selectCompany(fixture.company());
            SSNewAccountingYear selected = runtime.selectYear(company, fixture.existingYear());
            SSAccountPlan template = runtime.getAccountPlans().stream()
                    .filter(plan -> plan.getId() == fixture.plan()).findFirst().orElseThrow();
            Map<String, Object> before = planSnapshot(template);
            SSAccountPlan invalid = new SSAccountPlan(template);
            invalid.getAccounts().get(1).setDescription("x".repeat(2048));
            assertThatThrownBy(() -> runtime.createAccountingYear(LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 12, 31), invalid)).isInstanceOf(java.sql.SQLException.class);
            assertThat(runtime.normalizedStore().getCurrentYear().getId()).isEqualTo(selected.getId());
            assertThat(runtime.currentCompany().getId()).isEqualTo(company.getId());
            assertThat(runtime.normalizedStore().getYears()).hasSize(1);
            assertThat(planSnapshot(template)).isEqualTo(before);
            SSNewAccountingYear year = runtime.createAccountingYear(LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 12, 31), template);
            assertThat(year.getId()).isEqualTo(fixture.otherYear() + 1);
            assertThat(year.getVouchers()).isEmpty();
            assertThat(runtime.normalizedStore().getCurrentYear().getId()).isEqualTo(selected.getId());
            assertThat(planSnapshot(runtime.getAccountPlans().stream()
                    .filter(plan -> plan.getId() == fixture.plan()).findFirst().orElseThrow())).isEqualTo(before);
            year.getAccountPlan().getAccounts().get(0).setDescription("Detached change");
            assertThat(template.getAccounts().get(0).getDescription()).isEqualTo("Bank");
        }
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = runtime.selectCompany(fixture.company());
            assertThat(runtime.getYears(company)).hasSize(2);
            SSNewAccountingYear year = runtime.hydrateYear(runtime.selectYear(company, fixture.otherYear() + 1));
            assertThat(year.getAccounts()).hasSize(2);
            assertThat(year.getAccounts().get(0).getDescription()).isEqualTo("Bank");
        }
    }

    @Test
    void oldV18NormalizedDatabaseRejectsCreationWithoutAReplacementCatalogueOrSchemaChange() throws Exception {
        Path data = temporaryDirectory.resolve("v18");
        Files.createDirectories(data.resolve("db"));
        String url = "jdbc:hsqldb:file:" + data.resolve("db/JFSDB");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection,
                    NormalizedSchemaMigrations.load().subList(0, 18));
            SSNewCompany company = new SSNewCompany();
            company.setId(5);
            company.setName("Older company");
            new NormalizedAccountingStore(connection, Clock.systemUTC()).addCompany(company);
            DataFormatManager.recordVersion(connection, DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION);
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        Result result = create(data, 5, Map.of("from", "2025-01-01", "to", "2025-12-31", "accountPlanId", 0));
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.error()).contains("NORMALIZED_ACCOUNT_PLAN_CATALOG_REQUIRED");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var statement = connection.createStatement();
                 var count = statement.executeQuery("SELECT COUNT(*) FROM accounting_year")) {
                count.next();
                assertThat(count.getInt(1)).isZero();
            }
            try (var statement = connection.createStatement();
                 var history = statement.executeQuery("SELECT MAX(version) FROM bokfri_schema_history")) {
                history.next();
                assertThat(history.getInt(1)).isEqualTo(18);
            }
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    private Fixture fixture(Path data) throws Exception {
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSAccountPlan plan = new SSAccountPlan("Year template");
            plan.setBaseName("Original base");
            plan.setAssessementYear("2025");
            SSAccount bank = new SSAccount(1930);
            bank.setDescription("Bank");
            bank.setVATCode("VAT");
            bank.setSRUCode("SRU");
            bank.setReportCode("R");
            bank.setActive(false);
            bank.setProjectRequired(true);
            bank.setResultUnitRequired(true);
            plan.getAccounts().add(bank);
            plan.getAccounts().add(new SSAccount(3010));
            runtime.database().addAccountPlan(plan);
            runtime.database().addAccountPlan(new SSAccountPlan("Empty template"));
            SSNewCompany company = new SSNewCompany();
            company.setName("Selected company");
            runtime.database().addCompany(company);
            runtime.selectCompany(company.getId());
            SSNewAccountingYear year = seedYear(plan, 2024);
            runtime.database().addAccountingYear(year);
            SSNewCompany other = new SSNewCompany();
            other.setName("Other company");
            runtime.database().addCompany(other);
            runtime.selectCompany(other.getId());
            SSNewAccountingYear otherYear = seedYear(plan, 2025);
            runtime.database().addAccountingYear(otherYear);
            return new Fixture(company.getId(), year.getId(), other.getId(), otherYear.getId(), plan.getId());
        }
    }

    private static SSNewAccountingYear seedYear(SSAccountPlan plan, int calendarYear) {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(calendarYear, 1, 1));
        year.setLocalTo(LocalDate.of(calendarYear, 12, 31));
        year.setAccountPlan(new SSAccountPlan(plan, true));
        return year;
    }

    private Map<String, Object> yearSnapshot(BokfriRuntime runtime, SSNewAccountingYear year) throws Exception {
        runtime.hydrateYear(year);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", year.getId());
        result.put("from", year.getLocalFrom().toString());
        result.put("to", year.getLocalTo().toString());
        result.put("plan", planSnapshot(year.getAccountPlan()));
        result.put("balances", year.getInBalance().size());
        result.put("vouchers", year.getVouchers().size());
        return result;
    }

    private Map<String, Object> planSnapshot(SSAccountPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.getId());
        result.put("name", plan.getName());
        result.put("base", plan.getBaseName());
        result.put("assessment", plan.getAssessementYear());
        result.put("type", plan.getType().getName());
        result.put("accounts", plan.getAccounts().stream().map(account -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", account.getNumber());
            row.put("description", account.getDescription());
            row.put("vat", account.getVATCode());
            row.put("sru", account.getSRUCode());
            row.put("report", account.getReportCode());
            row.put("active", account.isActive());
            row.put("project", account.isProjectRequired());
            row.put("unit", account.isResultUnitRequired());
            return row;
        }).toList());
        return result;
    }

    private Result create(Path data, int company, Map<String, Object> input) throws Exception {
        Path file = temporaryDirectory.resolve("year.json");
        mapper.writeValue(file.toFile(), input);
        Path selection = temporaryDirectory.resolve("selection.config");
        if (!Files.exists(selection)) {
            SSDBConfig.setCompanyId(selection.toFile(), 777);
            SSDBConfig.setYearId(selection.toFile(), 777, 999);
        }
        byte[] before = Files.readAllBytes(selection);
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int code = BokfriCli.execute(new String[]{"--data-dir", data.toString(), "--config", selection.toString(),
                "--company-id", Integer.toString(company), "--format", "json", "year", "create",
                "--file", file.toString()},
                new PrintWriter(output, true), new PrintWriter(error, true));
        assertThat(Files.readAllBytes(selection)).isEqualTo(before);
        return new Result(code, output.toString(), error.toString());
    }

    private record Fixture(int company, int existingYear, int otherCompany, int otherYear, int plan) { }
    private record Result(int code, String output, String error) { }
}
