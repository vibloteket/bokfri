package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end migration of the real v1.0.1 database fixture into a durable normalized staging catalog. */
@Tag("integration")
class FullFixtureMigrationTest {
    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @Test
    void migratesTheRealV1DatabaseFixtureWithMatchingFingerprints(@TempDir Path tempDir)
            throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));
        byte[] sourceScript = Files.readAllBytes(data.resolve("db/JFSDB.script"));
        SemanticFingerprint sourceBefore;

        HsqlEngineMigrationService.migrateIfRequired(data);
        assertThat(engineVersion(data)).startsWith("2.5.");

        Class.forName("org.hsqldb.jdbcDriver");
        try (Connection legacy = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            legacy.setAutoCommit(false);
            sourceBefore = new AccountingCoreStagingConverter().fingerprintLegacy(legacy);
            AccountingCoreStagingCatalogService service =
                    new AccountingCoreStagingCatalogService(
                            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));
            AccountingCoreStagingCatalogService.StagingCatalogResult result =
                    service.create(data, legacy);
            legacy.rollback();

            assertThat(result.stagingDirectory()).isDirectory();
            assertThat(result.durableFingerprint())
                    .isEqualTo(result.conversion().destinationFingerprint());
            assertThat(result.companyDetails().destinationFingerprint())
                    .isEqualTo(result.durableCompanyDetailsFingerprint());
            assertThat(result.accountingDimensions().destinationFingerprint())
                    .isEqualTo(result.durableAccountingDimensionsFingerprint());
            assertThat(result.accountingTemplates().destinationFingerprint())
                    .isEqualTo(result.durableAccountingTemplatesFingerprint());
            assertThat(result.customers().destinationFingerprint())
                    .isEqualTo(result.durableCustomerRegisterFingerprint());
            assertThat(result.suppliers().destinationFingerprint())
                    .isEqualTo(result.durableSupplierRegisterFingerprint());
            assertThat(result.products().destinationFingerprint())
                    .isEqualTo(result.durableProductRegisterFingerprint());
            assertThat(result.customerInvoices().destinationFingerprint())
                    .isEqualTo(result.durableCustomerInvoiceFingerprint());
            assertThat(result.customerCreditInvoices().destinationFingerprint())
                    .isEqualTo(result.durableCustomerCreditInvoiceFingerprint());
            assertThat(result.periodicInvoices().destinationFingerprint())
                    .isEqualTo(result.durablePeriodicInvoiceFingerprint());
            assertThat(result.payments().destinationFingerprint())
                    .isEqualTo(result.durablePaymentFingerprint());
            assertThat(result.supplierInvoices().destinationFingerprint())
                    .isEqualTo(result.durableSupplierInvoiceFingerprint());
            assertThat(result.supplierCreditInvoices().destinationFingerprint())
                    .isEqualTo(result.durableSupplierCreditInvoiceFingerprint());
            assertThat(result.saleDocuments().destinationFingerprint())
                    .isEqualTo(result.durableSaleDocumentFingerprint());
            assertThat(result.purchaseOrders().destinationFingerprint())
                    .isEqualTo(result.durablePurchaseOrderFingerprint());
            assertThat(result.stockDocuments().destinationFingerprint())
                    .isEqualTo(result.durableStockDocumentFingerprint());
            assertThat(result.ownReports().destinationFingerprint())
                    .isEqualTo(result.durableOwnReportFingerprint());

            assertThat(result.conversion().companies()).isGreaterThanOrEqualTo(1);
            assertThat(result.conversion().years()).isGreaterThanOrEqualTo(1);
            assertThat(result.conversion().accounts()).isGreaterThanOrEqualTo(1);
        }
        // Reopen the source and prove its accounting data fingerprint is unchanged.
        try (Connection sourceAgain = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            sourceAgain.setReadOnly(true);
            SemanticFingerprint sourceAfter = new AccountingCoreStagingConverter()
                    .fingerprintLegacy(sourceAgain);
            try (var statement = sourceAgain.createStatement()) { statement.execute("SHUTDOWN"); }
            assertThat(sourceAfter).isEqualTo(sourceBefore);
        }
    }

    private static String engineVersion(Path data) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(data.resolve("db/JFSDB.properties"))) {
            properties.load(input);
        }
        return properties.getProperty("version");
    }

    private static void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = FullFixtureMigrationTest.class.getResourceAsStream(FIXTURE)) {
            if (input == null) throw new IOException("Missing compatibility fixture " + FIXTURE);
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
