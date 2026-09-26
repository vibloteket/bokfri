package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

/** Creates and durably verifies a normalized accounting-core staging catalog without activation. */
public final class AccountingCoreStagingCatalogService {
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public AccountingCoreStagingCatalogService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Converts an already-open legacy source into a new file-backed staging catalog.
     *
     * <p>The source connection is never committed, rolled back, shut down, or closed here. The
     * returned staging directory remains separate from {@code data/db}; no activation is attempted.
     */
    public StagingCatalogResult create(Path dataDirectory, Connection legacy)
            throws IOException, SQLException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(legacy, "legacy");
        Path data = dataDirectory.toAbsolutePath().normalize();
        MigrationRecoveryInspector.requireClean(data);
        Files.createDirectories(data);
        String suffix = DIRECTORY_TIME.format(clock.instant());
        Path staging = uniqueStagingDirectory(data, suffix);
        Path databaseDirectory = staging.resolve("db");
        Files.createDirectories(databaseDirectory);
        Path database = databaseDirectory.resolve("JFSDB");
        String url = "jdbc:hsqldb:file:" + database;
        boolean success = false;
        try {
            try {
                Class.forName("org.hsqldb.jdbcDriver");
            } catch (ClassNotFoundException exception) {
                throw new IOException("HSQLDB driver is unavailable", exception);
            }
            AccountingCoreStagingConverter.ConversionResult conversion;
            CompanyDetailsStagingConverter.ConversionResult companyDetails;
            AccountingDimensionsStagingConverter.ConversionResult accountingDimensions;
            AccountingTemplatesStagingConverter.ConversionResult accountingTemplates;
            CustomerRegisterStagingConverter.ConversionResult customers;
            SupplierRegisterStagingConverter.ConversionResult suppliers;
            ProductRegisterStagingConverter.ConversionResult products;
            CustomerInvoiceStagingConverter.ConversionResult customerInvoices;
            CustomerCreditInvoiceStagingConverter.ConversionResult customerCreditInvoices;
            PeriodicInvoiceStagingConverter.ConversionResult periodicInvoices;
            PaymentStagingConverter.ConversionResult payments;
            SupplierInvoiceStagingConverter.ConversionResult supplierInvoices;
            SupplierCreditInvoiceStagingConverter.ConversionResult supplierCreditInvoices;
            SaleDocumentStagingConverter.ConversionResult saleDocuments;
            PurchaseOrderStagingConverter.ConversionResult purchaseOrders;
            StockDocumentStagingConverter.ConversionResult stockDocuments;
            try (Connection normalized = DriverManager.getConnection(url, "sa", "")) {
                try {
                    normalized.setAutoCommit(false);
                    new SchemaMigrationRunner(clock).migrate(
                            normalized, NormalizedSchemaMigrations.load());
                    conversion = new AccountingCoreStagingConverter().convert(legacy, normalized);
                    companyDetails = new CompanyDetailsStagingConverter().convert(legacy, normalized);
                    accountingDimensions = new AccountingDimensionsStagingConverter()
                            .convert(legacy, normalized);
                    accountingTemplates = new AccountingTemplatesStagingConverter()
                            .convert(legacy, normalized);
                    customers = new CustomerRegisterStagingConverter().convert(legacy, normalized);
                    suppliers = new SupplierRegisterStagingConverter().convert(legacy, normalized);
                    products = new ProductRegisterStagingConverter().convert(legacy, normalized);
                    customerInvoices = new CustomerInvoiceStagingConverter().convert(legacy, normalized);
                    customerCreditInvoices = new CustomerCreditInvoiceStagingConverter().convert(legacy, normalized);
                    periodicInvoices = new PeriodicInvoiceStagingConverter().convert(legacy, normalized);
                    payments = new PaymentStagingConverter().convert(legacy, normalized);
                    supplierInvoices = new SupplierInvoiceStagingConverter().convert(legacy, normalized);
                    supplierCreditInvoices = new SupplierCreditInvoiceStagingConverter()
                            .convert(legacy, normalized);
                    saleDocuments = new SaleDocumentStagingConverter().convert(legacy, normalized);
                    purchaseOrders = new PurchaseOrderStagingConverter().convert(legacy, normalized);
                    stockDocuments = new StockDocumentStagingConverter().convert(legacy, normalized);
                    shutdown(normalized, "SHUTDOWN SCRIPT");
                } catch (SQLException | RuntimeException failure) {
                    shutdownAfterFailure(normalized, failure);
                    throw failure;
                }
            }

            SemanticFingerprint reopened;
            SemanticFingerprint reopenedCompanyDetails;
            SemanticFingerprint reopenedAccountingDimensions;
            SemanticFingerprint reopenedAccountingTemplates;
            SemanticFingerprint reopenedCustomers;
            SemanticFingerprint reopenedSuppliers;
            SemanticFingerprint reopenedProducts;
            SemanticFingerprint reopenedCustomerInvoices;
            SemanticFingerprint reopenedCustomerCreditInvoices;
            SemanticFingerprint reopenedPeriodicInvoices;
            SemanticFingerprint reopenedPayments;
            SemanticFingerprint reopenedSupplierInvoices;
            SemanticFingerprint reopenedSupplierCreditInvoices;
            SemanticFingerprint reopenedSaleDocuments;
            SemanticFingerprint reopenedPurchaseOrders;
            SemanticFingerprint reopenedStockDocuments;
            try (Connection verification = DriverManager.getConnection(url, "sa", "")) {
                verification.setReadOnly(true);
                reopened = new AccountingCoreStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedCompanyDetails = new CompanyDetailsStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedAccountingDimensions = new AccountingDimensionsStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedAccountingTemplates = new AccountingTemplatesStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedCustomers = new CustomerRegisterStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedSuppliers = new SupplierRegisterStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedProducts = new ProductRegisterStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedCustomerInvoices = new CustomerInvoiceStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedCustomerCreditInvoices = new CustomerCreditInvoiceStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedPeriodicInvoices = new PeriodicInvoiceStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedPayments = new PaymentStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedSupplierInvoices = new SupplierInvoiceStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedSupplierCreditInvoices = new SupplierCreditInvoiceStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedSaleDocuments = new SaleDocumentStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedPurchaseOrders = new PurchaseOrderStagingConverter()
                        .fingerprintNormalized(verification);
                reopenedStockDocuments = new StockDocumentStagingConverter()
                        .fingerprintNormalized(verification);
                shutdown(verification, "SHUTDOWN");
            }
            var differences = conversion.destinationFingerprint().differences(reopened);
            if (!differences.isEmpty()) {
                throw new IOException("Durable staging fingerprint mismatch after reopen: "
                        + String.join("; ", differences));
            }
            var companyDifferences = companyDetails.destinationFingerprint()
                    .differences(reopenedCompanyDetails);
            if (!companyDifferences.isEmpty()) {
                throw new IOException("Durable company-details fingerprint mismatch after reopen: "
                        + String.join("; ", companyDifferences));
            }
            var dimensionDifferences = accountingDimensions.destinationFingerprint()
                    .differences(reopenedAccountingDimensions);
            if (!dimensionDifferences.isEmpty()) {
                throw new IOException("Durable accounting-dimensions fingerprint mismatch after reopen: "
                        + String.join("; ", dimensionDifferences));
            }
            var templateDifferences = accountingTemplates.destinationFingerprint()
                    .differences(reopenedAccountingTemplates);
            if (!templateDifferences.isEmpty()) {
                throw new IOException("Durable accounting-templates fingerprint mismatch after reopen: "
                        + String.join("; ", templateDifferences));
            }
            var customerDifferences = customers.destinationFingerprint().differences(reopenedCustomers);
            if (!customerDifferences.isEmpty()) {
                throw new IOException("Durable customer-register fingerprint mismatch after reopen: "
                        + String.join("; ", customerDifferences));
            }
            var supplierDifferences = suppliers.destinationFingerprint().differences(reopenedSuppliers);
            if (!supplierDifferences.isEmpty()) {
                throw new IOException("Durable supplier-register fingerprint mismatch after reopen: "
                        + String.join("; ", supplierDifferences));
            }
            var productDifferences = products.destinationFingerprint().differences(reopenedProducts);
            if (!productDifferences.isEmpty()) {
                throw new IOException("Durable product-register fingerprint mismatch after reopen: "
                        + String.join("; ", productDifferences));
            }
            var invoiceDifferences = customerInvoices.destinationFingerprint()
                    .differences(reopenedCustomerInvoices);
            if (!invoiceDifferences.isEmpty()) {
                throw new IOException("Durable customer-invoice fingerprint mismatch after reopen: "
                        + String.join("; ", invoiceDifferences));
            }
            var creditInvoiceDifferences = customerCreditInvoices.destinationFingerprint()
                    .differences(reopenedCustomerCreditInvoices);
            if (!creditInvoiceDifferences.isEmpty()) {
                throw new IOException("Durable customer-credit-invoice fingerprint mismatch after reopen: "
                        + String.join("; ", creditInvoiceDifferences));
            }
            var periodicDifferences = periodicInvoices.destinationFingerprint()
                    .differences(reopenedPeriodicInvoices);
            if (!periodicDifferences.isEmpty()) {
                throw new IOException("Durable periodic-invoice fingerprint mismatch after reopen: "
                        + String.join("; ", periodicDifferences));
            }
            var paymentDifferences = payments.destinationFingerprint().differences(reopenedPayments);
            if (!paymentDifferences.isEmpty()) {
                throw new IOException("Durable payment fingerprint mismatch after reopen: "
                        + String.join("; ", paymentDifferences));
            }
            var supplierInvoiceDifferences = supplierInvoices.destinationFingerprint()
                    .differences(reopenedSupplierInvoices);
            if (!supplierInvoiceDifferences.isEmpty()) {
                throw new IOException("Durable supplier-invoice fingerprint mismatch after reopen: "
                        + String.join("; ", supplierInvoiceDifferences));
            }
            var supplierCreditDifferences = supplierCreditInvoices.destinationFingerprint()
                    .differences(reopenedSupplierCreditInvoices);
            if (!supplierCreditDifferences.isEmpty()) {
                throw new IOException("Durable supplier-credit-invoice fingerprint mismatch after reopen: "
                        + String.join("; ", supplierCreditDifferences));
            }
            var saleDocumentDifferences = saleDocuments.destinationFingerprint()
                    .differences(reopenedSaleDocuments);
            if (!saleDocumentDifferences.isEmpty()) {
                throw new IOException("Durable sale-document fingerprint mismatch after reopen: "
                        + String.join("; ", saleDocumentDifferences));
            }
            var purchaseOrderDifferences = purchaseOrders.destinationFingerprint()
                    .differences(reopenedPurchaseOrders);
            if (!purchaseOrderDifferences.isEmpty()) {
                throw new IOException("Durable purchase-order fingerprint mismatch after reopen: "
                        + String.join("; ", purchaseOrderDifferences));
            }
            var stockDifferences = stockDocuments.destinationFingerprint()
                    .differences(reopenedStockDocuments);
            if (!stockDifferences.isEmpty()) {
                throw new IOException("Durable stock fingerprint mismatch after reopen: "
                        + String.join("; ", stockDifferences));
            }
            requireCatalogFiles(database);
            success = true;
            return new StagingCatalogResult(staging, database,
                    conversion, companyDetails, accountingDimensions, accountingTemplates, customers,
                    suppliers, products, customerInvoices, customerCreditInvoices, periodicInvoices,
                    payments, supplierInvoices, supplierCreditInvoices, saleDocuments, purchaseOrders,
                    stockDocuments, reopened, reopenedCompanyDetails, reopenedAccountingDimensions,
                    reopenedAccountingTemplates, reopenedCustomers, reopenedSuppliers, reopenedProducts,
                    reopenedCustomerInvoices, reopenedCustomerCreditInvoices, reopenedPeriodicInvoices,
                    reopenedPayments, reopenedSupplierInvoices, reopenedSupplierCreditInvoices,
                    reopenedSaleDocuments, reopenedPurchaseOrders, reopenedStockDocuments,
                    clock.instant());
        } finally {
            if (!success) {
                deleteTree(staging);
            }
        }
    }

    private static Path uniqueStagingDirectory(Path data, String suffix) throws IOException {
        Path candidate = data.resolve(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX + suffix);
        for (int attempt = 0; Files.exists(candidate); attempt++) {
            candidate = data.resolve(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX
                    + suffix + "-" + (attempt + 1));
        }
        return candidate;
    }

    private static void shutdown(Connection connection, String command) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static void shutdownAfterFailure(Connection connection, Exception failure) {
        try {
            shutdown(connection, "SHUTDOWN");
        } catch (SQLException shutdownFailure) {
            failure.addSuppressed(shutdownFailure);
        }
    }

    private static void requireCatalogFiles(Path database) throws IOException {
        Path properties = Path.of(database + ".properties");
        Path script = Path.of(database + ".script");
        if (!Files.isRegularFile(properties) || !Files.isRegularFile(script)) {
            throw new IOException("Normalized staging catalog is incomplete: " + database);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record StagingCatalogResult(Path stagingDirectory, Path database,
                                       AccountingCoreStagingConverter.ConversionResult conversion,
                                       CompanyDetailsStagingConverter.ConversionResult companyDetails,
                                       AccountingDimensionsStagingConverter.ConversionResult accountingDimensions,
                                       AccountingTemplatesStagingConverter.ConversionResult accountingTemplates,
                                       CustomerRegisterStagingConverter.ConversionResult customers,
                                       SupplierRegisterStagingConverter.ConversionResult suppliers,
                                       ProductRegisterStagingConverter.ConversionResult products,
                                       CustomerInvoiceStagingConverter.ConversionResult customerInvoices,
                                       CustomerCreditInvoiceStagingConverter.ConversionResult customerCreditInvoices,
                                       PeriodicInvoiceStagingConverter.ConversionResult periodicInvoices,
                                       PaymentStagingConverter.ConversionResult payments,
                                       SupplierInvoiceStagingConverter.ConversionResult supplierInvoices,
                                       SupplierCreditInvoiceStagingConverter.ConversionResult supplierCreditInvoices,
                                       SaleDocumentStagingConverter.ConversionResult saleDocuments,
                                       PurchaseOrderStagingConverter.ConversionResult purchaseOrders,
                                       StockDocumentStagingConverter.ConversionResult stockDocuments,
                                       SemanticFingerprint durableFingerprint,
                                       SemanticFingerprint durableCompanyDetailsFingerprint,
                                       SemanticFingerprint durableAccountingDimensionsFingerprint,
                                       SemanticFingerprint durableAccountingTemplatesFingerprint,
                                       SemanticFingerprint durableCustomerRegisterFingerprint,
                                       SemanticFingerprint durableSupplierRegisterFingerprint,
                                       SemanticFingerprint durableProductRegisterFingerprint,
                                       SemanticFingerprint durableCustomerInvoiceFingerprint,
                                       SemanticFingerprint durableCustomerCreditInvoiceFingerprint,
                                       SemanticFingerprint durablePeriodicInvoiceFingerprint,
                                       SemanticFingerprint durablePaymentFingerprint,
                                       SemanticFingerprint durableSupplierInvoiceFingerprint,
                                       SemanticFingerprint durableSupplierCreditInvoiceFingerprint,
                                       SemanticFingerprint durableSaleDocumentFingerprint,
                                       SemanticFingerprint durablePurchaseOrderFingerprint,
                                       SemanticFingerprint durableStockDocumentFingerprint,
                                       Instant completedAt) {}
}
