package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that the runtime dispatches between legacy and normalized storage by data format. */
class BokfriRuntimeDispatchTest {

    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshDatabaseOpensInLegacyMode() throws Exception {
        Path data = temporaryDirectory.resolve("fresh-data");

        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(runtime.isNormalized()).isFalse();
            assertThat(runtime.dataFormat()).isEqualTo(DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
            assertThat(runtime.database()).isNotNull();
            assertThatThrownBy(runtime::normalizedStore)
                    .isInstanceOf(CliException.class)
                    .extracting(error -> ((CliException) error).getCode())
                    .isEqualTo("LEGACY_STORAGE");
            // SSDB is a JVM-global singleton that can retain in-memory state from earlier
            // tests, so use an id that cannot exist instead of assuming an empty database.
            assertThatThrownBy(() -> runtime.selectCompany(Integer.MAX_VALUE))
                    .isInstanceOf(CliException.class)
                    .extracting(error -> ((CliException) error).getCode())
                    .isEqualTo("COMPANY_NOT_FOUND");
        }
    }

    @Test
    void normalizedDatabaseOpensInNormalizedModeWithWorkingReads() throws Exception {
        Path data = temporaryDirectory.resolve("normalized-data");
        extractFixture(data.resolve("db"));
        new NormalizedMigrationService(
                Clock.fixed(Instant.parse("2026-09-27T08:30:00Z"), ZoneOffset.UTC))
                .migrate(data);

        List<SSNewCompany> companies;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            assertThat(runtime.isNormalized()).isTrue();
            assertThat(runtime.dataFormat())
                    .isEqualTo(DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION);
            assertThat(runtime.normalizedStore()).isNotNull();
            assertThat(runtime.registerStore()).isNotNull();
            assertThatThrownBy(runtime::database)
                    .isInstanceOf(CliException.class)
                    .extracting(error -> ((CliException) error).getCode())
                    .isEqualTo("NORMALIZED_STORAGE_UNSUPPORTED");

            companies = runtime.getCompanies();
            assertThat(companies).isNotEmpty();
            SSNewCompany company = runtime.selectCompany(companies.get(0).getId());
            assertThat(company.getName()).isEqualTo(companies.get(0).getName());

            List<SSNewAccountingYear> years = runtime.getYears(company);
            assertThat(years).isNotEmpty();
            SSNewAccountingYear year = runtime.selectYear(company, years.get(0).getId());
            assertThat(year.getId()).isEqualTo(years.get(0).getId());

            List<SSAccount> accounts = runtime.getAccounts();
            assertThat(accounts).isNotEmpty();
        }

        // A clean shutdown must leave the catalog reopenable in the same JVM.
        try (BokfriRuntime reopened = BokfriRuntime.open(data)) {
            assertThat(reopened.isNormalized()).isTrue();
            assertThat(reopened.getCompanies()).hasSize(companies.size());
        }
    }

    private void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var input = getClass().getResourceAsStream(FIXTURE);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().startsWith("META-INF/")) {
                    Files.copy(zip, destination.resolve(entry.getName()).normalize(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
