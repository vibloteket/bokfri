package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.cli.BokfriCli;
import org.fribok.bookkeeping.cli.BokfriRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedCatalogRecoveryServiceTest {
    @TempDir Path temporary;

    @Test
    void recoversOnlyCatalogueAndPreservesEvolvedRowsAndPhysicalRollback() throws Exception {
        Fixture fixture = fixture();
        String backupBefore = sha(fixture.backup());
        Map<String, String> rows;
        try (Connection connection = open(fixture.data())) {
            rows = NormalizedCatalogRecoveryService.fingerprintExistingTables(connection);
            shutdown(connection);
        }
        // Opening/checkpointing may change physical files; take the actual pre-recovery baseline.
        Map<String, String> original = files(fixture.data().resolve("db"));
        var recovered = service().recover(fixture.data(), fixture.backup(), sha(fixture.backup()));
        assertThat(recovered.catalogue().plans()).isGreaterThanOrEqualTo(2);
        assertThat(recovered.catalogue().sourceFingerprint()).isEqualTo(recovered.catalogue().destinationFingerprint());
        assertThat(files(recovered.activation().retainedSource())).isEqualTo(original);
        assertThat(Files.isRegularFile(recovered.activation().rollbackArchive())).isTrue();
        assertThat(sha(fixture.backup())).isEqualTo(backupBefore);
        Map<String, String> archived = new TreeMap<>();
        try (var zip = new java.util.zip.ZipFile(recovered.activation().rollbackArchive().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    archived.put(entry.getName(), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
                }
            }
        }
        assertThat(archived).isEqualTo(original);
        try (Connection connection = open(fixture.data())) {
            assertThat(NormalizedCatalogRecoveryService.fingerprintExistingTables(connection)).isEqualTo(rows);
            var plans = new NormalizedAccountPlanCatalogStore(connection).getAccountPlans();
            SSAccountPlan recoveredPlan = plans.stream().filter(plan -> "Recovery template".equals(plan.getName()))
                    .findFirst().orElseThrow();
            assertThat(recoveredPlan.getAccounts()).extracting(SSAccount::getNumber).containsExactly(3010, 1930);
            assertThat(recoveredPlan.getAccounts().get(1).getDescription()).isEqualTo("Original bank");
            assertThat(recoveredPlan.getAccounts().get(1).isProjectRequired()).isTrue();
            assertThat(recoveredPlan.getAccounts().get(1).isActive()).isFalse();
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT text_value, amount FROM recovery_evolved")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("Created after normalization");
                assertThat(result.getBigDecimal(2)).isEqualByComparingTo("91234.5678");
            }
            shutdown(connection);
        }
        try (BokfriRuntime runtime = BokfriRuntime.open(fixture.data())) {
            assertThat(runtime.getAccountPlans()).anyMatch(plan -> "Recovery template".equals(plan.getName()));
            var company = runtime.getCompanies().stream().filter(value -> "Preserved company".equals(value.getName()))
                    .findFirst().orElseThrow();
            runtime.selectCompany(company.getId());
            var existingYear = runtime.getYears(company).get(0);
            runtime.selectYear(company, existingYear.getId());
            assertThat(runtime.getVouchers()).extracting(value -> value.getDescription())
                    .containsExactly("Entered after normalization");
            var year = runtime.createAccountingYear(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31),
                    runtime.getAccountPlans().stream().filter(plan -> "Recovery template".equals(plan.getName()))
                            .findFirst().orElseThrow());
            assertThat(year.getAccountPlan().getAccounts()).hasSize(2);
        }
    }

    @Test
    void cliRequiresExplicitCommitAndReportsVerifiedBackupAndCounts() throws Exception {
        Fixture fixture = fixture();
        Map<String, String> before = files(fixture.data().resolve("db"));
        var preview = cli(fixture, false);
        assertThat(preview.code()).isEqualTo(1);
        assertThat(preview.error()).contains("COMMIT_REQUIRED");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
        var committed = cli(fixture, true);
        assertThat(committed.code()).as(committed.error()).isZero();
        assertThat(committed.output()).contains("\"recovered\":true", "legacyBackupSha256", "retainedSource");
    }

    @Test
    void rejectsBadDigestWithoutCreatingStagingOrTouchingActiveFiles() throws Exception {
        Fixture fixture = fixture();
        var before = files(fixture.data().resolve("db"));
        for (String digest : new String[]{"bad", "0".repeat(64)}) {
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), digest))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("SHA-256");
            assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
            assertThat(MigrationRecoveryInspector.inspect(fixture.data()).stagingDirectories()).isEmpty();
        }
    }

    @Test
    void rejectsExistingV19AndLeavesActiveDatabaseAndTrustedSourceUntouched() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = open(fixture.data())) {
            new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection, NormalizedSchemaMigrations.load());
            shutdown(connection);
        }
        var before = files(fixture.data().resolve("db"));
        String backupBefore = sha(fixture.backup());
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), backupBefore))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("V18");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
        assertThat(sha(fixture.backup())).isEqualTo(backupBefore);
    }

    @Test
    void checksumHistoryFailureCannotMutateActiveFiles() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = open(fixture.data()); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE bokfri_schema_history SET script_sha256='" + "a".repeat(64)
                    + "' WHERE version=18");
            shutdown(connection);
        }
        var before = files(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                .isInstanceOf(SchemaMigrationException.class).hasMessageContaining("does not match");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
        // Failed staging is intentionally retained and blocks automatic retry.
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Unresolved migration state");
    }

    @Test
    void rejectsLockedAndDirtyActiveCataloguesBeforeStaging() throws Exception {
        Fixture fixture = fixture();
        for (String suffix : new String[]{"lck", "log"}) {
            Path marker = fixture.data().resolve("db/JFSDB." + suffix);
            Files.writeString(marker, "do not touch");
            var before = files(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("cleanly shut down");
            assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
            Files.delete(marker);
        }
    }

    @Test
    void rejectsUnsafeZipPathsWithoutChangingActiveFiles() throws Exception {
        Fixture fixture = fixture();
        Path malicious = temporary.resolve("unsafe.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        var before = files(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), malicious, sha(malicious)))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Unexpected legacy backup entry");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
        assertThat(Files.exists(temporary.resolve("escape"))).isFalse();
    }

    @Test
    void rejectsPartialV19TablesBeforeApplyingMigration() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = open(fixture.data()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE account_plan_catalog (id INTEGER PRIMARY KEY)");
            shutdown(connection);
        }
        var before = files(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Partial or existing catalogue");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
    }

    @Test
    void rejectsNormalizedBackupAndLeavesActiveDataUntouched() throws Exception {
        Fixture fixture = fixture();
        Path normalizedBackup = temporary.resolve("normalized.zip");
        zip(fixture.data().resolve("db"), normalizedBackup);
        var before = files(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), normalizedBackup, sha(normalizedBackup)))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("supported legacy storage");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
    }

    @Test
    void rejectsInvalidLegacyAccountGraphWithoutActivatingPartialConversion() throws Exception {
        Fixture fixture = fixture();
        Path source = temporary.resolve("invalid-source");
        Path sourceDb = Files.createDirectories(source.resolve("db"));
        try (var archive = new java.util.zip.ZipFile(fixture.backup().toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = archive.getInputStream(entry)) {
                    Files.copy(input, sourceDb.resolve(entry.getName()));
                }
            }
        }
        try (Connection connection = open(source)) {
            SSAccountPlan invalid = new SSAccountPlan("Invalid account graph");
            invalid.setId(987654);
            invalid.getAccounts().add(null);
            try (var insert = connection.prepareStatement("INSERT INTO tbl_accountplan VALUES (?,?)")) {
                insert.setInt(1, invalid.getId());
                insert.setObject(2, invalid);
                insert.executeUpdate();
            }
            shutdown(connection);
        }
        Path invalidBackup = temporary.resolve("invalid.zip");
        zip(sourceDb, invalidBackup);
        var before = files(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), invalidBackup, sha(invalidBackup)))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Invalid account in catalogue plan");
        assertThat(files(fixture.data().resolve("db"))).isEqualTo(before);
    }

    @Test
    void fingerprintsDetectChangedDuplicateNullAndBinaryRows() throws Exception {
        Fixture fixture = fixture();
        try (Connection connection = open(fixture.data()); var statement = connection.createStatement()) {
            var original = NormalizedCatalogRecoveryService.fingerprintExistingTables(connection);
            statement.executeUpdate("UPDATE recovery_evolved SET bytes=X'FF' WHERE id=1");
            assertThat(NormalizedCatalogRecoveryService.fingerprintExistingTables(connection)).isNotEqualTo(original);
            statement.executeUpdate("UPDATE recovery_evolved SET bytes=X'0102FF' WHERE id=1");
            assertThat(NormalizedCatalogRecoveryService.fingerprintExistingTables(connection)).isEqualTo(original);
            statement.executeUpdate("INSERT INTO recovery_evolved VALUES (2,NULL,NULL,NULL)");
            assertThat(NormalizedCatalogRecoveryService.fingerprintExistingTables(connection)).isNotEqualTo(original);
            shutdown(connection);
        }
    }

    private void zip(Path directory, Path archive) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(path.getFileName().toString()));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private Fixture fixture() throws Exception {
        Path data = temporary.resolve("data");
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSAccountPlan plan = new SSAccountPlan("Recovery template");
            plan.setBaseName("Original catalogue base");
            plan.getAccounts().add(new SSAccount(3010));
            SSAccount bank = new SSAccount(1930);
            bank.setDescription("Original bank");
            bank.setProjectRequired(true);
            bank.setActive(false);
            plan.getAccounts().add(bank);
            runtime.database().addAccountPlan(plan);
            runtime.database().addAccountPlan(new SSAccountPlan("Empty recovery template"));
            SSNewCompany company = new SSNewCompany();
            company.setName("Preserved company");
            runtime.database().addCompany(company);
            runtime.selectCompany(company.getId());
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setLocalFrom(LocalDate.of(2025, 1, 1));
            year.setLocalTo(LocalDate.of(2025, 12, 31));
            year.setAccountPlan(new SSAccountPlan(plan, true));
            runtime.database().addAccountingYear(year);
        }
        var migration = new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            var company = runtime.getCompanies().stream()
                    .filter(value -> "Preserved company".equals(value.getName())).findFirst().orElseThrow();
            runtime.selectCompany(company.getId());
            runtime.selectYear(company, runtime.getYears(company).get(0).getId());
            se.swedsoft.bookkeeping.data.SSVoucher voucher = new se.swedsoft.bookkeeping.data.SSVoucher();
            voucher.setNumber(1);
            voucher.setLocalDate(LocalDate.of(2025, 3, 4));
            voucher.setDescription("Entered after normalization");
            se.swedsoft.bookkeeping.data.SSVoucherRow debit = new se.swedsoft.bookkeeping.data.SSVoucherRow();
            debit.setAccount(new SSAccount(1930));
            debit.setDebet(new java.math.BigDecimal("1234.50"));
            se.swedsoft.bookkeeping.data.SSVoucherRow credit = new se.swedsoft.bookkeeping.data.SSVoucherRow();
            credit.setAccount(new SSAccount(3010));
            credit.setCredit(new java.math.BigDecimal("1234.50"));
            voucher.setVoucherRows(new java.util.ArrayList<>(java.util.List.of(debit, credit)));
            runtime.addVoucher(voucher);
        }
        Path backup = temporary.resolve("trusted-legacy.zip");
        Files.copy(migration.activation().rollbackArchive(), backup);
        try (Connection connection = open(data); var statement = connection.createStatement()) {
            // Simulate an older normalized database that has since gained new typed data.
            statement.executeUpdate("DROP TABLE account_plan_catalog_account");
            statement.executeUpdate("DROP TABLE account_plan_catalog");
            statement.executeUpdate("DELETE FROM bokfri_schema_history WHERE version=19");
            statement.executeUpdate("CREATE TABLE recovery_evolved (id INTEGER PRIMARY KEY, "
                    + "text_value VARCHAR(255), amount DECIMAL(20,4), bytes VARBINARY(20))");
            statement.executeUpdate("INSERT INTO recovery_evolved VALUES "
                    + "(1,'Created after normalization',91234.5678,X'0102FF')");
            shutdown(connection);
        }
        return new Fixture(data, backup);
    }

    private Result cli(Fixture fixture, boolean commit) throws Exception {
        Path config = temporary.resolve("selection.config");
        if (!Files.exists(config)) {
            se.swedsoft.bookkeeping.data.system.SSDBConfig.setCompanyId(config.toFile(), 777);
            se.swedsoft.bookkeeping.data.system.SSDBConfig.setYearId(config.toFile(), 777, 999);
        }
        byte[] selection = Files.readAllBytes(config);
        var args = new java.util.ArrayList<>(java.util.List.of("--data-dir", fixture.data().toString(),
                "--config", config.toString(), "--format", "json", "database", "recover-account-plans", "--trusted-legacy-backup",
                fixture.backup().toString(), "--sha256", sha(fixture.backup())));
        if (commit) args.add("--commit");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = BokfriCli.execute(args.toArray(String[]::new), new PrintWriter(out), new PrintWriter(err));
        assertThat(Files.readAllBytes(config)).isEqualTo(selection);
        return new Result(code, out.toString(), err.toString());
    }

    private NormalizedCatalogRecoveryService service() {
        return new NormalizedCatalogRecoveryService(Clock.systemUTC());
    }

    private Connection open(Path data) throws Exception {
        return DriverManager.getConnection("jdbc:hsqldb:file:" + data.resolve("db/JFSDB")
                + ";ifexists=true;shutdown=true", "sa", "");
    }

    private void shutdown(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN SCRIPT"); }
    }

    private String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private Map<String, String> files(Path directory) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(path.getFileName().toString(), sha(path));
            }
        }
        return files;
    }

    private record Fixture(Path data, Path backup) {}
    private record Result(int code, String output, String error) {}
}
