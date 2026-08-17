package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the headless CLI and context configuration. */
class BokfriCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void companyAndYearUseShareTheGraphicalInterfaceSelection() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Path data = temporaryDirectory.resolve("data");
        Result companies = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "list");
        int companyId = new ObjectMapper().readTree(companies.stdout())
                .path("companies").get(0).path("id").asInt();
        Result years = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--format", "json", "year", "list");
        int yearId = new ObjectMapper().readTree(years.stdout()).path("years").get(0).path("id").asInt();

        Result companyUse = execute("--config", config.toString(), "--data-dir", data.toString(),
                "company", "use", Integer.toString(companyId));
        Result yearUse = execute("--config", config.toString(), "--data-dir", data.toString(),
                "year", "use", Integer.toString(yearId));
        Result status = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "status");

        assertThat(companyUse.exitCode()).isZero();
        assertThat(yearUse.exitCode()).isZero();
        JsonNode statusJson = new ObjectMapper().readTree(status.stdout());
        assertThat(statusJson.at("/selection/company/id").asInt()).isEqualTo(companyId);
        assertThat(statusJson.at("/selection/year/id").asInt()).isEqualTo(yearId);
        assertThat(statusJson.path("status").asText()).isEqualTo("ready");
        assertThat(Files.readString(config)).contains("company=\"" + companyId + "\"")
                .contains("year=\"" + yearId + "\"")
                .contains("yearid=\"" + yearId + "\"");
    }

    @Test
    void selectingCompanyRestoresItsLastAccountingYear() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Path data = temporaryDirectory.resolve("data");
        Result companies = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "list");
        int companyId = new ObjectMapper().readTree(companies.stdout())
                .path("companies").get(0).path("id").asInt();
        Result years = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--format", "json", "year", "list");
        int yearId = new ObjectMapper().readTree(years.stdout()).path("years").get(0).path("id").asInt();
        execute("--config", config.toString(), "--data-dir", data.toString(),
                "company", "use", Integer.toString(companyId));
        execute("--config", config.toString(), "--data-dir", data.toString(),
                "year", "use", Integer.toString(yearId));

        Result selected = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "use", Integer.toString(companyId));

        assertThat(selected.exitCode()).isZero();
        assertThat(new ObjectMapper().readTree(selected.stdout()).path("yearId").asInt()).isEqualTo(yearId);
    }

    @Test
    void commandLineOverridesDoNotChangeSharedSelection() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Path data = temporaryDirectory.resolve("data");
        Result companies = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "list");
        int companyId = new ObjectMapper().readTree(companies.stdout())
                .path("companies").get(0).path("id").asInt();
        execute("--config", config.toString(), "--data-dir", data.toString(),
                "company", "use", Integer.toString(companyId));
        String before = Files.readString(config);

        Result status = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", "999",
                "--format", "json", "status");

        assertThat(status.exitCode()).isEqualTo(1);
        JsonNode statusJson = new ObjectMapper().readTree(status.stdout());
        assertThat(statusJson.at("/selection/yearId").asInt()).isEqualTo(999);
        assertThat(statusJson.path("status").asText()).isEqualTo("broken");
        assertThat(statusJson.path("problem").asText()).contains("no accounting year with id 999");
        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void statusReportsWhenNoCompanyIsSelected() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Path data = temporaryDirectory.resolve("data");
        execute("--config", config.toString(), "--data-dir", data.toString(), "company", "list");
        Result result = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "status");

        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode status = new ObjectMapper().readTree(result.stdout());
        assertThat(status.path("status").asText()).isEqualTo("incomplete");
        assertThat(status.path("problem").asText()).isEqualTo("No company is selected");
    }

    @Test
    void recreatingSelectedDemoUpdatesSharedSelection() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Path data = temporaryDirectory.resolve("data");
        Result initial = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "demo", "recreate", "--commit");
        int oldDemoId = new ObjectMapper().readTree(initial.stdout()).path("companyId").asInt();
        assertThat(initial.exitCode()).isZero();
        assertThat(oldDemoId).isPositive();
        execute("--config", config.toString(), "--data-dir", data.toString(),
                "company", "use", Integer.toString(oldDemoId));

        Result recreated = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "demo", "recreate", "--commit");
        Result status = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "status");

        JsonNode recreatedJson = new ObjectMapper().readTree(recreated.stdout());
        JsonNode statusJson = new ObjectMapper().readTree(status.stdout());
        assertThat(recreated.exitCode()).isZero();
        assertThat(recreatedJson.path("selectionUpdated").asBoolean()).isTrue();
        assertThat(recreatedJson.path("companyId").asInt()).isNotEqualTo(oldDemoId);
        assertThat(status.exitCode()).isZero();
        assertThat(statusJson.at("/selection/company/id").asInt())
                .isEqualTo(recreatedJson.path("companyId").asInt());
        assertThat(statusJson.at("/selection/year/id").asInt())
                .isEqualTo(recreatedJson.path("yearId").asInt());
    }

    @Test
    void statusReportsMissingSelectedCompany() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        execute("--data-dir", data.toString(), "company", "list");

        Result status = execute("--data-dir", data.toString(), "--company-id", "999",
                "--format", "json", "status");

        assertThat(status.exitCode()).isEqualTo(1);
        JsonNode json = new ObjectMapper().readTree(status.stdout());
        assertThat(json.path("status").asText()).isEqualTo("broken");
        assertThat(json.at("/selection/companyId").asInt()).isEqualTo(999);
        assertThat(json.path("problem").asText()).contains("No company has id 999");
    }

    @Test
    void removedOverviewCommandsAreNoLongerAvailable() {
        CommandLine command = new CommandLine(new BokfriCli());

        assertThat(command.getSubcommands()).doesNotContainKeys("paths", "doctor");
        assertThat(command.getSubcommands().get("company").getSubcommands()).doesNotContainKey("current");
        assertThat(command.getSubcommands().get("year").getSubcommands()).doesNotContainKey("current");
    }

    @Test
    void everyCommandSupportsLongAndShortHelp() {
        List<List<String>> paths = new ArrayList<>();
        collectCommandPaths(new CommandLine(new BokfriCli()), List.of(), paths);

        assertThat(paths).hasSize(111);
        for (List<String> path : paths) {
            for (String helpOption : List.of("--help", "-h")) {
                List<String> arguments = new ArrayList<>(path);
                arguments.add(helpOption);
                Result result = execute(arguments.toArray(String[]::new));
                String command = path.isEmpty() ? "bokfri" : "bokfri " + String.join(" ", path);
                assertThat(result.exitCode()).as(command + " " + helpOption).isZero();
                assertThat(result.stderr()).as(command + " " + helpOption).isEmpty();
                assertThat(result.stdout()).as(command + " " + helpOption).startsWith("Usage:");
            }
        }
    }

    @Test
    void generatesVoucherSchemaWithoutOpeningDatabase() throws Exception {
        Result schema = execute("voucher", "schema");

        assertThat(schema.exitCode()).isZero();
        assertThat(schema.stderr()).isEmpty();
        JsonNode result = new ObjectMapper().readTree(schema.stdout());
        assertThat(result.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(result.path("$id").asText())
                .isEqualTo("https://bokfri.viblo.se/schemas/cli/voucher-v1.schema.json");
        assertThat(result.path("additionalProperties").asBoolean()).isFalse();
        assertThat(result.path("required")).extracting(JsonNode::asText)
                .contains("date", "description", "rows");
        assertThat(result.at("/properties/rows/minItems").asInt()).isEqualTo(1);
        assertThat(result.at("/properties/rows/items/required")).extracting(JsonNode::asText)
                .contains("account");
        assertThat(result.at("/properties/schemaVersion/const").asInt()).isEqualTo(1);
        assertThat(result.at("/properties/schemaVersion/default").asInt()).isEqualTo(1);
    }

    @Test
    void everyJsonInputCommandGeneratesDraft202012Schema() throws Exception {
        String[] commands = {"company", "year", "opening-balance", "customer", "product",
                "supplier", "supplier-invoice", "supplier-credit-invoice", "invoice",
                "credit-invoice", "inpayment", "outpayment", "voucher"};

        for (String command : commands) {
            Result schema = execute(command, "schema");
            assertThat(schema.exitCode()).as(command).isZero();
            JsonNode result = new ObjectMapper().readTree(schema.stdout());
            assertThat(result.path("$schema").asText()).as(command)
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(result.path("$id").asText()).as(command).endsWith(command + "-v1.schema.json");
            assertThat(result.path("additionalProperties").asBoolean()).as(command).isFalse();
        }
    }

    @Test
    void structuralAnnotationsDriveInputValidation() throws Exception {
        Path invalid = temporaryDirectory.resolve("invalid-voucher.json");
        Files.writeString(invalid, "{\"rows\":[]}");

        Result result = execute("--format", "json", "--company-id", "1", "--year-id", "1",
                "voucher", "validate", "--file", invalid.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode error = new ObjectMapper().readTree(result.stderr()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("INPUT_INVALID");
        assertThat(error.path("message").asText()).contains("$.date is required");
    }

    @Test
    void databaseStatusReportsCurrentFormat() throws Exception {
        Path data = temporaryDirectory.resolve("current-data");
        execute("--data-dir", data.toString(), "company", "list");

        Result status = execute("--data-dir", data.toString(), "--format", "json",
                "database", "status");

        assertThat(status.exitCode()).isZero();
        JsonNode result = new ObjectMapper().readTree(status.stdout());
        assertThat(result.path("exists").asBoolean()).isTrue();
        assertThat(result.path("format").asInt()).isEqualTo(2);
        assertThat(result.path("supportedFormat").asInt()).isEqualTo(2);
        assertThat(result.path("migrationRequired").asBoolean()).isFalse();
    }

    @Test
    void databaseMigrateCreatesVerifiedBackupAndEnablesNormalCommands() throws Exception {
        Path data = temporaryDirectory.resolve("legacy-data");
        extractLegacyDatabase(data.resolve("db"));

        Result blocked = execute("--data-dir", data.toString(), "--format", "json",
                "company", "list");
        Result status = execute("--data-dir", data.toString(), "--format", "json",
                "database", "status");
        Result migrated = execute("--data-dir", data.toString(), "--format", "json",
                "database", "migrate");
        Result companies = execute("--data-dir", data.toString(), "--format", "json",
                "company", "list");

        assertThat(blocked.exitCode()).isEqualTo(1);
        assertThat(new ObjectMapper().readTree(blocked.stderr()).at("/error/code").asText())
                .isEqualTo("DATABASE_MIGRATION_REQUIRED");
        assertThat(blocked.stderr()).contains("bokfri database migrate");
        assertThat(new ObjectMapper().readTree(status.stdout()).path("migrationRequired").asBoolean()).isTrue();
        JsonNode result = new ObjectMapper().readTree(migrated.stdout());
        assertThat(result.path("migrated").asBoolean()).isTrue();
        assertThat(result.path("fromFormat").asInt()).isEqualTo(1);
        assertThat(result.path("toFormat").asInt()).isEqualTo(2);
        assertThat(Path.of(result.path("backup").asText())).exists();
        assertThat(companies.exitCode()).isZero();
        assertThat(companies.stdout()).contains("Exempelföretag");
    }

    @Test
    void databaseMigrateIsIdempotentForCurrentFormat() throws Exception {
        Path data = temporaryDirectory.resolve("current-data");
        execute("--data-dir", data.toString(), "company", "list");

        Result migrated = execute("--data-dir", data.toString(), "--format", "json",
                "database", "migrate");

        assertThat(migrated.exitCode()).isZero();
        assertThat(new ObjectMapper().readTree(migrated.stdout()).path("migrated").asBoolean()).isFalse();
        assertThat(data.resolve("backups")).doesNotExist();
    }

    @Test
    void formatsMoneyWithTwoDecimalsAndOtherDecimalsAtTheirActualPrecision() {
        BigDecimal binaryNoise = new BigDecimal(0.18d);

        assertThat(BokfriCli.money(binaryNoise)).isEqualTo("0.18");
        assertThat(BokfriCli.money(new BigDecimal("12"))).isEqualTo("12.00");
        assertThat(BokfriCli.decimal(new BigDecimal("10.543210"))).isEqualTo("10.543210");
        assertThat(BokfriCli.money(null)).isNull();
        assertThat(BokfriCli.decimal(null)).isNull();
    }

    @Test
    void financialReportsRenderUsefulText() throws Exception {
        Path config = temporaryDirectory.resolve("reports-cli.yaml");
        Path data = temporaryDirectory.resolve("reports-data");
        Result companies = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "list");
        int companyId = new ObjectMapper().readTree(companies.stdout())
                .path("companies").get(0).path("id").asInt();
        Result years = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--format", "json", "year", "list");
        JsonNode yearRows = new ObjectMapper().readTree(years.stdout()).path("years");
        int yearId = 0;
        for (JsonNode year : yearRows) {
            if ("2026-07-01".equals(year.path("from").asText())) {
                yearId = year.path("id").asInt();
            }
        }
        String[] context = {"--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(yearId)};

        Result trial = execute(concat(context, "trial-balance"));
        Result balanceSheet = execute(concat(context, "balance-sheet"));
        Result income = execute(concat(context, "income-statement"));
        Result ledger = execute(concat(context, "general-ledger", "--account", "1930"));
        Result balance = execute(concat(context, "account", "balance", "1930"));

        assertThat(trial.stdout()).contains("Account\tDescription\tOpening\tDebit\tCredit\tClosing", "TOTAL")
                .doesNotContain("generated");
        assertThat(balanceSheet.stdout()).contains("Account\tDescription\tBalance", "Assets:", "Difference:")
                .doesNotContain("generated");
        assertThat(income.stdout()).contains("Account\tDescription\tAmount", "Result:")
                .doesNotContain("generated");
        assertThat(ledger.stdout()).contains("Voucher\tDate\tDescription\tDebit\tCredit\tBalance", "Closing:")
                .doesNotContain("generated");
        assertThat(balance.stdout()).contains("1930\tFöretagskonto\t2027-06-30\t")
                .doesNotContain("generated");
    }

    private static void collectCommandPaths(CommandLine command, List<String> prefix,
                                            List<List<String>> paths) {
        paths.add(List.copyOf(prefix));
        command.getSubcommands().forEach((name, subcommand) -> {
            List<String> path = new ArrayList<>(prefix);
            path.add(name);
            collectCommandPaths(subcommand, path, paths);
        });
    }

    private void extractLegacyDatabase(Path destination) throws Exception {
        Files.createDirectories(destination);
        try (var input = getClass().getResourceAsStream("/compat/v1.0.1/database-v1.0.1.zip");
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().startsWith("META-INF/")) {
                    Path target = destination.resolve(entry.getName()).normalize();
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String[] concat(String[] prefix, String... suffix) {
        String[] result = java.util.Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private Result execute(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = BokfriCli.execute(arguments,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8).trim(),
                stderr.toString(StandardCharsets.UTF_8).trim());
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
