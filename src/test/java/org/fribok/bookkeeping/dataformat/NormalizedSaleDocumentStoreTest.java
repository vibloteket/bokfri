package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSTender;
import se.swedsoft.bookkeeping.data.base.SSSale;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
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

class NormalizedSaleDocumentStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void migratedFullSnapshotsCanBeLoadedAndResavedWithoutAnyFingerprintChange() throws Exception {
        try (Connection legacy = memory(); Connection sql = database(); LegacyState ignored = new LegacyState()) {
            try (var statement = legacy.createStatement()) {
                statement.execute("CREATE TABLE tbl_order (id INTEGER,companyid INTEGER,number INTEGER,iorder OTHER)");
                statement.execute("CREATE TABLE tbl_tender (id INTEGER,companyid INTEGER,number INTEGER,tender OTHER)");
            }
            NormalizedRegisterStore registers = new NormalizedRegisterStore(sql);
            SSOrder order = full(SSOrder.forPersistence(), registers);
            order.setYourOrderNumber("Customer reference");
            order.setEstimatedDelivery("Next week");
            order.setInvoiceNr(42);
            order.setPeriodicInvoiceNr(43);
            order.setPurchaseOrderNr(44);
            order.setHideUnitprice(true);
            order.setCurrencyRate(new BigDecimal("10.25"));
            SSTender tender = full(SSTender.forPersistence(), registers);
            tender.setLocalExpires(LocalDate.of(2026, 12, 31));
            tender.setOrderNr(45);
            tender.setCurrencyRate(new BigDecimal("11.125"));
            for (boolean isTender : List.of(false, true)) {
                try (var insert = legacy.prepareStatement("INSERT INTO " + (isTender ? "tbl_tender" : "tbl_order")
                        + " VALUES (91,71,7,?)")) {
                    insert.setObject(1, isTender ? tender : order);
                    insert.executeUpdate();
                }
            }
            SaleDocumentStagingConverter converter = new SaleDocumentStagingConverter();
            var migration = converter.convert(legacy, sql);
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            var loadedOrder = store.getOrders(71).get(0);
            var loadedTender = store.getTenders(71).get(0);
            assertThat(loadedOrder.legacyId()).isEqualTo(91);
            assertThat(loadedTender.legacyId()).isEqualTo(91);
            assertThat(loadedOrder.document().getInvoiceNr()).isEqualTo(42);
            assertThat(loadedOrder.document().getPurchaseOrderNr()).isEqualTo(44);
            assertThat(loadedTender.document().getOrderNr()).isEqualTo(45);
            assertThat(loadedOrder.document().getRows()).hasSize(2);
            assertThat(loadedOrder.document().getRows().get(0).getDescription()).isEqualTo("Stored row");
            assertThat(loadedOrder.document().getRows().get(1).getDescription()).isNull();
            store.updateOrder(71, loadedOrder.id(), loadedOrder.document());
            store.updateTender(71, loadedTender.id(), loadedTender.document());
            assertThat(migration.sourceFingerprint().differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThat(store.getOrders(72)).isEmpty();
            assertThat(store.getTenders(72)).isEmpty();
        }
    }

    @Test
    void nullAndDuplicateNumbersUseStableStorageIdentitiesAndPreserveAbsenceOrders() throws Exception {
        nullAndDuplicateNumbersUseStableStorageIdentitiesAndPreserveAbsence(false);
    }

    @Test
    void nullAndDuplicateNumbersUseStableStorageIdentitiesAndPreserveAbsenceTenders() throws Exception {
        nullAndDuplicateNumbersUseStableStorageIdentitiesAndPreserveAbsence(true);
    }

    private void nullAndDuplicateNumbersUseStableStorageIdentitiesAndPreserveAbsence(boolean tender) throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState()) {
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            SSSale empty = empty(tender);
            long first = add(store, tender, 71, empty);
            long second = add(store, tender, 71, empty);
            empty.setNumber(9);
            long other = add(store, tender, 72, empty);
            var selected = find(store, tender, 71, first);
            assertThat(selected.document().getNumber()).isNull();
            assertThat(selected.document().getLocalDate()).isNull();
            assertThat(selected.document().getCurrency()).isNull();
            assertThat(selected.document().getStoredDelayInterest()).isNull();
            assertThat(selected.document().getStoredTaxRates()).containsExactly(null, null, null);
            assertThat(selected.document().getInvoiceAddress()).isNull();
            assertThat(selected.document().getDeliveryAddress()).isNull();
            assertThat(selected.document().getDefaultAccounts()).isEmpty();
            assertThat(selected.document().getRows()).isEmpty();
            if (selected.document() instanceof SSOrder order) {
                assertThat(order.getStoredCurrencyRate()).isNull();
                assertThat(order.getInvoiceNr()).isNull();
                assertThat(order.getPeriodicInvoiceNr()).isNull();
                assertThat(order.getPurchaseOrderNr()).isNull();
            } else {
                assertThat(((SSTender) selected.document()).getStoredCurrencyRate()).isNull();
                assertThat(((SSTender) selected.document()).getLocalExpires()).isNull();
                assertThat(((SSTender) selected.document()).getOrderNr()).isNull();
            }
            selected.document().setNumber(9);
            selected.document().setText("Edited first");
            update(store, tender, 71, first, selected.document());
            assertThat(find(store, tender, 71, first).legacyId()).isEqualTo(selected.legacyId());
            assertThat(find(store, tender, 71, second).document().getNumber()).isNull();
            assertThat(find(store, tender, 72, other).document().getText()).isNull();
            assertThat(find(store, tender, 71, first).document().getText()).isEqualTo("Edited first");
            // Returned snapshots do not write through, nor does insertion mutate caller-owned numbering.
            assertThat(empty.getNumber()).isEqualTo(9);
            selected.document().setText("Unsaved edit");
            assertThat(find(store, tender, 71, first).document().getText()).isEqualTo("Edited first");
            SSSale duplicate = empty(tender);
            duplicate.setNumber(9);
            long third = add(store, tender, 71, duplicate);
            assertThat(third).isNotEqualTo(first);
            assertThat(find(store, tender, 71, third).document().getNumber()).isEqualTo(9);
        }
    }

    @Test
    void lateInsertAndUpdateFailuresRollbackOnlyTheOperationAndAllowRetryOrders() throws Exception {
        lateInsertAndUpdateFailuresRollbackOnlyTheOperationAndAllowRetry(false);
    }

    @Test
    void lateInsertAndUpdateFailuresRollbackOnlyTheOperationAndAllowRetryTenders() throws Exception {
        lateInsertAndUpdateFailuresRollbackOnlyTheOperationAndAllowRetry(true);
    }

    private void lateInsertAndUpdateFailuresRollbackOnlyTheOperationAndAllowRetry(boolean tender) throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState()) {
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            SSSale original = full(empty(tender), new NormalizedRegisterStore(sql));
            long id = add(store, tender, 71, original);
            sql.commit();
            try (var statement = sql.createStatement()) {
                statement.executeUpdate("UPDATE company SET name='Outer transaction' WHERE legacy_id=72");
            }
            SaleDocumentStagingConverter converter = new SaleDocumentStagingConverter();
            var before = converter.fingerprintNormalized(sql);
            SSSale edited = full(empty(tender), new NormalizedRegisterStore(sql));
            edited.setText("Replacement header");
            SSSaleRow bad = new SSSaleRow();
            bad.setProjectNr("MISSING");
            edited.getRows().add(bad);
            assertThatThrownBy(() -> add(store, tender, 71, edited)).isInstanceOf(SQLException.class);
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThatThrownBy(() -> update(store, tender, 71, id, edited)).isInstanceOf(SQLException.class);
            assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
            assertThat(companyName(sql, 72)).isEqualTo("Outer transaction");
            bad.setProjectNr("P1");
            update(store, tender, 71, id, edited);
            long added = add(store, tender, 71, edited);
            assertThat(find(store, tender, 71, id).document().getText()).isEqualTo("Replacement header");
            assertThat(find(store, tender, 71, added).document().getRows()).hasSize(3);
            // Success must not commit the caller's changes, either.
            sql.rollback();
            assertThat(companyName(sql, 72)).isNotEqualTo("Outer transaction");
            assertThat(find(store, tender, 71, id).document().getText()).isEqualTo("Stored text");
            assertThat(tender ? store.findTender(71, added).isEmpty() : store.findOrder(71, added).isEmpty()).isTrue();
        }
    }

    @Test
    void writesAndDeletesRejectForeignIdsAndDeleteOnlyOwnedChildrenOrders() throws Exception {
        writesAndDeletesRejectForeignIdsAndDeleteOnlyOwnedChildren(false);
    }

    @Test
    void writesAndDeletesRejectForeignIdsAndDeleteOnlyOwnedChildrenTenders() throws Exception {
        writesAndDeletesRejectForeignIdsAndDeleteOnlyOwnedChildren(true);
    }

    private void writesAndDeletesRejectForeignIdsAndDeleteOnlyOwnedChildren(boolean tender) throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState()) {
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            SSSale sale = full(empty(tender), new NormalizedRegisterStore(sql));
            long owned = add(store, tender, 71, sale);
            long other = add(store, tender, 72, sale);
            var before = new SaleDocumentStagingConverter().fingerprintNormalized(sql);
            assertThatThrownBy(() -> update(store, tender, 72, owned, sale)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> delete(store, tender, 72, owned)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> add(store, tender, 999, sale)).isInstanceOf(SQLException.class);
            assertThat(before.differences(new SaleDocumentStagingConverter().fingerprintNormalized(sql))).isEmpty();
            String table = tender ? "tender" : "customer_order";
            try (var statement = sql.createStatement()) {
                statement.execute("CREATE TRIGGER block_delete BEFORE DELETE ON " + table
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Blocked delete'");
            }
            assertThatThrownBy(() -> delete(store, tender, 71, owned)).isInstanceOf(SQLException.class);
            assertThat(before.differences(new SaleDocumentStagingConverter().fingerprintNormalized(sql))).isEmpty();
            try (var statement = sql.createStatement()) { statement.execute("DROP TRIGGER block_delete"); }
            delete(store, tender, 71, owned);
            assertThat(tender ? store.getTenders(71) : store.getOrders(71)).isEmpty();
            assertThat(find(store, tender, 72, other).document().getRows()).hasSize(2);
            assertThat(find(store, tender, 72, other).document().getInvoiceAddress().getName()).isEqualTo("Invoice");
            assertThat(find(store, tender, 72, other).document().getDefaultAccounts()).hasSize(2);
            for (String suffix : List.of("_row", "_address", "_default_account")) {
                try (var statement = sql.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table + suffix + " WHERE "
                                + (tender ? "tender_id" : "order_id") + "=" + owned)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void replacingWithEmptySnapshotsRemovesOnlyTheSelectedDocumentsChildren() throws Exception {
        for (boolean tender : List.of(false, true)) {
            try (Connection sql = database(); LegacyState ignored = new LegacyState()) {
                NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
                SSSale full = full(empty(tender), new NormalizedRegisterStore(sql));
                long id = add(store, tender, 71, full);
                long other = add(store, tender, 72, full);
                int legacyId = find(store, tender, 71, id).legacyId();
                update(store, tender, 71, id, empty(tender));
                var restored = find(store, tender, 71, id);
                assertThat(restored.id()).isEqualTo(id);
                assertThat(restored.legacyId()).isEqualTo(legacyId);
                assertThat(restored.document().getNumber()).isNull();
                assertThat(restored.document().getRows()).isEmpty();
                assertThat(restored.document().getDefaultAccounts()).isEmpty();
                assertThat(restored.document().getInvoiceAddress()).isNull();
                assertThat(restored.document().getDeliveryAddress()).isNull();
                assertThat(restored.document().getStoredTaxRates()).containsExactly(null, null, null);
                assertThat(find(store, tender, 72, other).document().getRows()).hasSize(2);
            }
        }
    }

    @Test
    void explicitStoredAccessorsDoNotChangeLegacyBusinessDefaults() throws Exception {
        try (LegacyState ignored = new LegacyState()) {
            SSOrder order = SSOrder.forPersistence();
            SSTender tender = SSTender.forPersistence();
            assertThat(order.getStoredCurrencyRate()).isNull();
            assertThat(tender.getStoredCurrencyRate()).isNull();
            assertThat(order.getCurrencyRate()).isEqualByComparingTo("1");
            assertThat(tender.getCurrencyRate()).isEqualByComparingTo("1");
            assertThat(order.getStoredCurrencyRate()).isNull();
            assertThat(tender.getStoredCurrencyRate()).isNull();
            order.setTaxRate2(null);
            assertThat(order.getTaxRate2()).isEqualByComparingTo("12");
            order.setStoredTaxRates(null, null, null);
            assertThat(order.getStoredTaxRates()).containsExactly(null, null, null);
            order.setPurchaseOrderNr(42);
            tender.setOrderNr(43);
            assertThat(order.getPurchaseOrderNr()).isEqualTo(42);
            assertThat(tender.getOrderNr()).isEqualTo(43);
        }
    }

    @Test
    void committedSnapshotsSurviveShutdownAndReopenWithoutLegacyTables() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporaryDirectory.resolve("orders");
        long order;
        long tender;
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState()) {
            initialize(sql);
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            order = store.addOrder(71, full(SSOrder.forPersistence(), new NormalizedRegisterStore(sql)));
            tender = store.addTender(72, full(SSTender.forPersistence(), new NormalizedRegisterStore(sql)));
            sql.commit();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState()) {
            sql.setAutoCommit(false);
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            assertThat(store.findOrder(71, order).orElseThrow().document().getText()).isEqualTo("Stored text");
            assertThat(store.findTender(72, tender).orElseThrow().document().getRows()).hasSize(2);
            assertThat(store.findOrder(72, order)).isEmpty();
            assertThat(store.findTender(71, tender)).isEmpty();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void autocommitWritesAreRejectedBeforeCreatingPartialData() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState()) {
            NormalizedSaleDocumentStore store = new NormalizedSaleDocumentStore(sql);
            long order = store.addOrder(71, SSOrder.forPersistence());
            long tender = store.addTender(71, SSTender.forPersistence());
            sql.commit();
            sql.setAutoCommit(true);
            assertThatThrownBy(() -> store.addOrder(71, SSOrder.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.addTender(71, SSTender.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.updateOrder(71, order, SSOrder.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.updateTender(71, tender, SSTender.forPersistence()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.deleteOrder(71, order))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThatThrownBy(() -> store.deleteTender(71, tender))
                    .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
            assertThat(store.getOrders(71)).hasSize(1);
            assertThat(store.getTenders(71)).hasSize(1);
        }
    }

    private static SSSale empty(boolean tender) {
        return tender ? SSTender.forPersistence() : SSOrder.forPersistence();
    }

    private static long add(NormalizedSaleDocumentStore store, boolean tender, int company, SSSale sale)
            throws SQLException {
        return tender ? store.addTender(company, (SSTender) sale) : store.addOrder(company, (SSOrder) sale);
    }

    private static NormalizedSaleDocumentStore.Stored<?> find(NormalizedSaleDocumentStore store, boolean tender,
            int company, long id) throws SQLException {
        return tender ? store.findTender(company, id).orElseThrow() : store.findOrder(company, id).orElseThrow();
    }

    private static void update(NormalizedSaleDocumentStore store, boolean tender, int company, long id, SSSale sale)
            throws SQLException {
        if (tender) store.updateTender(company, id, (SSTender) sale);
        else store.updateOrder(company, id, (SSOrder) sale);
    }

    private static void delete(NormalizedSaleDocumentStore store, boolean tender, int company, long id)
            throws SQLException {
        if (tender) store.deleteTender(company, id);
        else store.deleteOrder(company, id);
    }

    private static <T extends SSSale> T full(T sale, NormalizedRegisterStore registers) throws SQLException {
        sale.setNumber(7);
        sale.setLocalDate(LocalDate.of(2026, 11, 1));
        sale.setCustomerNr("C1");
        sale.setCustomerName("Customer snapshot");
        sale.setOurContactPerson("Our contact");
        sale.setYourContactPerson("Their contact");
        sale.setDelayInterest(new BigDecimal("8.25"));
        sale.setCurrency(registers.getCurrencies().get(0));
        sale.setPaymentTerm(registers.getPaymentTerms().get(0));
        sale.setDeliveryTerm(registers.getDeliveryTerms().get(0));
        sale.setDeliveryWay(registers.getDeliveryWays().get(0));
        sale.setTaxFree(true);
        sale.setText("Stored text");
        sale.setStoredTaxRates(new BigDecimal("25"), null, new BigDecimal("6.125"));
        sale.setEuSaleCommodity(true);
        sale.setEuSaleYhirdPartCommodity(true);
        sale.setPrinted(true);
        sale.getDefaultAccounts().put(SSDefaultAccount.Sales, 3010);
        sale.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, null);
        SSAddress invoice = new SSAddress();
        invoice.setName("Invoice");
        invoice.setAddress1("Street");
        invoice.setAddress2("Floor");
        invoice.setZipCode("12345");
        invoice.setCity("City");
        invoice.setCountry("SE");
        sale.setInvoiceAddress(invoice);
        SSAddress delivery = new SSAddress();
        delivery.setName("Delivery");
        sale.setDeliveryAddress(delivery);
        SSSaleRow row = new SSSaleRow();
        row.setProductNr("P1");
        row.setDescription("Stored row");
        row.setUnitprice(new BigDecimal("123.4567"));
        row.setQuantity(new BigDecimal("2.5"));
        row.setUnit(registers.getUnits().get(0));
        row.setDiscount(new BigDecimal("12.5"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        row.setAccountNr(3010);
        row.setProjectNr("P1");
        row.setResultUnitNr("U1");
        sale.getRows().add(row);
        SSSaleRow blank = new SSSaleRow();
        blank.setTaxCode(null);
        blank.setDiscount(null);
        sale.getRows().add(blank);
        return sale;
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:sale_store_" + UUID.randomUUID(), "sa", "");
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
            statement.executeUpdate("INSERT INTO project (company_id,number,name,concluded) "
                    + "SELECT id,'P1','Project',false FROM company");
            statement.executeUpdate("INSERT INTO result_unit (company_id,number,name) "
                    + "SELECT id,'U1','Unit' FROM company");
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

        LegacyState() throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field field = SSDB.class.getDeclaredField(name);
                field.setAccessible(true);
                saved.put(field, field.get(db));
            }
            set("iCurrentCompany", new SSNewCompany());
            set("iCurrentYear", null);
            set("iVouchers", null);
            set("iConnection", Proxy.newProxyInstance(Connection.class.getClassLoader(),
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
