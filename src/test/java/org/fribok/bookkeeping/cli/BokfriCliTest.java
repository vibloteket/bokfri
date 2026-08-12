package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the headless CLI and context configuration. */
class BokfriCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsSelectsAndPrintsContextAsJson() throws Exception {
        Path config = temporaryDirectory.resolve("config/cli.yaml");
        Path data = temporaryDirectory.resolve("data");

        Result create = execute("--config", config.toString(), "context", "create", "--name", "demo-2026",
                "--data-dir", data.toString(), "--company-id", "12", "--year-id", "34");
        Result use = execute("--config", config.toString(), "context", "use", "demo-2026");
        Result current = execute("--config", config.toString(), "--format", "json",
                "context", "current");

        assertThat(create.exitCode()).isZero();
        assertThat(use.exitCode()).isZero();
        assertThat(current.exitCode()).isZero();
        assertThat(current.stderr()).isEmpty();

        JsonNode result = new ObjectMapper().readTree(current.stdout());
        assertThat(result.path("name").asText()).isEqualTo("demo-2026");
        assertThat(result.path("current").asBoolean()).isTrue();
        assertThat(result.path("companyId").asInt()).isEqualTo(12);
        assertThat(result.path("yearId").asInt()).isEqualTo(34);
        assertThat(Files.readString(config)).contains("current-context: \"demo-2026\"");
    }

    @Test
    void contextCreateDerivesNameFromCompanyAndYear() throws Exception {
        Path config = temporaryDirectory.resolve("auto-name-cli.yaml");
        Path data = temporaryDirectory.resolve("auto-name-data");
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

        Result create = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(yearId),
                "--format", "json", "context", "create");

        assertThat(create.exitCode()).isZero();
        JsonNode result = new ObjectMapper().readTree(create.stdout());
        assertThat(result.path("name").asText()).isEqualTo("bokfri-demo-ab-20260701");
        assertThat(Files.readString(config)).contains("bokfri-demo-ab-20260701:");
    }

    @Test
    void contextCreateDoesNotOverwriteAnotherContextWithTheSameName() throws Exception {
        Path config = temporaryDirectory.resolve("collision-cli.yaml");
        Path data = temporaryDirectory.resolve("collision-data");
        Result first = execute("--config", config.toString(), "context", "create", "--name", "demo",
                "--data-dir", data.toString(), "--company-id", "1", "--year-id", "2");
        Result duplicate = execute("--config", config.toString(), "--format", "json",
                "context", "create", "--name", "demo", "--data-dir", data.toString(),
                "--company-id", "9", "--year-id", "2");

        assertThat(first.exitCode()).isZero();
        assertThat(duplicate.exitCode()).isEqualTo(1);
        assertThat(new ObjectMapper().readTree(duplicate.stderr()).at("/error/code").asText())
                .isEqualTo("CONTEXT_NAME_EXISTS");
    }

    @Test
    void contextCreateUsesDefaultDataDirectoryWhenItIsOmitted() throws Exception {
        Path config = temporaryDirectory.resolve("cli.yaml");

        Result create = execute("--config", config.toString(), "--format", "json",
                "context", "create", "--name", "default-data", "--company-id", "12", "--year-id", "34");

        assertThat(create.exitCode()).isZero();
        assertThat(create.stderr()).isEmpty();
        JsonNode result = new ObjectMapper().readTree(create.stdout());
        assertThat(result.path("dataDir").asText()).isEqualTo(
                org.fribok.bookkeeping.app.Path.get(
                        org.fribok.bookkeeping.app.Path.USER_DATA).toPath()
                        .toAbsolutePath().normalize().toString());
        assertThat(Files.readString(config)).contains("data-dir:");
    }

    @Test
    void contextCreateStillRequiresCompanyAndYear() throws Exception {
        Result result = execute("--config", temporaryDirectory.resolve("cli.yaml").toString(),
                "--format", "json", "context", "create");

        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode error = new ObjectMapper().readTree(result.stderr()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("CONTEXT_VALUES_REQUIRED");
        assertThat(error.path("message").asText()).isEqualTo("Provide --company-id and --year-id");
    }

    @Test
    void commandLineOverridesCurrentContextWithoutChangingConfig() throws Exception {
        Path config = temporaryDirectory.resolve("cli.yaml");
        Path firstData = temporaryDirectory.resolve("first");
        Path secondData = temporaryDirectory.resolve("second");
        execute("--config", config.toString(), "context", "create", "--name", "first",
                "--data-dir", firstData.toString(), "--company-id", "1", "--year-id", "10");
        execute("--config", config.toString(), "context", "create", "--name", "second",
                "--data-dir", secondData.toString(), "--company-id", "2", "--year-id", "20");
        execute("--config", config.toString(), "context", "use", "first");

        Result doctor = execute("--config", config.toString(), "--context", "second",
                "--company-id", "3", "--format", "json", "doctor");

        assertThat(doctor.exitCode()).isZero();
        JsonNode result = new ObjectMapper().readTree(doctor.stdout());
        assertThat(result.at("/context/name").asText()).isEqualTo("second");
        assertThat(result.at("/context/companyId").asInt()).isEqualTo(3);
        assertThat(result.at("/context/yearId").asInt()).isEqualTo(20);

        Result current = execute("--config", config.toString(), "--format", "json",
                "context", "current");
        assertThat(new ObjectMapper().readTree(current.stdout()).path("name").asText())
                .isEqualTo("first");
    }

    @Test
    void acceptsGlobalOptionsAfterNestedCommands() throws Exception {
        Path config = temporaryDirectory.resolve("cli.yaml");
        Path storedData = temporaryDirectory.resolve("stored");
        Path overriddenData = temporaryDirectory.resolve("overridden");
        Result create = execute("--config", config.toString(), "context", "create", "--name", "selected",
                "--data-dir", storedData.toString(), "--company-id", "1", "--year-id", "2");

        Result doctor = execute("doctor", "--config=" + config, "--context=selected",
                "--data-dir=" + overriddenData, "--company-id=37", "--year-id=38", "--format=json");

        assertThat(create.exitCode()).isZero();
        assertThat(doctor.exitCode()).isZero();
        assertThat(doctor.stderr()).isEmpty();
        JsonNode result = new ObjectMapper().readTree(doctor.stdout());
        assertThat(result.at("/context/name").asText()).isEqualTo("selected");
        assertThat(result.at("/context/dataDir").asText()).isEqualTo(overriddenData.toString());
        assertThat(result.at("/context/companyId").asInt()).isEqualTo(37);
        assertThat(result.at("/context/yearId").asInt()).isEqualTo(38);
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

    @Test
    void reportsStableJsonErrorForUnknownContext() throws Exception {
        Path config = temporaryDirectory.resolve("cli.yaml");

        Result result = execute("--config", config.toString(), "--format", "json",
                "--context", "missing", "doctor");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        JsonNode error = new ObjectMapper().readTree(result.stderr()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("CONTEXT_NOT_FOUND");
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
