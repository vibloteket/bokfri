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
        Result companyCurrent = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "company", "current");
        Result yearCurrent = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--format", "json", "year", "current");

        assertThat(companyUse.exitCode()).isZero();
        assertThat(yearUse.exitCode()).isZero();
        assertThat(new ObjectMapper().readTree(companyCurrent.stdout()).path("id").asInt()).isEqualTo(companyId);
        assertThat(new ObjectMapper().readTree(yearCurrent.stdout()).path("id").asInt()).isEqualTo(yearId);
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

        Result doctor = execute("--config", config.toString(), "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", "999",
                "--format", "json", "doctor");

        assertThat(doctor.exitCode()).isZero();
        assertThat(new ObjectMapper().readTree(doctor.stdout()).at("/selection/yearId").asInt()).isEqualTo(999);
        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void reportsStableJsonErrorWhenNoCompanyIsSelected() throws Exception {
        Path config = temporaryDirectory.resolve("database.config");
        Result result = execute("--config", config.toString(), "--format", "json", "company", "current");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(new ObjectMapper().readTree(result.stderr()).at("/error/code").asText())
                .isEqualTo("COMPANY_REQUIRED");
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
