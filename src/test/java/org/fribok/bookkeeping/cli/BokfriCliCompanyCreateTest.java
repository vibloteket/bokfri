package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BokfriCliCompanyCreateTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void creationSettingsAndValidationMatchLegacyAfterReopen() throws Exception {
        List<Map<String, Object>> expected = null;
        for (boolean normalized : List.of(false, true)) {
            Path data = temporaryDirectory.resolve("companies-" + normalized);
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                SSNewCompany existing = new SSNewCompany();
                existing.setName("Existing company");
                runtime.database().addCompany(existing);
                SSCurrency currency = new SSCurrency("XTS", "Custom currency");
                currency.setExchangeRate(new BigDecimal("2.34567"));
                runtime.database().addCurrency(currency);
                runtime.database().addPaymentTerm(new SSPaymentTerm("45", "Custom payment term"));
                runtime.database().addUnit(new SSUnit("custom-unit", "Custom unit"));
            }
            if (normalized) new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
            Map<String, Object> custom = new LinkedHashMap<>();
            custom.put("name", "  Created company  ");
            custom.put("corporateId", " 556000-0000 ");
            custom.put("vatNumber", " SE556000000001 ");
            custom.put("email", " company@example.test ");
            custom.put("phone", " +46 12345 ");
            custom.put("contactPerson", " Contact Person ");
            custom.put("bankgiro", " 123-4567 ");
            custom.put("logotype", " logo.png ");
            custom.put("currency", "xts");
            custom.put("paymentTerms", "45");
            custom.put("standardUnit", "custom-unit");
            custom.put("vatPeriod", 3);
            custom.put("reminderFee", new BigDecimal("42.125"));
            custom.put("delayInterest", new BigDecimal("8.75"));
            Result created = create(data, custom);
            assertThat(created.code()).as(created.error()).isZero();
            assertThat(mapper.readTree(created.output()).path("id").asInt()).isEqualTo(2);
            assertThat(mapper.readTree(created.output()).path("name").asText()).isEqualTo("Created company");
            assertThat(create(data, Map.of("name", "Default company")).code()).isZero();
            Result duplicate = create(data, Map.of("name", "Created company"));
            assertThat(duplicate.code()).isEqualTo(1);
            assertThat(duplicate.error()).contains("Company name already exists");
            for (String field : List.of("currency", "paymentTerms", "standardUnit")) {
                String error = switch (field) {
                    case "currency" -> "COMPANY_CURRENCY_NOT_FOUND";
                    case "paymentTerms" -> "COMPANY_PAYMENT_TERMS_NOT_FOUND";
                    default -> "COMPANY_UNIT_NOT_FOUND";
                };
                Result invalid = create(data, Map.of("name", "Invalid " + field, field, "not-registered"));
                assertThat(invalid.code()).isEqualTo(1);
                assertThat(invalid.error()).contains(error);
            }
            try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
                List<SSNewCompany> companies = runtime.getCompanies();
                assertThat(companies).hasSize(4);
                SSNewCompany stored = companies.stream().filter(value -> value.getId() == 2).findFirst().orElseThrow();
                assertThat(stored.getCurrency().getName()).isEqualTo("XTS");
                assertThat(stored.getCurrency().getDescription()).isEqualTo("Custom currency");
                assertThat(stored.getCurrency().getExchangeRate()).isEqualByComparingTo("2.34567");
                assertThat(stored.getPaymentTerm().getDescription()).isEqualTo("Custom payment term");
                assertThat(stored.getStandardUnit().getDescription()).isEqualTo("Custom unit");
                for (SSDefaultAccount account : SSDefaultAccount.values()) {
                    assertThat(stored.getDefaultAccounts()).containsEntry(account, account.getDefaultAccountNumber());
                }
                List<Map<String, Object>> snapshots = companies.stream().filter(value -> value.getId() >= 2)
                        .map(this::snapshot).toList();
                if (normalized) assertThat(snapshots).isEqualTo(expected);
                else expected = snapshots;
            }
            if (normalized) {
                assertThat(SSDB.getInstance().getCurrentCompany()).isNull();
                assertThat(SSDB.getInstance().getCurrentYear()).isNull();
            }
        }
    }

    @Test
    void firstCompanyCanBeCreatedOnAnEmptyNormalizedCatalog() throws Exception {
        Path data = temporaryDirectory.resolve("empty");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            for (SSNewCompany company : new ArrayList<>(runtime.getCompanies())) {
                runtime.database().deleteCompany(company);
            }
        }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM currency WHERE code='SEK'");
                statement.executeUpdate("DELETE FROM payment_term WHERE name='30'");
                statement.executeUpdate("DELETE FROM unit_definition WHERE name='st'");
                connection.commit();
                statement.execute("SHUTDOWN");
            }
        }
        Result created = create(data, Map.of("name", "First normalized company"));
        assertThat(created.code()).as(created.error()).isZero();
        assertThat(mapper.readTree(created.output()).path("id").asInt()).isZero();
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = runtime.getCompanies().get(0);
            assertThat(company.getCurrency().getName()).isEqualTo("SEK");
            assertThat(company.getPaymentTerm().getName()).isEqualTo("30");
            assertThat(company.getStandardUnit().getName()).isEqualTo("st");
            assertThat(company.getDefaultAccounts()).hasSize(SSDefaultAccount.values().length);
            assertThat(company.getAutoIncrement().getNumbers()).isEmpty();
            assertThat(runtime.getYears(company)).isEmpty();
        }
    }

    @Test
    void failedWriteRollsBackNewLookupsAndCompanyAndAllowsRetry() throws Exception {
        Path data = temporaryDirectory.resolve("rollback");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            for (SSNewCompany company : new ArrayList<>(runtime.getCompanies())) {
                runtime.database().deleteCompany(company);
            }
        }
        new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        SSNewCompany company = new SSNewCompany();
        company.setName("Retry company");
        company.setPhone("x".repeat(2048));
        company.setCurrency(new SSCurrency("ROLLBACK", "Rollback currency"));
        company.setStandardUnit(new SSUnit("rollback-unit", "Rollback unit"));
        company.setPaymentTerm(new SSPaymentTerm("rollback-term", "Rollback term"));
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThatThrownBy(() -> runtime.createCompany(company)).isInstanceOf(java.sql.SQLException.class);
            assertThat(company.getId()).isZero();
            assertThat(runtime.getCompanies()).isEmpty();
            assertThat(runtime.getCurrencies()).noneMatch(value -> value.getName().equals("ROLLBACK"));
            assertThat(runtime.getUnits()).noneMatch(value -> value.getName().equals("rollback-unit"));
            assertThat(runtime.getPaymentTerms()).noneMatch(value -> value.getName().equals("rollback-term"));
            company.setPhone("valid");
            Map<SSDefaultAccount, Integer> invalidDefaults = new LinkedHashMap<>();
            invalidDefaults.put(SSDefaultAccount.Sales, 3010);
            invalidDefaults.put(null, 1930);
            company.setDefaultAccounts(invalidDefaults);
            assertThatThrownBy(() -> runtime.createCompany(company)).isInstanceOf(NullPointerException.class);
            assertThat(company.getId()).isZero();
            assertThat(runtime.getCompanies()).isEmpty();
            assertThat(runtime.getCurrencies()).noneMatch(value -> value.getName().equals("ROLLBACK"));
            company.setDefaultAccounts(Map.of(SSDefaultAccount.Sales, 3010));
            runtime.createCompany(company);
        }
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(runtime.getCompanies()).hasSize(1);
            assertThat(runtime.getCompanies().get(0).getPhone()).isEqualTo("valid");
            assertThat(runtime.getCompanies().get(0).getCurrency().getName()).isEqualTo("ROLLBACK");
        }
    }

    private Map<String, Object> snapshot(SSNewCompany company) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", company.getId());
        result.put("name", company.getName());
        result.put("corporateId", company.getCorporateID());
        result.put("vatNumber", company.getVATNumber());
        result.put("email", company.getEMail());
        result.put("phone", company.getPhone());
        result.put("contact", company.getContactPerson());
        result.put("bankgiro", company.getBankGiroNumber());
        result.put("logo", company.getLogotype());
        result.put("vatPeriod", company.getVatPeriod());
        result.put("fee", decimal(company.getReminderfee()));
        result.put("interest", decimal(company.getDelayInterest()));
        result.put("currency", company.getCurrency() == null ? null : company.getCurrency().getName());
        result.put("paymentTerm", company.getPaymentTerm() == null ? null : company.getPaymentTerm().getName());
        result.put("unit", company.getStandardUnit() == null ? null : company.getStandardUnit().getName());
        result.put("defaults", company.getDefaultAccounts());
        result.put("counters", company.getAutoIncrement().getNumbers());
        result.put("rounding", company.isRoundingOff());
        result.put("taxRates", List.of(decimal(company.getTaxRate1()), decimal(company.getTaxRate2()),
                decimal(company.getTaxRate3())));
        return result;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private Result create(Path data, Map<String, Object> input) throws Exception {
        Path file = temporaryDirectory.resolve("input.json");
        mapper.writeValue(file.toFile(), input);
        Path selection = temporaryDirectory.resolve("selection.config");
        if (!Files.exists(selection)) {
            SSDBConfig.setCompanyId(selection.toFile(), 7);
            SSDBConfig.setYearId(selection.toFile(), 7, 19);
        }
        byte[] original = Files.readAllBytes(selection);
        Result result = execute("--data-dir", data.toString(), "--config", selection.toString(),
                "--format", "json", "company", "create", "--file", file.toString());
        assertThat(Files.readAllBytes(selection)).isEqualTo(original);
        return result;
    }

    private static Result execute(String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = BokfriCli.execute(arguments, new PrintWriter(output, true, StandardCharsets.UTF_8),
                new PrintWriter(error, true, StandardCharsets.UTF_8));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String output, String error) { }
}
