package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSPeriodicInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodicInvoiceSnapshotPreservationTest {
    @TempDir Path directory;

    @Test
    void preservesAllNestedSnapshotsRawAddedEntriesAndNanosecondEditMetadataAfterReopen() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("periodic");
        SemanticFingerprint expected;
        try (Connection legacy = legacy(); Connection normalized = DriverManager.getConnection(url, "sa", "");
                LegacyState ignored = new LegacyState()) {
            initialize(normalized, 20);
            seed(legacy, 11, 71, fixture(true));
            seed(legacy, 12, 72, fixture(false));
            var result = new PeriodicInvoiceStagingConverter().convert(legacy, normalized);
            expected = result.sourceFingerprint();
            assertThat(expected.differences(result.destinationFingerprint())).isEmpty();
            assertThat(expected.domains()).containsKeys("periodic-nested-periodic_invoice_address",
                    "periodic-nested-periodic_invoice_default_account", "periodic-nested-periodic_invoice_voucher_row",
                    "periodic-nested-periodic_invoice_added_state", "periodic-nested-periodic_invoice_snapshot");
            assertThat(count(normalized, "periodic_invoice_snapshot")).isEqualTo(6);
            assertThat(count(normalized, "periodic_invoice_added_state")).isEqualTo(8);
            assertThat(count(normalized, "periodic_invoice_voucher")).isEqualTo(3);
            try (var statement = normalized.createStatement(); var rows = statement.executeQuery(
                    "SELECT edited_at,crossed,added FROM periodic_invoice_voucher_row WHERE edited_at IS NOT NULL")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(
                        LegacySwedishTimeResolver.resolve(edited()).instant());
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getBoolean(3)).isTrue();
            }
            try (var statement = normalized.createStatement(); var rows = statement.executeQuery(
                    "SELECT account_number FROM periodic_invoice_default_account WHERE account_type='CustomerClaim'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
            }
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        try (Connection normalized = DriverManager.getConnection(url, "sa", "")) {
            assertThat(expected.differences(new PeriodicInvoiceStagingConverter()
                    .fingerprintNormalized(normalized))).isEmpty();
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void everyNewDomainDetectsTamperingBeyondTheOldFlatProjection() throws Exception {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("address", "UPDATE periodic_invoice_address SET name='Changed'");
        changes.put("account", "UPDATE periodic_invoice_default_account SET account_number=9999");
        changes.put("voucher", "UPDATE periodic_invoice_voucher SET description='Changed'");
        changes.put("row", "UPDATE periodic_invoice_voucher_row SET edited_signature='Changed'");
        changes.put("added", "UPDATE periodic_invoice_added_state SET added=FALSE WHERE invoice_number=999");
        changes.put("presence", "UPDATE periodic_invoice_snapshot SET present=TRUE WHERE present=FALSE");
        for (var change : changes.entrySet()) {
            try (Connection legacy = legacy(); Connection normalized = database(20);
                    LegacyState ignored = new LegacyState()) {
                seed(legacy, 11, 71, fixture(true));
                seed(legacy, 12, 72, fixture(false));
                var converter = new PeriodicInvoiceStagingConverter();
                var expected = converter.convert(legacy, normalized).sourceFingerprint();
                try (var statement = normalized.createStatement()) { statement.executeUpdate(change.getValue()); }
                assertThat(expected.differences(converter.fingerprintNormalized(normalized)))
                        .as(change.getKey()).isNotEmpty();
            }
        }
    }

    @Test
    void nullAndEmptyTemplatesAndVouchersRemainDistinctWithoutBusinessDefaults() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = database(20);
                LegacyState ignored = new LegacyState()) {
            SSPeriodicInvoice absent = periodic();
            SSPeriodicInvoice empty = periodic();
            empty.setTemplate(SSInvoice.forPersistence());
            SSPeriodicInvoice voucher = periodic();
            SSInvoice invoice = SSInvoice.forPersistence();
            invoice.setVoucher(new SSVoucher(0, null));
            voucher.setTemplate(invoice);
            seed(legacy, 11, 71, absent);
            seed(legacy, 12, 71, empty);
            seed(legacy, 13, 71, voucher);
            new PeriodicInvoiceStagingConverter().convert(legacy, normalized);
            assertThat(count(normalized, "periodic_invoice_snapshot")).isEqualTo(3);
            assertThat(count(normalized, "periodic_invoice_address")).isZero();
            assertThat(count(normalized, "periodic_invoice_default_account")).isZero();
            assertThat(count(normalized, "periodic_invoice_voucher")).isEqualTo(1);
            try (var statement = normalized.createStatement(); var rows = statement.executeQuery(
                    "SELECT p.legacy_id,s.present FROM periodic_invoice_snapshot s"
                            + " JOIN periodic_invoice p ON p.id=s.periodic_invoice_id ORDER BY p.legacy_id")) {
                rows.next(); assertThat(rows.getBoolean(2)).isFalse();
                rows.next(); assertThat(rows.getBoolean(2)).isTrue();
                rows.next(); assertThat(rows.getBoolean(2)).isTrue();
            }
            assertThat(absent.getStoredTemplate()).isNull();
            assertThat(empty.getStoredTemplate().getStoredVoucher()).isNull();
        }
    }

    @Test
    void lateNestedFailureRollsBackBothFlatAndNestedMigrationWithoutTouchingLegacySource() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = database(20);
                LegacyState ignored = new LegacyState()) {
            SSPeriodicInvoice periodic = fixture(true);
            periodic.getStoredTemplate().getStoredVoucher().getRows().get(0).setEditedSignature("X".repeat(256));
            seed(legacy, 11, 71, periodic);
            assertThatThrownBy(() -> new PeriodicInvoiceStagingConverter().convert(legacy, normalized))
                    .isInstanceOf(SQLException.class);
            for (String table : List.of("periodic_invoice", "periodic_invoice_instance",
                    "periodic_invoice_template_row",
                    "periodic_invoice_instance_row")) assertThat(count(normalized, table)).as(table).isZero();
            for (var table : PeriodicInvoiceSnapshots.Table.values()) {
                assertThat(count(normalized, table.name)).isZero();
            }
            assertThat(count(legacy, "tbl_periodicinvoice")).isEqualTo(1);
            assertThat(count(normalized, "company")).isEqualTo(2);
        }
    }

    @Test
    void oldV19ProjectionsFailClosedAndV20DoesNotInventOrBackfillMissingSnapshots() throws Exception {
        try (Connection normalized = database(19)) {
            try (var statement = normalized.createStatement()) {
                statement.executeUpdate("INSERT INTO periodic_invoice (legacy_id,company_id,append_period,"
                        + "append_information,template_tax_free,template_eu_sale_commodity,"
                        + "template_eu_sale_third_party_commodity,template_printed,template_entered,"
                        + "template_reminder_count,template_interest_invoiced,template_stock_influencing)"
                        + " SELECT 11,id,FALSE,FALSE,FALSE,FALSE,FALSE,FALSE,FALSE,0,FALSE,FALSE"
                        + " FROM company WHERE legacy_id=71");
            }
            normalized.commit();
            assertRecoveryRequired(normalized);
            new SchemaMigrationRunner(Clock.systemUTC()).migrate(normalized, NormalizedSchemaMigrations.load());
            assertThat(count(normalized, "periodic_invoice_snapshot")).isZero();
            assertThat(count(normalized, "periodic_invoice")).isEqualTo(1);
            assertRecoveryRequired(normalized);
        }
    }

    @Test
    void missingMarkersPartialSchemaAndMissingHistoryCannotMasqueradeAsComplete() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = database(20);
                LegacyState ignored = new LegacyState()) {
            seed(legacy, 11, 71, fixture(true));
            new PeriodicInvoiceStagingConverter().convert(legacy, normalized);
            try (var statement = normalized.createStatement()) {
                statement.executeUpdate("DELETE FROM periodic_invoice_snapshot WHERE invoice_index=1");
            }
            assertRecoveryRequired(normalized);
        }
        try (Connection normalized = database(20)) {
            try (var statement = normalized.createStatement()) {
                statement.execute("DROP TABLE periodic_invoice_added_state");
            }
            assertRecoveryRequired(normalized);
        }
        try (Connection normalized = database(20)) {
            try (var statement = normalized.createStatement()) {
                statement.executeUpdate("DELETE FROM bokfri_schema_history WHERE version=20");
            }
            assertRecoveryRequired(normalized);
        }
    }

    @Test
    void foreignKeysAndPresenceConstraintsRejectInvalidSnapshotGraphs() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = database(20);
                LegacyState ignored = new LegacyState()) {
            seed(legacy, 11, 71, fixture(true));
            new PeriodicInvoiceStagingConverter().convert(legacy, normalized);
            try (var statement = normalized.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO periodic_invoice_snapshot VALUES (999,-1,TRUE)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE periodic_invoice_snapshot SET present=FALSE WHERE invoice_index=0"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE periodic_invoice_snapshot SET invoice_index=-2 WHERE invoice_index=1"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void unsupportedCorrectionGraphsAndInvoiceSubtypesAreRejectedRatherThanFlattened() throws Exception {
        for (boolean correction : List.of(false, true)) {
            try (Connection legacy = legacy(); Connection normalized = database(20);
                    LegacyState ignored = new LegacyState()) {
                SSPeriodicInvoice value = fixture(true);
                if (correction) value.getStoredTemplate().getStoredVoucher().setCorrects(new SSVoucher(42, null));
                else value.setTemplate(SSCreditInvoice.forPersistence());
                seed(legacy, 11, 71, value);
                assertThatThrownBy(() -> new PeriodicInvoiceStagingConverter().convert(legacy, normalized))
                        .isInstanceOf(SQLException.class).hasMessageContaining("PERIODIC_SNAPSHOT_UNSUPPORTED");
                assertThat(count(normalized, "periodic_invoice")).isZero();
                assertThat(count(legacy, "tbl_periodicinvoice")).isEqualTo(1);
            }
        }
    }

    @Test
    void combinedFingerprintUsesTheSameAggregateEncodingAsTheStandardBuilder() {
        SemanticFingerprintBuilder first = new SemanticFingerprintBuilder();
        first.domain("a").startRecord("1").writeString("one").finish();
        SemanticFingerprintBuilder second = new SemanticFingerprintBuilder();
        second.domain("b").startRecord("2").writeString("two").finish();
        SemanticFingerprintBuilder both = new SemanticFingerprintBuilder();
        both.domain("b").startRecord("2").writeString("two").finish();
        both.domain("a").startRecord("1").writeString("one").finish();
        assertThat(PeriodicInvoiceSnapshots.combine(first.finish(), second.finish())).isEqualTo(both.finish());
    }

    private static LocalDateTime edited() {
        return LocalDateTime.of(2026, 3, 29, 2, 30, 0, 123456789);
    }

    private static SSPeriodicInvoice periodic() {
        SSPeriodicInvoice periodic = new SSPeriodicInvoice();
        periodic.setNumber(1);
        periodic.setLocalDate(null);
        periodic.setLocalPeriodStart(null);
        periodic.setLocalPeriodEnd(null);
        return periodic;
    }

    private static SSPeriodicInvoice fixture(boolean template) {
        SSPeriodicInvoice periodic = periodic();
        if (template) {
            SSInvoice invoice = SSInvoice.forPersistence();
            SSAddress address = new SSAddress(); address.setName("Stored invoice address");
            invoice.setInvoiceAddress(address);
            invoice.setDeliveryAddress(new SSAddress());
            invoice.getDefaultAccounts().put(SSDefaultAccount.Sales, 3010);
            invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, null);
            SSVoucher voucher = new SSVoucher(41, LocalDate.of(2026, 3, 29));
            voucher.setDescription("Stored periodic voucher");
            SSVoucherRow row = new SSVoucherRow();
            row.setAccountNr(1930); row.setProjectNr("P1"); row.setResultUnitNr("U1");
            row.setDebet(new BigDecimal("123.4567")); row.setLocalEditedDate(edited());
            row.setEditedSignature("Author"); row.setCrossed(true); row.setAdded(true);
            voucher.getRows().add(row);
            voucher.getRows().add(new SSVoucherRow());
            invoice.setVoucher(voucher);
            periodic.setTemplate(invoice);
        }
        SSInvoice first = SSInvoice.forPersistence();
        first.setNumber(5);
        SSAddress address = new SSAddress(); address.setName("Instance address"); first.setInvoiceAddress(address);
        first.getDefaultAccounts().put(SSDefaultAccount.Sales, 3020);
        first.setVoucher(new SSVoucher(0, null));
        SSInvoice duplicate = SSInvoice.forPersistence(); duplicate.setNumber(5);
        periodic.getInvoices().add(first);
        periodic.getInvoices().add(duplicate);
        periodic.getStoredAdded().put(null, null);
        periodic.getStoredAdded().put(5, null);
        periodic.getStoredAdded().put(999, true);
        periodic.getStoredAdded().put(-3, false);
        return periodic;
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:periodic_preservation_" + UUID.randomUUID(), "sa", "");
    }

    private static Connection legacy() throws Exception {
        Connection connection = memory();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tbl_periodicinvoice"
                    + " (id INTEGER,companyid INTEGER,number INTEGER,periodicinvoice OTHER)");
        }
        return connection;
    }

    private static Connection database(int version) throws Exception {
        Connection connection = memory(); initialize(connection, version); return connection;
    }

    private static void initialize(Connection connection, int version) throws Exception {
        connection.setAutoCommit(false);
        new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection,
                NormalizedSchemaMigrations.load().subList(0, version));
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO company (legacy_id,name,rounding_off) VALUES (71,'Selected',FALSE)");
            statement.executeUpdate("INSERT INTO company (legacy_id,name,rounding_off) VALUES (72,'Other',FALSE)");
        }
        connection.commit();
    }

    private static void seed(Connection legacy, int id, int company, SSPeriodicInvoice value) throws SQLException {
        try (var insert = legacy.prepareStatement("INSERT INTO tbl_periodicinvoice VALUES (?,?,?,?)")) {
            insert.setInt(1, id); insert.setInt(2, company); insert.setInt(3, value.getNumber());
            insert.setObject(4, value); insert.executeUpdate();
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }

    private static void assertRecoveryRequired(Connection connection) {
        assertThatThrownBy(() -> new PeriodicInvoiceStagingConverter().fingerprintNormalized(connection))
                .isInstanceOf(SQLException.class).hasMessageContaining("PERIODIC_SNAPSHOT_RECOVERY_REQUIRED");
    }

    private static final class LegacyState implements AutoCloseable {
        private final SSDB db = SSDB.getInstance();
        private final Map<Field, Object> saved = new LinkedHashMap<>();

        LegacyState() throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iPeriodicInvoices")) {
                Field field = SSDB.class.getDeclaredField(name);
                field.setAccessible(true);
                saved.put(field, field.get(db));
            }
            set("iPeriodicInvoices", List.of());
            set("iConnection", Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }

        private void set(String name, Object value) throws Exception {
            Field field = SSDB.class.getDeclaredField(name); field.setAccessible(true); field.set(db, value);
        }

        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(db, entry.getValue());
        }
    }
}
