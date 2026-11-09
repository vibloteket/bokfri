package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSPurchaseOrder;
import se.swedsoft.bookkeeping.data.SSPurchaseOrderRow;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedPurchaseOrderStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void migratedSnapshotsCanBeLoadedAndResavedWithoutAnySemanticFingerprintChange() throws Exception {
        try (Connection legacy = memory(); Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            try (var statement = legacy.createStatement()) {
                statement.execute("CREATE TABLE tbl_purchaseorder"
                        + " (id INTEGER,companyid INTEGER,number INTEGER,purchaseorder OTHER)");
            }
            SSPurchaseOrder order = full(new NormalizedRegisterStore(sql));
            try (var insert = legacy.prepareStatement("INSERT INTO tbl_purchaseorder VALUES (91,71,7,?)")) {
                insert.setObject(1, order);
                insert.executeUpdate();
            }
            PurchaseOrderStagingConverter converter = new PurchaseOrderStagingConverter();
            var migration = converter.convert(legacy, sql);
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            var loaded = store.getPurchaseOrders(71).get(0);
            assertThat(loaded.legacyId()).isEqualTo(91);
            assertThat(loaded.document().getInvoiceNr()).isEqualTo(42);
            assertThat(loaded.document().isStockInfluencing()).isTrue();
            assertThat(loaded.document().getRows()).hasSize(2);
            assertThat(loaded.document().getRows().get(0).getDescription()).isEqualTo("Stored row");
            assertThat(loaded.document().getRows().get(1).getDescription()).isNull();
            store.updatePurchaseOrder(71, loaded.id(), loaded.document());
            assertThat(migration.sourceFingerprint().differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThat(store.findPurchaseOrder(71, loaded.id()).orElseThrow().legacyId()).isEqualTo(91);
            assertThat(store.getPurchaseOrders(72)).isEmpty();
        }
    }

    @Test
    void nullAndDuplicateNumbersUseStableIdentitiesAndPreserveAbsentOptionalFields() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            SSPurchaseOrder empty = SSPurchaseOrder.forPersistence();
            long first = store.addPurchaseOrder(71, empty);
            long second = store.addPurchaseOrder(71, empty);
            long other = store.addPurchaseOrder(72, empty);
            var loaded = store.findPurchaseOrder(71, first).orElseThrow();
            SSPurchaseOrder order = loaded.document();
            assertThat(order.getNumber()).isNull();
            assertThat(order.getLocalDate()).isNull();
            assertThat(order.getLocalEstimatedDelivery()).isNull();
            assertThat(order.getSupplierNr()).isNull();
            assertThat(order.getSupplierName()).isNull();
            assertThat(order.getCurrency()).isNull();
            assertThat(order.getStoredCurrencyRate()).isNull();
            assertThat(order.getPaymentTerm()).isNull();
            assertThat(order.getDeliveryTerm()).isNull();
            assertThat(order.getDeliveryWay()).isNull();
            assertThat(order.getDeliveryAddress()).isNull();
            assertThat(order.getSupplierAddress()).isNull();
            assertThat(order.getDefaultAccounts()).isEmpty();
            assertThat(order.getRows()).isEmpty();
            assertThat(order.getInvoiceNr()).isNull();
            assertThat(order.isStockInfluencing()).isFalse();
            assertThat(order.isPrinted()).isFalse();
            order.setNumber(9);
            order.setText("Edited first");
            store.updatePurchaseOrder(71, first, order);
            assertThat(store.findPurchaseOrder(71, first).orElseThrow().legacyId()).isEqualTo(loaded.legacyId());
            assertThat(store.findPurchaseOrder(71, second).orElseThrow().document().getNumber()).isNull();
            assertThat(store.findPurchaseOrder(72, other).orElseThrow().document().getText()).isNull();
            order.setText("Unsaved edit");
            assertThat(store.findPurchaseOrder(71, first).orElseThrow().document().getText()).isEqualTo("Edited first");
            long duplicate = store.addPurchaseOrder(71, order);
            assertThat(duplicate).isNotEqualTo(first);
            assertThat(store.findPurchaseOrder(71, duplicate).orElseThrow().document().getNumber()).isEqualTo(9);
            assertThat(empty.getNumber()).isNull();
        }
    }

    @Test
    void lateInsertAndUpdateFailuresPreserveTheOuterTransactionAndAllowRetry() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            SSPurchaseOrder original = full(new NormalizedRegisterStore(sql));
            long id = store.addPurchaseOrder(71, original);
            sql.commit();
            var outer = sql.setSavepoint();
            try (var statement = sql.createStatement()) {
                statement.executeUpdate("UPDATE company SET name='Outer transaction' WHERE legacy_id=72");
            }
            PurchaseOrderStagingConverter converter = new PurchaseOrderStagingConverter();
            var before = converter.fingerprintNormalized(sql);
            SSPurchaseOrder edited = full(new NormalizedRegisterStore(sql));
            edited.setText("Replacement header");
            SSPurchaseOrderRow bad = new SSPurchaseOrderRow();
            bad.setUnit(new SSUnit("MISSING", "Missing"));
            edited.getRows().add(bad);
            assertThatThrownBy(() -> store.addPurchaseOrder(71, edited)).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThatThrownBy(() -> store.updatePurchaseOrder(71, id, edited)).isInstanceOf(SQLException.class);
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThat(companyName(sql, 72)).isEqualTo("Outer transaction");
            bad.setUnit(null);
            store.updatePurchaseOrder(71, id, edited);
            long added = store.addPurchaseOrder(71, edited);
            assertThat(store.findPurchaseOrder(71, added).orElseThrow().document().getRows()).hasSize(3);
            assertThat(store.findPurchaseOrder(71, id).orElseThrow().document().getText())
                    .isEqualTo("Replacement header");
            sql.rollback(outer);
            assertThat(companyName(sql, 72)).isNotEqualTo("Outer transaction");
            assertThat(store.findPurchaseOrder(71, id).orElseThrow().document().getText()).isEqualTo("Stored text");
            assertThat(store.findPurchaseOrder(71, added)).isEmpty();
        }
    }

    @Test
    void wrongCompanyWritesAndLateDeleteFailuresCannotRemoveOwnedChildren() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            SSPurchaseOrder order = full(new NormalizedRegisterStore(sql));
            long id = store.addPurchaseOrder(71, order);
            long other = store.addPurchaseOrder(72, order);
            PurchaseOrderStagingConverter converter = new PurchaseOrderStagingConverter();
            var before = converter.fingerprintNormalized(sql);
            assertThat(store.findPurchaseOrder(72, id)).isEmpty();
            assertThatThrownBy(() -> store.updatePurchaseOrder(72, id, order)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> store.deletePurchaseOrder(72, id)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> store.addPurchaseOrder(999, order)).isInstanceOf(SQLException.class);
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            try (var statement = sql.createStatement()) {
                statement.execute("CREATE TRIGGER block_delete BEFORE DELETE ON purchase_order"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Blocked delete'");
            }
            assertThatThrownBy(() -> store.deletePurchaseOrder(71, id)).isInstanceOf(SQLException.class);
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            try (var statement = sql.createStatement()) { statement.execute("DROP TRIGGER block_delete"); }
            store.deletePurchaseOrder(71, id);
            assertThat(store.getPurchaseOrders(71)).isEmpty();
            assertThat(store.findPurchaseOrder(72, other).orElseThrow().document().getRows()).hasSize(2);
            assertThat(store.findPurchaseOrder(72, other).orElseThrow().document().getDefaultAccounts()).hasSize(2);
            for (String suffix : List.of("_row", "_address", "_default_account")) {
                try (var statement = sql.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM purchase_order" + suffix + " WHERE purchase_order_id=" + id)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void replacingWithEmptySnapshotRemovesOnlySelectedChildrenAndRetainsBothIdentities() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            long id = store.addPurchaseOrder(71, full(new NormalizedRegisterStore(sql)));
            long other = store.addPurchaseOrder(72, full(new NormalizedRegisterStore(sql)));
            int legacyId = store.findPurchaseOrder(71, id).orElseThrow().legacyId();
            store.updatePurchaseOrder(71, id, SSPurchaseOrder.forPersistence());
            var loaded = store.findPurchaseOrder(71, id).orElseThrow();
            assertThat(loaded.id()).isEqualTo(id);
            assertThat(loaded.legacyId()).isEqualTo(legacyId);
            assertThat(loaded.document().getRows()).isEmpty();
            assertThat(loaded.document().getDefaultAccounts()).isEmpty();
            assertThat(loaded.document().getDeliveryAddress()).isNull();
            assertThat(loaded.document().getSupplierAddress()).isNull();
            assertThat(loaded.document().getStoredCurrencyRate()).isNull();
            assertThat(loaded.document().getInvoiceNr()).isNull();
            assertThat(loaded.document().isStockInfluencing()).isFalse();
            assertThat(store.findPurchaseOrder(72, other).orElseThrow().document().getRows()).hasSize(2);
        }
    }

    @Test
    void committedSnapshotsSurviveFileShutdownAndReopenWithoutLegacyTables() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporaryDirectory.resolve("purchase-orders");
        long id;
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState(true)) {
            initialize(sql);
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            id = store.addPurchaseOrder(71, full(new NormalizedRegisterStore(sql)));
            sql.commit();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState(true)) {
            sql.setAutoCommit(false);
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            var order = store.findPurchaseOrder(71, id).orElseThrow().document();
            assertThat(order.getText()).isEqualTo("Stored text");
            assertThat(order.getRows()).hasSize(2);
            assertThat(order.getInvoiceNr()).isEqualTo(42);
            assertThat(store.findPurchaseOrder(72, id)).isEmpty();
            store.deletePurchaseOrder(71, id);
            sql.commit();
            assertThat(store.getPurchaseOrders(71)).isEmpty();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void allWritesRejectAutocommitBeforeChangingAnything() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedPurchaseOrderStore store = new NormalizedPurchaseOrderStore(sql);
            long id = store.addPurchaseOrder(71, SSPurchaseOrder.forPersistence());
            sql.commit();
            sql.setAutoCommit(true);
            assertThatThrownBy(() -> store.addPurchaseOrder(71, SSPurchaseOrder.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.updatePurchaseOrder(71, id, SSPurchaseOrder.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.deletePurchaseOrder(71, id))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThat(store.getPurchaseOrders(71)).hasSize(1);
        }
    }

    @Test
    void copiesPreserveMissingAddressesStockFlagAndIndependentPresentChildren() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            SSPurchaseOrder empty = new SSPurchaseOrder(SSPurchaseOrder.forPersistence());
            assertThat(empty.getDeliveryAddress()).isNull();
            assertThat(empty.getSupplierAddress()).isNull();
            assertThat(empty.isStockInfluencing()).isFalse();
            SSPurchaseOrder original = full(new NormalizedRegisterStore(sql));
            SSPurchaseOrder copy = new SSPurchaseOrder(original);
            assertThat(copy.isStockInfluencing()).isTrue();
            assertThat(copy.getDeliveryAddress()).isNotSameAs(original.getDeliveryAddress());
            assertThat(copy.getSupplierAddress()).isNotSameAs(original.getSupplierAddress());
            assertThat(copy.getRows().get(0)).isNotSameAs(original.getRows().get(0));
            copy.getDeliveryAddress().setName("Changed delivery");
            copy.getSupplierAddress().setName("Changed supplier");
            copy.getRows().get(0).setDescription("Changed row");
            copy.getDefaultAccounts().put(SSDefaultAccount.Purchases, 9999);
            assertThat(original.getDeliveryAddress().getName()).isEqualTo("Delivery");
            assertThat(original.getSupplierAddress().getName()).isEqualTo("Supplier");
            assertThat(original.getRows().get(0).getDescription()).isEqualTo("Stored row");
            assertThat(original.getDefaultAccounts()).containsEntry(SSDefaultAccount.Purchases, 4010);
        }
    }

    @Test
    void supplierAssignmentPreservesMissingAddressesAndCopiesPresentOnes() throws Exception {
        try (LegacyState ignored = new LegacyState(false)) {
            SSPurchaseOrder order = SSPurchaseOrder.forPersistence();
            // Isolate address copying from the legacy business currency resolver, which is unchanged.
            SSSupplier supplier = new SSSupplier() {
                @Override public SSCurrency getCurrency() { return getStoredCurrency(); }
            };
            supplier.setAddress(null);
            supplier.setNumber("S1");
            supplier.setName("Supplier snapshot");
            order.setSupplier(supplier);
            assertThat(order.getSupplierAddress()).isNull();
            assertThat(order.getSupplierName()).isEqualTo("Supplier snapshot");
            SSAddress address = new SSAddress();
            address.setName("Stored supplier address");
            supplier.setAddress(address);
            order.setSupplier(supplier);
            assertThat(order.getSupplierAddress()).isNotSameAs(address);
            order.getSupplierAddress().setName("Changed snapshot");
            assertThat(address.getName()).isEqualTo("Stored supplier address");
        }
    }

    @Test
    void noArgumentBusinessDefaultsAndRateFallbackAreUnchanged() throws Exception {
        try (LegacyState ignored = new LegacyState(false)) {
            SSPurchaseOrder order = new SSPurchaseOrder();
            assertThat(order.getDeliveryAddress()).isNotNull();
            assertThat(order.getSupplierAddress()).isNotNull();
            assertThat(order.getLocalEstimatedDelivery()).isEqualTo(LocalDate.now());
            assertThat(order.isStockInfluencing()).isTrue();
            assertThat(order.getStoredCurrencyRate()).isNull();
            assertThat(order.getCurrencyRate()).isEqualByComparingTo("1");
            assertThat(order.getStoredCurrencyRate()).isNull();
        }
    }

    private static SSPurchaseOrder full(NormalizedRegisterStore registers) throws SQLException {
        SSPurchaseOrder order = SSPurchaseOrder.forPersistence();
        order.setNumber(7);
        order.setLocalDate(LocalDate.of(2026, 11, 1));
        order.setSupplierNr("S1");
        order.setSupplierName("Supplier snapshot");
        order.setOurContact("Our contact");
        order.setYourContact("Supplier contact");
        order.setPaymentTerm(registers.getPaymentTerms().get(0));
        order.setDeliveryTerm(registers.getDeliveryTerms().get(0));
        order.setDeliveryWay(registers.getDeliveryWays().get(0));
        order.setCurrency(registers.getCurrencies().get(0));
        order.setCurrencyRate(new BigDecimal("10.125"));
        order.setText("Stored text");
        order.setLocalEstimatedDelivery(LocalDate.of(2026, 12, 31));
        order.setPrinted(true);
        order.setStockInfluencing(true);
        order.setInvoiceNr(42);
        order.getDefaultAccounts().put(SSDefaultAccount.Purchases, 4010);
        order.getDefaultAccounts().put(SSDefaultAccount.SupplierDebt, null);
        SSAddress delivery = new SSAddress();
        delivery.setName("Delivery");
        delivery.setAddress1("Street");
        delivery.setAddress2("Floor");
        delivery.setZipCode("12345");
        delivery.setCity("City");
        delivery.setCountry("SE");
        order.setDeliveryAddress(delivery);
        SSAddress supplier = new SSAddress();
        supplier.setName("Supplier");
        order.setSupplierAddress(supplier);
        SSPurchaseOrderRow row = new SSPurchaseOrderRow();
        row.setProductNr("P1");
        row.setDescription("Stored row");
        row.setSupplierArticleNr("SUP-1");
        row.setUnit(registers.getUnits().get(0));
        row.setQuantity(3);
        row.setUnitPrice(new BigDecimal("123.4567"));
        row.setAccountNr(4010);
        order.getRows().add(row);
        order.getRows().add(new SSPurchaseOrderRow());
        return order;
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:purchase_store_" + UUID.randomUUID(), "sa", "");
    }

    private static Connection database() throws Exception {
        Connection sql = memory();
        initialize(sql);
        return sql;
    }

    private static void initialize(Connection sql) throws Exception {
        sql.setAutoCommit(false);
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        new SchemaMigrationRunner(clock).migrate(sql, NormalizedSchemaMigrations.load());
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany();
            company.setId(id);
            company.setName("Company " + id);
            new NormalizedAccountingWriter(clock).addCompany(sql, company);
        }
        try (var statement = sql.createStatement()) {
            statement.executeUpdate("INSERT INTO currency VALUES ('EUR','Euro',10)");
            statement.executeUpdate("INSERT INTO unit_definition VALUES ('st','Styck')");
            statement.executeUpdate("INSERT INTO payment_term VALUES ('30','Net 30')");
            statement.executeUpdate("INSERT INTO delivery_term VALUES ('FOB','FOB')");
            statement.executeUpdate("INSERT INTO delivery_way VALUES ('Post','Post')");
        }
        sql.commit();
    }

    private static String companyName(Connection sql, int company) throws SQLException {
        try (var statement = sql.prepareStatement("SELECT name FROM company WHERE legacy_id=?")) {
            statement.setInt(1, company);
            try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
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
            set("iCurrentCompany", blocked ? new SSNewCompany() : null);
            set("iCurrentYear", null);
            set("iVouchers", null);
            set("iConnection", blocked ? Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }) : null);
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
