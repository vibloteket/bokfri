package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedRegisterStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void clearLegacySingletonSelection() {
        // Domain-object constructors copy defaults (currency, payment terms, ...) from the
        // global SSDB singleton's current company. In a shared test JVM that state can leak
        // between test classes and would make the store persist unintended lookup references.
        se.swedsoft.bookkeeping.data.system.SSDB stale =
                se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
        stale.setCurrentYear(null);
        stale.setCurrentCompany(null);
    }

    @Test
    void registersRoundTrip() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(4);
            writer.addCompany(connection, company);
            connection.commit();

            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);

            SSCustomer customer = new SSCustomer();
            customer.setNumber("K1");
            customer.setName("Kund AB");
            customer.setEMail("k@example.se");
            customer.setTaxFree(true);
            store.addCustomer(4, customer);
            connection.commit();
            assertThat(store.getCustomers(4)).hasSize(1);
            assertThat(store.getCustomers(4).get(0).getName()).isEqualTo("Kund AB");
            assertThat(store.getCustomers(4).get(0).getTaxFree()).isTrue();

            SSSupplier supplier = new SSSupplier();
            supplier.setNumber("L1");
            supplier.setName("Leverantör AB");
            store.addSupplier(4, supplier);
            connection.commit();
            assertThat(store.getSuppliers(4)).hasSize(1);

            SSProduct product = new SSProduct();
            product.setNumber("A1");
            product.setDescription("Artikel");
            product.setSellingPrice(new BigDecimal("12.50"));
            product.setStockProduct(true);
            store.addProduct(4, product);
            connection.commit();
            assertThat(store.getProducts(4)).hasSize(1);
            assertThat(store.getProducts(4).get(0).getStoredSellingPrice())
                    .isEqualByComparingTo("12.50");

            store.deleteCustomer(4, "K1");
            store.deleteSupplier(4, "L1");
            store.deleteProduct(4, "A1");
            connection.commit();
            assertThat(store.getCustomers(4)).isEmpty();
            assertThat(store.getSuppliers(4)).isEmpty();
            assertThat(store.getProducts(4)).isEmpty();
        }
    }

    @Test
    void lookupsAndDimensionsRoundTrip() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(7);
            writer.addCompany(connection, company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency (code,description,exchange_rate) "
                        + "VALUES ('SEK','Svensk krona',1)");
                statement.executeUpdate("INSERT INTO unit_definition (name,description) "
                        + "VALUES ('st','Styck')");
                statement.executeUpdate("INSERT INTO payment_term (name,description) "
                        + "VALUES ('30 dagar','Netto 30')");
                statement.executeUpdate("INSERT INTO project (company_id,number,name,concluded) "
                        + "SELECT id,'P1','Projekt ett',false FROM company WHERE legacy_id=7");
                statement.executeUpdate("INSERT INTO result_unit (company_id,number,name) "
                        + "SELECT id,'R1','Enhet ett' FROM company WHERE legacy_id=7");
            }
            connection.commit();

            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);
            assertThat(store.getCurrencies()).hasSize(1);
            assertThat(store.getCurrencies().get(0).getName()).isEqualTo("SEK");
            assertThat(store.getCurrencies().get(0).getExchangeRate())
                    .isEqualByComparingTo("1");
            assertThat(store.getUnits()).hasSize(1);
            assertThat(store.getUnits().get(0).getName()).isEqualTo("st");
            assertThat(store.getPaymentTerms()).hasSize(1);
            assertThat(store.getPaymentTerms().get(0).getName()).isEqualTo("30 dagar");
            assertThat(store.getProjects(7)).hasSize(1);
            assertThat(store.getProjects(7).get(0).getNumber()).isEqualTo("P1");
            assertThat(store.getProjects(7).get(0).isConcluded(java.time.LocalDate.now()))
                    .isFalse();
            assertThat(store.getResultUnits(7)).hasSize(1);
            assertThat(store.getResultUnits(7).get(0).getNumber()).isEqualTo("R1");
        }
    }

    @Test
    void registersRoundTripWithFullFidelity() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(9);
            writer.addCompany(connection, company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency (code,description,exchange_rate) "
                        + "VALUES ('SEK','Svensk krona',1)");
                statement.executeUpdate("INSERT INTO unit_definition (name,description) "
                        + "VALUES ('st','Styck')");
                statement.executeUpdate("INSERT INTO payment_term (name,description) "
                        + "VALUES ('30 dagar','Netto 30')");
                statement.executeUpdate("INSERT INTO delivery_term (name,description) "
                        + "VALUES ('ExWorks','Ex works')");
                statement.executeUpdate("INSERT INTO delivery_way (name,description) "
                        + "VALUES ('Post','Posten')");
                statement.executeUpdate("INSERT INTO project (company_id,number,name,concluded) "
                        + "SELECT id,'P1','Projekt ett',false FROM company WHERE legacy_id=9");
                statement.executeUpdate("INSERT INTO result_unit (company_id,number,name) "
                        + "SELECT id,'R1','Enhet ett' FROM company WHERE legacy_id=9");
            }
            connection.commit();
            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);

            SSCustomer customer = new SSCustomer();
            customer.setNumber("K9");
            customer.setName("Kund Nio AB");
            customer.setEMail("info@kund9.se");
            customer.setPhone1("08-1");
            customer.setPhone2("08-2");
            customer.setTelefax("08-3");
            customer.setRegistrationNumber("556677-8899");
            customer.setOurContactPerson("Vi");
            customer.setYourContactPerson("De");
            customer.setVATNumber("SE556677889901");
            customer.setBankgiro("111-2222");
            customer.setPlusgiro("333-4");
            customer.setAccountNumber("123456789");
            customer.setClearingNumber("5555");
            customer.setEuSaleCommodity(true);
            customer.setTaxFree(true);
            customer.setHideUnitprice(true);
            customer.setInvoiceCurrency(store.getCurrencies().get(0));
            customer.setPaymentTerm(store.getPaymentTerms().get(0));
            customer.setDeliveryTerm(store.getDeliveryTerms().get(0));
            customer.setDeliveryWay(store.getDeliveryWays().get(0));
            customer.setCreditLimit(new BigDecimal("5000"));
            customer.setDiscount(new BigDecimal("10.5"));
            customer.setComment("Viktig kund");
            se.swedsoft.bookkeeping.data.SSAddress invoiceAddress =
                    new se.swedsoft.bookkeeping.data.SSAddress();
            invoiceAddress.setName("Kund Nio AB");
            invoiceAddress.setAddress1("Box 1");
            invoiceAddress.setAddress2("Ref");
            invoiceAddress.setZipCode("11122");
            invoiceAddress.setCity("Stockholm");
            invoiceAddress.setCountry("Sverige");
            customer.setInvoiceAddress(invoiceAddress);
            se.swedsoft.bookkeeping.data.SSAddress deliveryAddress =
                    new se.swedsoft.bookkeeping.data.SSAddress();
            deliveryAddress.setName("Lager");
            deliveryAddress.setAddress1("Gatan 1");
            deliveryAddress.setCity("Göteborg");
            customer.setDeliveryAddress(deliveryAddress);
            store.addCustomer(9, customer);
            connection.commit();

            SSCustomer readCustomer = store.getCustomers(9).get(0);
            assertThat(readCustomer.getNumber()).isEqualTo("K9");
            assertThat(readCustomer.getPhone2()).isEqualTo("08-2");
            assertThat(readCustomer.getTelefax()).isEqualTo("08-3");
            assertThat(readCustomer.getRegistrationNumber()).isEqualTo("556677-8899");
            assertThat(readCustomer.getOurContactPerson()).isEqualTo("Vi");
            assertThat(readCustomer.getYourContactPerson()).isEqualTo("De");
            assertThat(readCustomer.getVATNumber()).isEqualTo("SE556677889901");
            assertThat(readCustomer.getBankgiro()).isEqualTo("111-2222");
            assertThat(readCustomer.getPlusgiro()).isEqualTo("333-4");
            assertThat(readCustomer.getAccountNumber()).isEqualTo("123456789");
            assertThat(readCustomer.getClearingNumber()).isEqualTo("5555");
            assertThat(readCustomer.getEuSaleCommodity()).isTrue();
            assertThat(readCustomer.getHideUnitprice()).isTrue();
            assertThat(readCustomer.getStoredInvoiceCurrency().getName()).isEqualTo("SEK");
            assertThat(readCustomer.getPaymentTerm().getName()).isEqualTo("30 dagar");
            assertThat(readCustomer.getDeliveryTerm().getName()).isEqualTo("ExWorks");
            assertThat(readCustomer.getDeliveryWay().getName()).isEqualTo("Post");
            assertThat(readCustomer.getCreditLimit()).isEqualByComparingTo("5000");
            assertThat(readCustomer.getDiscount()).isEqualByComparingTo("10.5");
            assertThat(readCustomer.getComment()).isEqualTo("Viktig kund");
            assertThat(readCustomer.getInvoiceAddress().getAddress1()).isEqualTo("Box 1");
            assertThat(readCustomer.getInvoiceAddress().getZipCode()).isEqualTo("11122");
            assertThat(readCustomer.getDeliveryAddress().getCity()).isEqualTo("Göteborg");
            assertThat(readCustomer.getDeliveryAddress().getAddress2()).isNullOrEmpty();

            SSSupplier supplier = new SSSupplier();
            supplier.setNumber("L9");
            supplier.setName("Leverantör Nio AB");
            supplier.setEMail("lev@example.se");
            supplier.setHomepage("https://lev9.example.se");
            supplier.setRegistrationNumber("556000-1111");
            supplier.setYourContact("Deras");
            supplier.setOurContact("Vår");
            supplier.setOurCustomerNr("4711");
            supplier.setBankGiro("999-1");
            supplier.setPlusGiro("888-2");
            supplier.setOutpaymentNumber(7);
            supplier.setCurrency(store.getCurrencies().get(0));
            supplier.setPaymentTerm(store.getPaymentTerms().get(0));
            supplier.setComment("Leverantörskommentar");
            se.swedsoft.bookkeeping.data.SSAddress supplierAddress =
                    new se.swedsoft.bookkeeping.data.SSAddress();
            supplierAddress.setName("Leverantör Nio AB");
            supplierAddress.setAddress1("Industrigatan 5");
            supplierAddress.setCity("Malmö");
            supplier.setAddress(supplierAddress);
            store.addSupplier(9, supplier);
            connection.commit();

            SSSupplier readSupplier = store.getSuppliers(9).get(0);
            assertThat(readSupplier.getNumber()).isEqualTo("L9");
            assertThat(readSupplier.getHomepage()).isEqualTo("https://lev9.example.se");
            assertThat(readSupplier.getOurCustomerNr()).isEqualTo("4711");
            assertThat(readSupplier.getBankgiro()).isEqualTo("999-1");
            assertThat(readSupplier.getOutpaymentNumber()).isEqualTo(7);
            assertThat(readSupplier.getStoredCurrency().getName()).isEqualTo("SEK");
            assertThat(readSupplier.getComment()).isEqualTo("Leverantörskommentar");
            assertThat(readSupplier.getAddress().getCity()).isEqualTo("Malmö");

            SSProduct product = new SSProduct();
            product.setNumber("A9");
            product.setDescription("Artikel nio");
            product.setSellingPrice(new BigDecimal("199.90"));
            product.setTaxCode(se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_1);
            product.setUnit(store.getUnits().get(0));
            product.setWeight(new BigDecimal("1.25"));
            product.setVolume(new BigDecimal("0.5"));
            product.setStockProduct(true);
            product.setDefaultAccount(
                    se.swedsoft.bookkeeping.data.common.SSDefaultAccount.Sales, 3010);
            product.setDescriptions(java.util.Map.of(Locale.ENGLISH, "Article nine"));
            product.setProjectNr("P1");
            product.setResultUnitNr("R1");
            se.swedsoft.bookkeeping.data.SSProductRow component =
                    new se.swedsoft.bookkeeping.data.SSProductRow();
            component.setProduct("A1");
            component.setDescription("Komponent");
            component.setQuantity(2);
            product.getParcelRows().add(component);
            store.addProduct(9, product);
            connection.commit();

            SSProduct readProduct = store.getProducts(9).get(0);
            assertThat(readProduct.getNumber()).isEqualTo("A9");
            assertThat(readProduct.getTaxCode().name()).isEqualTo("TAXRATE_1");
            assertThat(readProduct.getUnit().getName()).isEqualTo("st");
            assertThat(readProduct.getStoredWeight()).isEqualByComparingTo("1.25");
            assertThat(readProduct.getStoredVolume()).isEqualByComparingTo("0.5");
            assertThat(readProduct.getDefaultAccount(
                    se.swedsoft.bookkeeping.data.common.SSDefaultAccount.Sales)).isEqualTo(3010);
            assertThat(readProduct.getDescription(Locale.ENGLISH)).contains("Article nine");
            assertThat(readProduct.getProject()).isNotNull();
            assertThat(readProduct.getProject().getNumber()).isEqualTo("P1");
            assertThat(readProduct.getResultUnit()).isNotNull();
            assertThat(readProduct.getResultUnit().getNumber()).isEqualTo("R1");
            assertThat(readProduct.getParcelRows()).hasSize(1);
            assertThat(readProduct.getParcelRows().get(0).getProductNr()).isEqualTo("A1");
            assertThat(readProduct.getParcelRows().get(0).getQuantity()).isEqualTo(2);

            // Deletes must cascade over address/description/account/component child rows.
            store.deleteCustomer(9, "K9");
            store.deleteSupplier(9, "L9");
            store.deleteProduct(9, "A9");
            connection.commit();
            assertThat(store.getCustomers(9)).isEmpty();
            assertThat(store.getSuppliers(9)).isEmpty();
            assertThat(store.getProducts(9)).isEmpty();
        }
    }

    @Test
    void autoIncrementCountersStartAtZeroAndBump() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(15);
            writer.addCompany(connection, company);
            connection.commit();
            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);

            assertThat(store.autoIncrementValue(15, "invoicejournal")).isZero();
            store.bumpAutoIncrement(15, "invoicejournal");
            connection.commit();
            assertThat(store.autoIncrementValue(15, "invoicejournal")).isEqualTo(1);
            store.bumpAutoIncrement(15, "invoicejournal");
            connection.commit();
            assertThat(store.autoIncrementValue(15, "invoicejournal")).isEqualTo(2);
            // Other counters and other companies are unaffected.
            assertThat(store.autoIncrementValue(15, "invoice")).isZero();
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:register_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
