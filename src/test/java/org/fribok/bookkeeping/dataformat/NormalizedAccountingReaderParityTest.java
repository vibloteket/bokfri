package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the normalized catalog reads back the same accounting core as the legacy object graph. */
@Tag("integration")
class NormalizedAccountingReaderParityTest {
    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @Test
    void normalizedReaderMatchesLegacyGraphForTheRealFixture(@TempDir Path tempDir)
            throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));
        HsqlEngineMigrationService.migrateIfRequired(data);

        Class.forName("org.hsqldb.jdbcDriver");
        AccountingCoreStagingCatalogService.StagingCatalogResult staged;
        List<SSNewCompany> legacyCompanies;
        List<SSNewAccountingYear> legacyYears;
        List<SSAccount> legacyAccounts;
        try (Connection legacy = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            legacy.setAutoCommit(false);
            staged = new AccountingCoreStagingCatalogService(
                    Clock.fixed(Instant.parse("2026-09-26T16:00:00Z"), ZoneOffset.UTC))
                    .create(data, legacy);
            legacyCompanies = readLegacyCompanies(legacy);
            SSNewCompany company = legacyCompanies.get(0);
            legacyYears = readLegacyYears(legacy, company.getId());
            List<SSAccount> legacyRaw = legacyYears.isEmpty() ? List.of()
                    : new java.util.ArrayList<>(legacyYears.get(0).getAccounts());
            // Legacy plans can contain duplicate account numbers; the normalized schema
            // enforces UNIQUE(year, number), so parity compares the first occurrence of each.
            java.util.Map<Integer, SSAccount> deduped = new java.util.LinkedHashMap<>();
            for (SSAccount account : legacyRaw) {
                if (account.getNumber() != null) deduped.putIfAbsent(account.getNumber(), account);
            }
            legacyAccounts = new java.util.ArrayList<>(deduped.values());
            legacyAccounts.sort(java.util.Comparator.comparing(SSAccount::getNumber));
            try (var statement = legacy.createStatement()) { statement.execute("SHUTDOWN"); }
        }

        try (Connection normalized = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + staged.stagingDirectory().resolve("db/JFSDB"), "sa", "")) {
            normalized.setReadOnly(true);
            NormalizedAccountingReader reader = new NormalizedAccountingReader();
            int companyLegacyId = legacyCompanies.get(0).getId();

            List<SSNewCompany> companies = reader.companies(normalized);
            assertThat(companies).hasSameSizeAs(legacyCompanies);
            for (int i = 0; i < companies.size(); i++) {
                assertThat(companies.get(i).getId()).isEqualTo(legacyCompanies.get(i).getId());
                assertThat(companies.get(i).getName()).isEqualTo(legacyCompanies.get(i).getName());
                assertThat(companies.get(i).getCorporateID())
                        .isEqualTo(legacyCompanies.get(i).getCorporateID());
                assertThat(companies.get(i).getVATNumber())
                        .isEqualTo(legacyCompanies.get(i).getVATNumber());
            }

            List<SSNewAccountingYear> years = reader.years(normalized, companyLegacyId);
            assertThat(years).hasSameSizeAs(legacyYears);
            for (int i = 0; i < years.size(); i++) {
                assertThat(years.get(i).getId()).isEqualTo(legacyYears.get(i).getId());
                assertThat(years.get(i).getLocalFrom()).isEqualTo(legacyYears.get(i).getLocalFrom());
                assertThat(years.get(i).getLocalTo()).isEqualTo(legacyYears.get(i).getLocalTo());
                assertThat(years.get(i).getAccountPlan().getName())
                        .isEqualTo(legacyYears.get(i).getAccountPlan().getName());
            }

            if (!legacyYears.isEmpty()) {
                List<SSAccount> accounts = reader.accounts(normalized, legacyYears.get(0).getId());
                assertThat(accounts).hasSameSizeAs(legacyAccounts);
                for (int i = 0; i < accounts.size(); i++) {
                    assertThat(accounts.get(i).getNumber()).as("account index "+i).isEqualTo(legacyAccounts.get(i).getNumber());
                    assertThat(accounts.get(i).getDescription())
                            .isEqualTo(legacyAccounts.get(i).getDescription());
                    assertThat(accounts.get(i).isActive())
                            .isEqualTo(legacyAccounts.get(i).isActive());
                    assertThat(accounts.get(i).isProjectRequired())
                            .isEqualTo(legacyAccounts.get(i).isProjectRequired());
                    assertThat(accounts.get(i).isResultUnitRequired())
                            .isEqualTo(legacyAccounts.get(i).isResultUnitRequired());
                }
            }
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    private static List<SSNewCompany> readLegacyCompanies(Connection legacy) throws Exception {
        try (var statement = legacy.createStatement();
             var result = statement.executeQuery("SELECT company FROM tbl_company ORDER BY id")) {
            List<SSNewCompany> companies = new java.util.ArrayList<>();
            while (result.next()) {
                SSNewCompany company = (SSNewCompany) result.getObject(1);
                companies.add(company);
            }
            return companies;
        }
    }

    private static List<SSNewAccountingYear> readLegacyYears(Connection legacy, int companyId)
            throws Exception {
        try (var statement = legacy.prepareStatement(
                "SELECT accountingyear FROM tbl_accountingyear WHERE companyid=? ORDER BY id")) {
            statement.setInt(1, companyId);
            try (var result = statement.executeQuery()) {
                List<SSNewAccountingYear> years = new java.util.ArrayList<>();
                while (result.next()) {
                    years.add((SSNewAccountingYear) result.getObject(1));
                }
                return years;
            }
        }
    }

    private static void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = NormalizedAccountingReaderParityTest.class
                .getResourceAsStream(FIXTURE)) {
            if (input == null) throw new IOException("Missing fixture " + FIXTURE);
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                    Path target = destination.resolve(entry.getName()).normalize();
                    if (!target.getParent().equals(destination)) {
                        throw new IOException("Fixture entry escapes destination");
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
