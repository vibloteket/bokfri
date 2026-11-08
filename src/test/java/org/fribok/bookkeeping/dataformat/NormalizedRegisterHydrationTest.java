package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedRegisterHydrationTest {
    @Test
    void nullFieldsAbsentAddressesAndEmptyProductChildrenSurviveWithoutLegacyJdbc() throws Exception {
        try (Connection connection = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);
            SSCustomer customer = SSCustomer.forPersistence();
            customer.setNumber("C1");
            SSSupplier supplier = SSSupplier.forPersistence();
            supplier.setNumber("S1");
            SSProduct product = SSProduct.forPersistence();
            product.setNumber("P1");
            store.addCustomer(71, customer);
            store.addSupplier(71, supplier);
            store.addProduct(71, product);
            connection.commit();
            for (int pass = 0; pass < 2; pass++) {
                SSCustomer loadedCustomer = store.getCustomers(71).get(0);
                assertThat(loadedCustomer.getInvoiceAddress()).isNull();
                assertThat(loadedCustomer.getDeliveryAddress()).isNull();
                assertThat(loadedCustomer.getStoredInvoiceCurrency()).isNull();
                assertThat(loadedCustomer.getPaymentTerm()).isNull();
                assertThat(loadedCustomer.getDeliveryTerm()).isNull();
                assertThat(loadedCustomer.getDeliveryWay()).isNull();
                assertThat(loadedCustomer.getOurContactPerson()).isNull();
                SSSupplier loadedSupplier = store.getSuppliers(71).get(0);
                assertThat(loadedSupplier.getAddress()).isNull();
                assertThat(loadedSupplier.getStoredCurrency()).isNull();
                assertThat(loadedSupplier.getPaymentTerm()).isNull();
                assertThat(loadedSupplier.getDeliveryTerm()).isNull();
                assertThat(loadedSupplier.getDeliveryWay()).isNull();
                assertThat(loadedSupplier.getOurContact()).isNull();
                SSProduct loadedProduct = store.getProducts(71).get(0);
                assertThat(loadedProduct.getStoredDefaultAccounts()).isEmpty();
                assertThat(loadedProduct.getDescriptions()).isEmpty();
                assertThat(loadedProduct.getParcelRows()).isEmpty();
                assertThat(loadedProduct.getTaxCode()).isNull();
                assertThat(loadedProduct.getStoredSellingPrice()).isNull();
                assertThat(loadedProduct.getPurchasePrice()).isNull();
                assertThat(loadedProduct.getStockPrice()).isNull();
                assertThat(loadedProduct.getUnitFreight()).isNull();
                assertThat(loadedProduct.getStoredWeight()).isNull();
                assertThat(loadedProduct.getStoredVolume()).isNull();
                assertThat(loadedProduct.getUnit()).isNull();
                assertThat(loadedProduct.isStockProduct()).isFalse();
                if (pass == 0) {
                    store.addCustomer(72, loadedCustomer);
                    store.addSupplier(72, loadedSupplier);
                    store.addProduct(72, loadedProduct);
                    connection.commit();
                }
            }
            assertThat(store.getCustomers(72).get(0).getInvoiceAddress()).isNull();
            assertThat(store.getSuppliers(72).get(0).getAddress()).isNull();
            assertThat(store.getProducts(72).get(0).getTaxCode()).isNull();
            for (String table : List.of("customer_address", "supplier_address", "product_default_account")) {
                try (var sql = connection.createStatement();
                        var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    assertThat(rows.getInt(1)).as(table + " stays empty after a read/write cycle").isZero();
                }
            }
        }
    }

    @Test
    void populatedLookupsAddressesAndProductChildrenStayCompanyScopedWithLegacyJdbcBlocked() throws Exception {
        try (Connection connection = database(); LegacyState ignored = new LegacyState(true)) {
            try (var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO currency (code,description,exchange_rate) VALUES ('EUR','Euro',2)");
                sql.executeUpdate("INSERT INTO unit_definition (name,description) VALUES ('st','Styck')");
                sql.executeUpdate("INSERT INTO payment_term (name,description) VALUES ('30','Net 30')");
                sql.executeUpdate("INSERT INTO delivery_term (name,description) VALUES ('FOB','FOB')");
                sql.executeUpdate("INSERT INTO delivery_way (name,description) VALUES ('Post','Post')");
                sql.executeUpdate("INSERT INTO project (company_id,number,name,concluded) "
                        + "SELECT id,'P1',CASE WHEN legacy_id=71 THEN 'Selected project' ELSE 'Other project' END,"
                        + "false FROM company");
                sql.executeUpdate("INSERT INTO result_unit (company_id,number,name) "
                        + "SELECT id,'U1',CASE WHEN legacy_id=71 THEN 'Selected unit' ELSE 'Other unit' END "
                        + "FROM company");
            }
            NormalizedRegisterStore store = new NormalizedRegisterStore(connection);
            for (int company : List.of(71, 72)) {
                String label = company == 71 ? "Selected" : "Other";
                SSCustomer customer = SSCustomer.forPersistence();
                customer.setNumber("C1");
                customer.setName(label);
                customer.setInvoiceCurrency(store.getCurrencies().get(0));
                customer.setPaymentTerm(store.getPaymentTerms().get(0));
                customer.setDeliveryTerm(store.getDeliveryTerms().get(0));
                customer.setDeliveryWay(store.getDeliveryWays().get(0));
                SSAddress address = new SSAddress();
                address.setName(label + " address");
                customer.setInvoiceAddress(address);
                store.addCustomer(company, customer);
                SSSupplier supplier = SSSupplier.forPersistence();
                supplier.setNumber("S1");
                supplier.setName(label);
                supplier.setCurrency(store.getCurrencies().get(0));
                supplier.setPaymentTerm(store.getPaymentTerms().get(0));
                supplier.setAddress(address);
                store.addSupplier(company, supplier);
                SSProduct product = SSProduct.forPersistence();
                product.setNumber("P1");
                product.setDescription(label);
                product.setTaxCode(SSTaxCode.TAXRATE_1);
                product.setSellingPrice(new BigDecimal("12.50"));
                product.setUnit(store.getUnits().get(0));
                product.setDescriptions(Map.of(Locale.ENGLISH, label + " translation"));
                product.setDefaultAccount(SSDefaultAccount.Sales, 3010);
                product.setDefaultAccount(SSDefaultAccount.Purchases, (Integer) null);
                product.setProjectNr("P1");
                product.setResultUnitNr("U1");
                SSProductRow component = new SSProductRow();
                component.setProduct("COMP");
                component.setDescription(label + " component");
                component.setQuantity(3);
                product.getParcelRows().add(component);
                store.addProduct(company, product);
            }
            connection.commit();
            SSCustomer customer = store.getCustomers(71).get(0);
            assertThat(customer.getName()).isEqualTo("Selected");
            assertThat(customer.getInvoiceAddress().getName()).isEqualTo("Selected address");
            assertThat(customer.getDeliveryAddress()).isNull();
            assertThat(customer.getStoredInvoiceCurrency().getName()).isEqualTo("EUR");
            assertThat(customer.getPaymentTerm().getName()).isEqualTo("30");
            assertThat(customer.getDeliveryTerm().getName()).isEqualTo("FOB");
            assertThat(customer.getDeliveryWay().getName()).isEqualTo("Post");
            SSSupplier supplier = store.getSuppliers(71).get(0);
            assertThat(supplier.getName()).isEqualTo("Selected");
            assertThat(supplier.getAddress().getName()).isEqualTo("Selected address");
            assertThat(supplier.getStoredCurrency().getName()).isEqualTo("EUR");
            SSProduct product = store.getProducts(71).get(0);
            assertThat(product.getDescription()).isEqualTo("Selected");
            assertThat(product.getStoredSellingPrice()).isEqualByComparingTo("12.50");
            assertThat(product.getTaxCode()).isEqualTo(SSTaxCode.TAXRATE_1);
            assertThat(product.getUnit().getName()).isEqualTo("st");
            assertThat(product.getDescriptions()).containsExactlyEntriesOf(Map.of(Locale.ENGLISH,
                    "Selected translation"));
            assertThat(product.getStoredDefaultAccounts()).hasSize(2)
                    .containsEntry(SSDefaultAccount.Sales, 3010).containsEntry(SSDefaultAccount.Purchases, null);
            assertThat(product.getParcelRows()).hasSize(1);
            assertThat(product.getParcelRows().get(0).getDescription()).isEqualTo("Selected component");
            assertThat(product.getProject().getName()).isEqualTo("Selected project");
            assertThat(product.getResultUnit().getName()).isEqualTo("Selected unit");
            assertThat(store.getCustomers(72).get(0).getName()).isEqualTo("Other");
            assertThat(store.getSuppliers(72).get(0).getName()).isEqualTo("Other");
            assertThat(store.getProducts(72).get(0).getDescription()).isEqualTo("Other");
        }
    }

    @Test
    void copiesPreserveAbsentAddressesAndIndependentlyCopyPresentOnes() {
        SSCustomer customer = SSCustomer.forPersistence();
        assertThat(new SSCustomer(customer).getInvoiceAddress()).isNull();
        assertThat(new SSCustomer(customer).getDeliveryAddress()).isNull();
        SSSupplier supplier = SSSupplier.forPersistence();
        assertThat(new SSSupplier(supplier).getAddress()).isNull();
        SSAddress address = new SSAddress();
        address.setName("Stored address");
        customer.setInvoiceAddress(address);
        customer.setDeliveryAddress(address);
        supplier.setAddress(address);
        SSCustomer customerCopy = new SSCustomer(customer);
        SSSupplier supplierCopy = new SSSupplier(supplier);
        assertThat(customerCopy.getInvoiceAddress()).isNotSameAs(address);
        assertThat(customerCopy.getDeliveryAddress()).isNotSameAs(address);
        assertThat(supplierCopy.getAddress()).isNotSameAs(address);
        customerCopy.getInvoiceAddress().setName("Changed invoice");
        customerCopy.getDeliveryAddress().setName("Changed delivery");
        supplierCopy.getAddress().setName("Changed supplier");
        assertThat(address.getName()).isEqualTo("Stored address");
    }

    @Test
    void applyingCustomersToInvoicesPreservesMissingAddressesAndCopiesPresentOnes() throws Exception {
        try (LegacyState ignored = new LegacyState(true)) {
            SSCustomer customer = SSCustomer.forPersistence();
            customer.setNumber("C1");
            SSInvoice invoice = SSInvoice.forPersistence();
            invoice.setCustomer(customer);
            assertThat(invoice.getInvoiceAddress()).isNull();
            assertThat(invoice.getDeliveryAddress()).isNull();
            SSAddress address = new SSAddress();
            address.setName("Stored address");
            customer.setInvoiceAddress(address);
            customer.setDeliveryAddress(address);
            invoice.setCustomer(customer);
            assertThat(invoice.getInvoiceAddress()).isNotSameAs(address);
            assertThat(invoice.getDeliveryAddress()).isNotSameAs(address);
            assertThat(invoice.getInvoiceAddress().getName()).isEqualTo("Stored address");
            assertThat(invoice.getDeliveryAddress().getName()).isEqualTo("Stored address");
        }
    }

    @Test
    void noArgumentConstructorsKeepLegacyDefaults() throws Exception {
        try (LegacyState ignored = new LegacyState(false)) {
            assertThat(new SSCustomer().getInvoiceAddress()).isNotNull();
            assertThat(new SSCustomer().getDeliveryAddress()).isNotNull();
            assertThat(new SSSupplier().getAddress()).isNotNull();
            SSProduct product = new SSProduct();
            assertThat(product.getStoredSellingPrice()).isEqualByComparingTo("0");
            assertThat(product.getPurchasePrice()).isEqualByComparingTo("0");
            assertThat(product.getTaxCode()).isEqualTo(SSTaxCode.TAXRATE_1);
            assertThat(product.isStockProduct()).isTrue();
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:register_hydration_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        new SchemaMigrationRunner(clock).migrate(connection, NormalizedSchemaMigrations.load());
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany();
            company.setId(id);
            new NormalizedAccountingWriter(clock).addCompany(connection, company);
        }
        connection.commit();
        return connection;
    }

    private static final class LegacyState implements AutoCloseable {
        private final SSDB db = SSDB.getInstance();
        private final Map<Field, Object> saved = new LinkedHashMap<>();

        LegacyState(boolean blocked) throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field field = SSDB.class.getDeclaredField(name);
                field.setAccessible(true);
                saved.put(field, field.get(db));
            }
            SSNewCompany unrelated = new SSNewCompany();
            unrelated.setContactPerson("Unrelated contact");
            unrelated.getDefaultAccounts().put(SSDefaultAccount.Sales, 9999);
            unrelated.getDefaultAccounts().put(SSDefaultAccount.Purchases, 8888);
            set("iCurrentCompany", blocked ? unrelated : null);
            set("iCurrentYear", null);
            set("iVouchers", null);
            if (blocked) set("iConnection", Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }

        private void set(String name, Object value) throws Exception {
            Field field = SSDB.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(db, value);
        }

        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(db, entry.getValue());
        }
    }
}
