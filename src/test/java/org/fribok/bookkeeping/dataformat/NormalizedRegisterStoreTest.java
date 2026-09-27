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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedRegisterStoreTest {

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
