package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.cli.BokfriCli;
import org.fribok.bookkeeping.cli.BokfriRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSPeriodicInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedPeriodicRecoveryServiceTest {
    @TempDir Path temporary;
    private record Fixture(Path data, Path backup, long periodicId, int company) {}

    @Test
    void recoversOnlySurvivingSnapshotsPreservesAllEvolvedTablesAndCreatesPhysicalRollback() throws Exception {
        Fixture fixture = fixture(false);
        Map<String, String> original;
        try (Connection connection = open(fixture)) {
            original = existing(connection);
            assertThat(original).containsKeys("ACCOUNT_PLAN_CATALOG", "VOUCHER", "RECOVERY_EVOLVED");
        }
        var files = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
        String backupSha = sha(fixture.backup());
        var result = service().recover(fixture.data(), fixture.backup(), backupSha.toUpperCase(java.util.Locale.ROOT));
        assertThat(result.periodics()).isEqualTo(1); // A deleted second periodic is deliberately not resurrected.
        assertThat(result.snapshots()).isEqualTo(3);
        assertThat(result.legacyBackupSha256()).isEqualTo(backupSha);
        assertThat(sha(fixture.backup())).isEqualTo(backupSha);
        assertThat(NormalizedCatalogRecoveryService.catalogHashes(result.activation().retainedSource()))
                .isEqualTo(files);
        try (ZipFile zip = new ZipFile(result.activation().rollbackArchive().toFile())) {
            for (var file : files.entrySet()) {
                try (var input = zip.getInputStream(zip.getEntry(file.getKey()))) {
                    assertThat(hash(input.readAllBytes())).as(file.getKey()).isEqualTo(file.getValue());
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            try (Connection connection = open(fixture)) {
                assertThat(existing(connection)).isEqualTo(original);
                assertThat(result.periodicFingerprint().differences(new PeriodicInvoiceStagingConverter()
                        .fingerprintNormalized(connection))).isEmpty();
                assertThat(count(connection, "periodic_invoice")).isEqualTo(1);
                assertThat(count(connection, "periodic_invoice_snapshot")).isEqualTo(3);
                assertThat(count(connection, "periodic_invoice_added_state")).isEqualTo(3);
                assertThat(count(connection, "periodic_invoice_address")).isEqualTo(2);
                assertThat(count(connection, "periodic_invoice_voucher")).isEqualTo(2);
                assertThat(count(connection, "periodic_invoice_voucher_row")).isEqualTo(1);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT SUM(debit) FROM voucher_row")) {
                    rows.next(); assertThat(rows.getBigDecimal(1)).isGreaterThanOrEqualTo(new BigDecimal("1234.50"));
                }
            }
        }
    }

    @Test
    void rejectsChangedMissingAndChangedInstanceProjectionsWithoutChangingActiveFiles() throws Exception {
        for (String sql : List.of("UPDATE periodic_invoice SET description='Edited after normalization'",
                "UPDATE periodic_invoice SET legacy_id=99999",
                "UPDATE periodic_invoice_instance SET invoice_number=99 WHERE row_number=0")) {
            Fixture fixture = fixture(false);
            try (Connection connection = open(fixture); var statement = connection.createStatement()) {
                statement.executeUpdate(sql); connection.commit();
            }
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("projection differs");
            assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
        }
    }

    @Test
    void rejectsExistingV20AndPartialSnapshotTablesWithoutOverwritingAnything() throws Exception {
        for (boolean complete : List.of(false, true)) {
            Fixture fixture = fixture(complete);
            if (!complete) try (Connection connection = open(fixture); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE periodic_invoice_snapshot (id INTEGER)"); connection.commit();
            }
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                    .isInstanceOfAny(java.io.IOException.class, java.sql.SQLException.class)
                    .hasMessageContaining(complete ? "exactly schema V19" : "partial V20");
            assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
        }
    }

    @Test
    void rejectsBadHashesLockedAndDirtyCataloguesBeforeCreatingStaging() throws Exception {
        Fixture fixture = fixture(false);
        for (String expected : List.of("invalid", "0".repeat(64))) {
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), expected))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("SHA-256");
            assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
            assertThat(MigrationRecoveryInspector.inspect(fixture.data()).stagingDirectories()).isEmpty();
        }
        for (String suffix : List.of("lck", "log")) {
            Path marker = fixture.data().resolve("db/JFSDB." + suffix);
            Files.writeString(marker, "do not touch");
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("cleanly shut down");
            assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
            Files.delete(marker);
        }
    }

    @Test
    void rejectsUnsafeArchivesAndNormalizedBackupsWithoutTouchingTheCurrentDatabase() throws Exception {
        for (boolean unsafe : List.of(false, true)) {
            Fixture fixture = fixture(false);
            Path archive = fixture.data().getParent().resolve("invalid.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                if (unsafe) {
                    zip.putNextEntry(new ZipEntry("../escape")); zip.write(new byte[]{1}); zip.closeEntry();
                } else {
                    try (var files = Files.list(fixture.data().resolve("db"))) {
                        for (Path file : files.filter(path -> path.getFileName().toString()
                                .startsWith("JFSDB.")).toList()) {
                            zip.putNextEntry(new ZipEntry("db/" + file.getFileName()));
                            Files.copy(file, zip); zip.closeEntry();
                        }
                    }
                }
            }
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            assertThatThrownBy(() -> service().recover(fixture.data(), archive, sha(archive)))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining(unsafe ? "Unexpected legacy backup entry" : "supported legacy storage");
            assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
        }
    }

    @Test
    void skipsDeletedUnsupportedObjectsButRejectsSelectedGraphsAndLateNestedFailures() throws Exception {
        for (String change : List.of("deleted", "correction", "oversized")) {
            Fixture fixture = fixture(false);
            Path altered = alteredBackup(fixture, change);
            var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
            if (change.equals("deleted")) {
                var result = service().recover(fixture.data(), altered, sha(altered));
                assertThat(result.periodics()).isEqualTo(1);
                assertThat(result.snapshots()).isEqualTo(3);
            } else {
                assertThatThrownBy(() -> service().recover(fixture.data(), altered, sha(altered)))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db")))
                        .isEqualTo(before);
                if (change.equals("oversized")) {
                    Path staged = MigrationRecoveryInspector.inspect(fixture.data()).stagingDirectories().get(0);
                    try (Connection connection = NormalizedCatalogRecoveryService.open(staged.resolve("db"), true)) {
                        assertThat(count(connection, "periodic_invoice_snapshot")).isZero();
                        assertThat(count(connection, "periodic_invoice")).isEqualTo(1);
                    }
                }
            }
        }
    }

    @Test
    void unresolvedStagingIsRetainedAndNeverAutomaticallyRetried() throws Exception {
        Fixture fixture = fixture(false);
        Path staged = Files.createTempDirectory(fixture.data(), MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX);
        Path note = staged.resolve("operator-note"); Files.writeString(note, "Preserve forensic state");
        var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Unresolved migration state");
        assertThat(Files.readString(note)).isEqualTo("Preserve forensic state");
        assertThat(MigrationRecoveryInspector.inspect(fixture.data()).stagingDirectories()).containsExactly(staged);
        assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
    }

    @Test
    void rejectsUnfingerprintableActiveSqlTypesWithoutDeserializingThem() throws Exception {
        Fixture fixture = fixture(false);
        try (Connection connection = open(fixture); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unsupported_state (object OTHER)"); connection.commit();
        }
        var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
        assertThatThrownBy(() -> service().recover(fixture.data(), fixture.backup(), sha(fixture.backup())))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Cannot safely fingerprint SQL type");
        assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
    }

    @Test
    void emptyCurrentSelectionUpgradesWithoutRestoringDeletedSourcePeriodics() throws Exception {
        Fixture fixture = fixture(false);
        try (Connection connection = open(fixture); var statement = connection.createStatement()) {
            for (String table : List.of("periodic_invoice_instance_row", "periodic_invoice_instance",
                    "periodic_invoice_template_row", "periodic_invoice")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            connection.commit();
        }
        var result = service().recover(fixture.data(), fixture.backup(), sha(fixture.backup()));
        assertThat(result.periodics()).isZero();
        assertThat(result.snapshots()).isZero();
        try (Connection connection = open(fixture)) {
            assertThat(count(connection, "periodic_invoice")).isZero();
            assertThat(count(connection, "periodic_invoice_snapshot")).isZero();
            assertThat(result.periodicFingerprint().differences(new PeriodicInvoiceStagingConverter()
                    .fingerprintNormalized(connection))).isEmpty();
        }
    }

    @Test
    void cliRequiresCommitAndPreservesTheSavedGuiSelectionOnSuccess() throws Exception {
        Fixture fixture = fixture(false);
        var before = NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"));
        Result refused = cli(fixture, false);
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.error()).contains("COMMIT_REQUIRED");
        assertThat(NormalizedCatalogRecoveryService.catalogHashes(fixture.data().resolve("db"))).isEqualTo(before);
        assertThat(MigrationRecoveryInspector.inspect(fixture.data()).stagingDirectories()).isEmpty();
        Result recovered = cli(fixture, true);
        assertThat(recovered.code()).as(recovered.error()).isZero();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(recovered.output());
        assertThat(json.path("recovered").asBoolean()).isTrue();
        assertThat(json.path("periodics").asLong()).isEqualTo(1);
        assertThat(json.path("snapshots").asLong()).isEqualTo(3);
        assertThat(Files.isRegularFile(Path.of(json.path("backup").asText()))).isTrue();
    }

    private Fixture fixture(boolean keepV20) throws Exception {
        Path root = Files.createTempDirectory(temporary, "fixture-");
        Path data = root.resolve("data");
        int companyId;
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            SSNewCompany company = new SSNewCompany(); company.setName("Preserved company");
            runtime.database().addCompany(company); companyId = company.getId(); runtime.selectCompany(companyId);
            SSAccountPlan plan = new SSAccountPlan(); plan.getAccounts().add(new SSAccount(1930));
            plan.getAccounts().add(new SSAccount(3010));
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setLocalFrom(LocalDate.of(2025, 1, 1)); year.setLocalTo(LocalDate.of(2025, 12, 31));
            year.setAccountPlan(plan); runtime.database().addAccountingYear(year);
            for (int index = 0; index < 2; index++) {
                SSPeriodicInvoice periodic = new SSPeriodicInvoice(); periodic.setCount(2);
                SSInvoice template = SSInvoice.forPersistence();
                SSAddress address = new SSAddress(); address.setName("Original snapshot");
                template.setInvoiceAddress(address); template.setDeliveryAddress(new SSAddress());
                template.getDefaultAccounts().put(SSDefaultAccount.Sales, 3010);
                template.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, null);
                SSVoucher voucher = new SSVoucher(42, null);
                SSVoucherRow row = new SSVoucherRow(); row.setAccountNr(1930); row.setDebet(new BigDecimal("37.50"));
                voucher.getRows().add(row); template.setVoucher(voucher); periodic.setTemplate(template);
                SSInvoice first = SSInvoice.forPersistence();
                first.setNumber(7); first.setVoucher(new SSVoucher(0, null));
                SSInvoice duplicate = SSInvoice.forPersistence(); duplicate.setNumber(7);
                periodic.getInvoices().add(first); periodic.getInvoices().add(duplicate);
                periodic.getStoredAdded().put(7, true); periodic.getStoredAdded().put(999, false);
                periodic.getStoredAdded().put(null, null);
                runtime.database().addPeriodicInvoice(periodic);
            }
        }
        var migration = new NormalizedMigrationService(Clock.systemUTC()).migrate(data);
        try (BokfriRuntime runtime = BokfriRuntime.open(data)) {
            var company = runtime.getCompanies().stream().filter(value -> value.getId() == companyId)
                    .findFirst().orElseThrow();
            runtime.selectCompany(companyId); runtime.selectYear(company, runtime.getYears(company).get(0).getId());
            SSVoucher voucher = new SSVoucher(0, LocalDate.of(2025, 3, 4));
            voucher.setDescription("Entered after normalization");
            SSVoucherRow debit = new SSVoucherRow();
            debit.setAccountNr(1930); debit.setDebet(new BigDecimal("1234.50"));
            SSVoucherRow credit = new SSVoucherRow();
            credit.setAccountNr(3010); credit.setCredit(new BigDecimal("1234.50"));
            voucher.getRows().add(debit); voucher.getRows().add(credit); runtime.addVoucher(voucher);
        }
        Path backup = root.resolve("trusted-legacy.zip"); Files.copy(migration.activation().rollbackArchive(), backup);
        long selected;
        try (Connection connection = NormalizedCatalogRecoveryService.open(data.resolve("db"), false);
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT p.id FROM periodic_invoice p"
                    + " JOIN company c ON c.id=p.company_id"
                    + " WHERE c.legacy_id=" + companyId + " ORDER BY p.id")) {
                rows.next(); selected = rows.getLong(1);
            }
            if (!keepV20) {
                for (String table : List.of("periodic_invoice_added_state", "periodic_invoice_voucher_row",
                        "periodic_invoice_voucher", "periodic_invoice_default_account", "periodic_invoice_address",
                        "periodic_invoice_snapshot")) statement.executeUpdate("DROP TABLE " + table);
                statement.executeUpdate("DELETE FROM bokfri_schema_history WHERE version=20");
                for (String table : List.of("periodic_invoice_instance_row", "periodic_invoice_instance",
                        "periodic_invoice_template_row")) statement.executeUpdate("DELETE FROM " + table
                        + " WHERE periodic_invoice_id<>" + selected);
                statement.executeUpdate("DELETE FROM periodic_invoice WHERE id<>" + selected);
            }
            statement.executeUpdate("CREATE TABLE recovery_evolved (id INTEGER PRIMARY KEY, text_value VARCHAR(255),"
                    + " amount DECIMAL(20,4), bytes VARBINARY(20))");
            statement.executeUpdate("INSERT INTO recovery_evolved VALUES "
                    + "(1,'Post-migration state',91234.5678,X'0102FF')");
            connection.commit();
        }
        return new Fixture(data, backup, selected, companyId);
    }

    private Path alteredBackup(Fixture fixture, String change) throws Exception {
        int selected;
        try (Connection connection = open(fixture); var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT legacy_id FROM periodic_invoice WHERE id=" + fixture.periodicId())) {
            rows.next(); selected = rows.getInt(1);
        }
        Path source = Files.createTempDirectory(fixture.data().getParent(), "altered-source-");
        NormalizedCatalogRecoveryService.extractCatalog(fixture.backup(), source.resolve("db"));
        try (Connection connection = NormalizedCatalogRecoveryService.open(source.resolve("db"), false)) {
            Map<Integer, SSPeriodicInvoice> records = new java.util.LinkedHashMap<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT id,periodicinvoice FROM tbl_periodicinvoice")) {
                while (rows.next()) records.put(rows.getInt(1), (SSPeriodicInvoice) rows.getObject(2));
            }
            for (var entry : records.entrySet()) {
                boolean modify = change.equals("deleted") ? entry.getKey() != selected : entry.getKey() == selected;
                if (!modify) continue;
                var voucher = entry.getValue().getStoredTemplate().getStoredVoucher();
                if (change.equals("oversized")) voucher.getRows().get(0).setEditedSignature("X".repeat(256));
                else voucher.setCorrects(new SSVoucher(88, null));
                try (var update = connection.prepareStatement(
                        "UPDATE tbl_periodicinvoice SET periodicinvoice=? WHERE id=?")) {
                    update.setObject(1, entry.getValue()); update.setInt(2, entry.getKey()); update.executeUpdate();
                }
            }
            connection.commit();
        }
        Path archive = fixture.data().getParent().resolve("altered-trusted.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
                var files = Files.list(source.resolve("db"))) {
            for (Path file : files.filter(path -> path.getFileName().toString()
                                .startsWith("JFSDB.")).toList()) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString())); Files.copy(file, zip); zip.closeEntry();
            }
        }
        return archive;
    }

    private static Connection open(Fixture fixture) throws Exception {
        return NormalizedCatalogRecoveryService.open(fixture.data().resolve("db"), false);
    }

    private static Map<String, String> existing(Connection connection) throws Exception {
        Set<String> excluded = Arrays.stream(PeriodicInvoiceSnapshots.Table.values())
                .map(table -> table.name.toUpperCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        return NormalizedCatalogRecoveryService.fingerprintExistingTables(connection, excluded, 19);
    }

    private static long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }

    private NormalizedPeriodicRecoveryService service() {
        return new NormalizedPeriodicRecoveryService(Clock.systemUTC());
    }
    private static String sha(Path file) throws Exception { return NormalizedCatalogRecoveryService.sha256(file); }
    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Result(int code, String output, String error) {}
    private static Result cli(Fixture fixture, boolean commit) throws Exception {
        Path config = fixture.data().getParent().resolve("selection.config");
        if (!Files.exists(config)) {
            se.swedsoft.bookkeeping.data.system.SSDBConfig.setCompanyId(config.toFile(), 777);
            se.swedsoft.bookkeeping.data.system.SSDBConfig.setYearId(config.toFile(), 777, 999);
        }
        byte[] selection = Files.readAllBytes(config);
        var args = new java.util.ArrayList<>(List.of("--data-dir", fixture.data().toString(),
                "--config", config.toString(),
                "--format", "json", "database", "recover-periodic-invoices", "--trusted-legacy-backup",
                fixture.backup().toString(), "--sha256", sha(fixture.backup())));
        if (commit) args.add("--commit");
        StringWriter out = new StringWriter(); StringWriter err = new StringWriter();
        int code = BokfriCli.execute(args.toArray(String[]::new), new PrintWriter(out), new PrintWriter(err));
        assertThat(Files.readAllBytes(config)).isEqualTo(selection);
        return new Result(code, out.toString(), err.toString());
    }
}
