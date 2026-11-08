package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.service.spreadsheet.AccountPlanSpreadsheetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BokfriCliAccountPlanCatalogTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listExportAndImportMatchLegacyWithoutACompanyOrYearSelection() throws Exception {
        Path data = temporaryDirectory.resolve("catalog");
        int id;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSAccountPlan source = plan("Custom template");
            runtime.database().addAccountPlan(source);
            id = source.getId();
            runtime.database().addAccountPlan(new SSAccountPlan("Empty template"));
            runtime.database().addAccountPlan(new SSAccountPlan("Custom template"));
            runtime.database().addAccountPlan(new SSAccountPlan());
        }
        Result legacyList = cli(data, "--format", "json", "account-plan", "list");
        assertThat(legacyList.code()).as(legacyList.error()).isZero();
        Result legacyText = cli(data, "account-plan", "list");
        Path legacyExport = temporaryDirectory.resolve("legacy.xlsx");
        Result legacy = cli(data, "--format", "json", "account-plan", "export", "--id", Integer.toString(id),
                "--output", legacyExport.toString());
        assertThat(legacy.code()).as(legacy.error()).isZero();
        var spreadsheets = new AccountPlanSpreadsheetService();
        SSAccountPlan imported = plan("Imported template");
        Path workbook = temporaryDirectory.resolve("import.xlsx");
        spreadsheets.write(imported, workbook, false);
        String[] preview = {"--format", "json", "account-plan", "import", "--file", workbook.toString()};
        Result legacyPreview = cli(data, preview);
        assertThat(legacyPreview.code()).as(legacyPreview.error()).isZero();
        List<Map<String, Object>> before;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) { before = snapshots(runtime.getAccountPlans()); }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        var accountingBefore = coreFingerprint(data);
        Result listed = cli(data, "--format", "json", "account-plan", "list");
        assertThat(listed.code()).as(listed.error()).isZero();
        assertThat(mapper.readTree(listed.output())).isEqualTo(mapper.readTree(legacyList.output()));
        assertThat(cli(data, "account-plan", "list").output()).isEqualTo(legacyText.output());
        Path normalizedExport = temporaryDirectory.resolve("normalized.xlsx");
        String[] export = {"--format", "json", "account-plan", "export", "--id", Integer.toString(id),
                "--output", normalizedExport.toString()};
        Result exported = cli(data, export);
        assertThat(exported.code()).as(exported.error()).isZero();
        assertThat(snapshot(spreadsheets.read(normalizedExport))).isEqualTo(snapshot(spreadsheets.read(legacyExport)));
        byte[] original = Files.readAllBytes(normalizedExport);
        assertThat(cli(data, export).error()).contains("OUTPUT_EXISTS");
        assertThat(Files.readAllBytes(normalizedExport)).isEqualTo(original);
        assertThat(cli(data, concat(export, "--overwrite")).code()).isZero();
        Path absent = temporaryDirectory.resolve("absent.xlsx");
        Result missing = cli(data, "--format", "json", "account-plan", "export", "--id", "999999",
                "--output", absent.toString());
        assertThat(missing.code()).isEqualTo(1);
        assertThat(missing.error()).contains("ACCOUNT_PLAN_NOT_FOUND");
        assertThat(absent).doesNotExist();
        Result planned = cli(data, preview);
        assertThat(planned.code()).as(planned.error()).isZero();
        assertThat(mapper.readTree(planned.output())).isEqualTo(mapper.readTree(legacyPreview.output()));
        assertThat(mapper.readTree(planned.output()).path("applied").asBoolean()).isFalse();
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(snapshots(runtime.getAccountPlans())).isEqualTo(before);
        }
        Result applied = cli(data, concat(preview, "--apply"));
        assertThat(applied.code()).as(applied.error()).isZero();
        int importedId = mapper.readTree(applied.output()).path("id").asInt();
        assertThat(importedId).isGreaterThan(id);
        JsonNode duplicate = mapper.readTree(cli(data, preview).output());
        assertThat(duplicate.path("duplicate").asBoolean()).isTrue();
        Result refused = cli(data, concat(preview, "--apply"));
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.error()).contains("ACCOUNT_PLAN_EXISTS");
        Path malformed = temporaryDirectory.resolve("malformed.xlsx");
        Files.writeString(malformed, "not an XLSX workbook");
        Result invalid = cli(data, "--format", "json", "account-plan", "import", "--file",
                malformed.toString(), "--apply");
        assertThat(invalid.code()).isEqualTo(1);
        assertThat(invalid.error()).contains("ACCOUNT_PLAN_IMPORT_FAILED");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            List<SSAccountPlan> plans = runtime.getAccountPlans();
            assertThat(plans).hasSize(before.size() + 1);
            assertThat(snapshots(plans.stream().filter(value -> value.getId() != importedId).toList()))
                    .isEqualTo(before);
            SSAccountPlan stored = plans.stream().filter(value -> value.getId() == importedId)
                    .findFirst().orElseThrow();
            Map<String, Object> expected = snapshot(spreadsheets.read(workbook));
            expected.put("id", importedId);
            assertThat(snapshot(stored)).isEqualTo(expected);
            plans.get(0).getAccounts().clear();
            assertThat(snapshots(runtime.getAccountPlans())).hasSize(before.size() + 1);
            assertThat(snapshots(runtime.getAccountPlans()).get(0)).isEqualTo(before.get(0));
        }
        assertThat(coreFingerprint(data)).isEqualTo(accountingBefore);
        assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
        assertThat(SSDB.getInstance().getCurrentYear()).isNull();
    }

    @Test
    void failedAccountWriteRollsBackTemplateAndRowsAndAllowsRetry() throws Exception {
        Path data = temporaryDirectory.resolve("rollback");
        try (BokfriRuntime ignored = BokfriRuntime.open(data)) { }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            List<Map<String, Object>> before = snapshots(runtime.getAccountPlans());
            SSAccountPlan plan = plan("Retry template");
            plan.setId(88);
            plan.getAccounts().add(new SSAccount(3010));
            assertThatThrownBy(() -> runtime.addAccountPlan(plan)).isInstanceOf(java.sql.SQLException.class);
            assertThat(plan.getId()).isEqualTo(88);
            assertThat(snapshots(runtime.getAccountPlans())).isEqualTo(before);
            plan.getAccounts().remove(plan.getAccounts().size() - 1);
            runtime.addAccountPlan(plan);
            assertThat(runtime.getAccountPlans()).hasSize(before.size() + 1);
            assertThat(snapshot(runtime.getAccountPlans().get(before.size()))).isEqualTo(snapshot(plan));
        }
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(runtime.getAccountPlans()).anyMatch(plan -> "Retry template".equals(plan.getName()));
        }
    }

    @Test
    void olderNormalizedCatalogFailsClearlyWithoutChangingSchemaOrCreatingOutputs() throws Exception {
        Path data = temporaryDirectory.resolve("v18");
        Files.createDirectories(data.resolve("db"));
        String url = "jdbc:hsqldb:file:" + data.resolve("db/JFSDB");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection,
                    NormalizedSchemaMigrations.load().subList(0, 18));
            DataFormatManager.recordVersion(connection, DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION);
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        Path workbook = temporaryDirectory.resolve("old-import.xlsx");
        new AccountPlanSpreadsheetService().write(plan("Import"), workbook, false);
        Path output = temporaryDirectory.resolve("old-export.xlsx");
        for (String[] operation : List.of(new String[]{"list"},
                new String[]{"export", "--id", "0", "--output", output.toString()},
                new String[]{"import", "--file", workbook.toString()},
                new String[]{"import", "--file", workbook.toString(), "--apply"})) {
            Result result = cli(data, concat(new String[]{"--format", "json", "account-plan"}, operation));
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.error()).contains("NORMALIZED_ACCOUNT_PLAN_CATALOG_REQUIRED", "retained legacy");
        }
        assertThat(output).doesNotExist();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "ACCOUNT_PLAN_CATALOG", null)) {
                assertThat(tables.next()).isFalse();
            }
            try (var statement = connection.createStatement();
                 var history = statement.executeQuery("SELECT MAX(version) FROM bokfri_schema_history")) {
                history.next();
                assertThat(history.getInt(1)).isEqualTo(18);
            }
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    private static org.fribok.bookkeeping.dataformat.SemanticFingerprint coreFingerprint(Path data) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            connection.setReadOnly(true);
            var fingerprint = new org.fribok.bookkeeping.dataformat.AccountingCoreStagingConverter()
                    .fingerprintNormalized(connection);
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
            return fingerprint;
        }
    }

    private static SSAccountPlan plan(String name) {
        SSAccountPlan plan = new SSAccountPlan(name);
        plan.setBaseName("Base template");
        plan.setAssessementYear("2026");
        SSAccount revenue = new SSAccount(3010);
        revenue.setDescription("Revenue åäö");
        revenue.setVATCode("VAT");
        revenue.setSRUCode("SRU");
        revenue.setReportCode("R");
        revenue.setActive(false);
        revenue.setProjectRequired(true);
        revenue.setResultUnitRequired(true);
        plan.getAccounts().add(revenue);
        plan.getAccounts().add(new SSAccount(1930));
        return plan;
    }

    private static List<Map<String, Object>> snapshots(List<SSAccountPlan> plans) {
        return plans.stream().map(BokfriCliAccountPlanCatalogTest::snapshot).toList();
    }

    private static Map<String, Object> snapshot(SSAccountPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.getId());
        result.put("name", plan.getName());
        result.put("base", plan.getBaseName());
        result.put("year", plan.getAssessementYear());
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

    private Result cli(Path data, String... arguments) throws Exception {
        Path selection = temporaryDirectory.resolve("selection.config");
        if (!Files.exists(selection)) {
            SSDBConfig.setCompanyId(selection.toFile(), 777);
            SSDBConfig.setYearId(selection.toFile(), 777, 999);
        }
        byte[] before = Files.readAllBytes(selection);
        List<String> args = new ArrayList<>(List.of("--data-dir", data.toString(), "--config", selection.toString()));
        args.addAll(List.of(arguments));
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int code = BokfriCli.execute(args.toArray(String[]::new),
                new PrintWriter(output, true), new PrintWriter(error, true));
        assertThat(Files.readAllBytes(selection)).isEqualTo(before);
        return new Result(code, output.toString(), error.toString());
    }

    private static String[] concat(String[] prefix, String... suffix) {
        List<String> result = new ArrayList<>(List.of(prefix));
        result.addAll(List.of(suffix));
        return result.toArray(String[]::new);
    }

    private record Result(int code, String output, String error) { }
}
