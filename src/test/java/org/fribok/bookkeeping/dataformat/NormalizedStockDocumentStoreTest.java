package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSIndelivery;
import se.swedsoft.bookkeeping.data.SSIndeliveryRow;
import se.swedsoft.bookkeeping.data.SSInventory;
import se.swedsoft.bookkeeping.data.SSInventoryRow;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOutdelivery;
import se.swedsoft.bookkeeping.data.SSOutdeliveryRow;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedStockDocumentStoreTest {
    @TempDir Path temporaryDirectory;
    private enum Kind { INVENTORY, IN, OUT }
    private record Line(String product, Integer stock, Integer change) {}
    private record State(Integer number, LocalDate date, String text, List<Line> rows) {}

    @Test
    void completeMigratedSnapshotsCanBeLoadedAndResavedWithoutFingerprintChanges() throws Exception {
        try (Connection legacy = memory(); Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            for (Kind kind : Kind.values()) {
                String table = "tbl_" + table(kind);
                try (var statement = legacy.createStatement()) {
                    statement.execute("CREATE TABLE " + table + " (id INTEGER,companyid INTEGER,number INTEGER,"
                            + table(kind) + " OTHER)");
                }
                try (var insert = legacy.prepareStatement("INSERT INTO " + table + " VALUES (91,71,7,?)")) {
                    insert.setObject(1, full(kind));
                    insert.executeUpdate();
                }
            }
            StockDocumentStagingConverter converter = new StockDocumentStagingConverter();
            var migration = converter.convert(legacy, sql);
            for (Kind kind : Kind.values()) {
                var loaded = list(store, kind, 71).get(0);
                assertThat(loaded.legacyId()).isEqualTo(91);
                assertThat(state(loaded.document())).isEqualTo(state(full(kind)));
                update(store, kind, 71, loaded.id(), loaded.document());
                assertThat(find(store, kind, 71, loaded.id()).orElseThrow().legacyId()).isEqualTo(91);
                assertThat(list(store, kind, 72)).isEmpty();
            }
            assertThat(migration.sourceFingerprint().differences(converter.fingerprintNormalized(sql))).isEmpty();
        }
    }

    @Test
    void nullDuplicateNumbersAndRowValuesRemainDetachedAndUseStableStorageIdentities() throws Exception {
        for (Kind kind : Kind.values()) {
            try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
                NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
                Object empty = empty(kind);
                long first = add(store, kind, 71, empty);
                long second = add(store, kind, 71, empty);
                long other = add(store, kind, 72, empty);
                var loaded = find(store, kind, 71, first).orElseThrow();
                assertThat(state(loaded.document())).isEqualTo(new State(null, null, null, List.of()));
                edit(loaded.document(), 9, "Edited first");
                update(store, kind, 71, first, loaded.document());
                assertThat(find(store, kind, 71, first).orElseThrow().legacyId()).isEqualTo(loaded.legacyId());
                assertThat(state(find(store, kind, 71, second).orElseThrow().document()).number()).isNull();
                assertThat(state(find(store, kind, 72, other).orElseThrow().document()).text()).isNull();
                edit(loaded.document(), 9, "Unsaved edit");
                assertThat(state(find(store, kind, 71, first).orElseThrow().document()).text())
                        .isEqualTo("Edited first");
                long duplicate = add(store, kind, 71, loaded.document());
                assertThat(duplicate).isNotEqualTo(first);
                assertThat(state(find(store, kind, 71, duplicate).orElseThrow().document()).number()).isEqualTo(9);
                assertThat(state(empty).number()).isNull();
                long nullable = add(store, kind, 71, full(kind));
                assertThat(state(find(store, kind, 71, nullable).orElseThrow().document()).rows())
                        .containsExactlyElementsOf(state(full(kind)).rows());
            }
        }
    }

    @Test
    void lateInsertAndUpdateFailuresPreserveOuterSavepointsAndPermitRetry() throws Exception {
        for (Kind kind : Kind.values()) {
            try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
                NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
                long id = add(store, kind, 71, full(kind));
                sql.commit();
                var outer = sql.setSavepoint();
                try (var statement = sql.createStatement()) {
                    statement.executeUpdate("UPDATE company SET name='Outer transaction' WHERE legacy_id=72");
                }
                StockDocumentStagingConverter converter = new StockDocumentStagingConverter();
                var before = converter.fingerprintNormalized(sql);
                Object edited = full(kind);
                edit(edited, 9, "Replacement header");
                append(edited, "X".repeat(256), 1, 1);
                assertThatThrownBy(() -> add(store, kind, 71, edited)).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
                assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
                assertThatThrownBy(() -> update(store, kind, 71, id, edited)).isInstanceOf(SQLException.class);
                assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
                assertThat(companyName(sql, 72)).isEqualTo("Outer transaction");
                removeLast(edited);
                update(store, kind, 71, id, edited);
                long added = add(store, kind, 71, edited);
                assertThat(state(find(store, kind, 71, id).orElseThrow().document()).text())
                        .isEqualTo("Replacement header");
                sql.rollback(outer);
                assertThat(companyName(sql, 72)).isNotEqualTo("Outer transaction");
                assertThat(state(find(store, kind, 71, id).orElseThrow().document()).text()).isEqualTo("Stored text");
                assertThat(find(store, kind, 71, added)).isEmpty();
            }
        }
    }

    @Test
    void foreignCompanyWritesAndLateDeleteFailuresCannotRemoveOwnedRows() throws Exception {
        for (Kind kind : Kind.values()) {
            try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
                NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
                long id = add(store, kind, 71, full(kind));
                long other = add(store, kind, 72, full(kind));
                StockDocumentStagingConverter converter = new StockDocumentStagingConverter();
                var before = converter.fingerprintNormalized(sql);
                assertThat(find(store, kind, 72, id)).isEmpty();
                assertThatThrownBy(() -> update(store, kind, 72, id, full(kind))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> delete(store, kind, 72, id)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> add(store, kind, 999, full(kind))).isInstanceOf(SQLException.class);
                assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
                try (var statement = sql.createStatement()) {
                    statement.execute("CREATE TRIGGER block_delete BEFORE DELETE ON " + table(kind)
                            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Blocked delete'");
                }
                assertThatThrownBy(() -> delete(store, kind, 71, id)).isInstanceOf(SQLException.class);
                assertThat(before.differences(converter.fingerprintNormalized(sql))).isEmpty();
                try (var statement = sql.createStatement()) { statement.execute("DROP TRIGGER block_delete"); }
                delete(store, kind, 71, id);
                assertThat(list(store, kind, 71)).isEmpty();
                assertThat(state(find(store, kind, 72, other).orElseThrow().document())).isEqualTo(state(full(kind)));
                try (var statement = sql.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table(kind) + "_row WHERE " + table(kind) + "_id=" + id)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void replacingWithEmptySnapshotsRemovesOnlySelectedRowsAndRetainsBothIdentities() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            for (Kind kind : Kind.values()) {
                long id = add(store, kind, 71, full(kind));
                long other = add(store, kind, 72, full(kind));
                int legacyId = find(store, kind, 71, id).orElseThrow().legacyId();
                update(store, kind, 71, id, empty(kind));
                var loaded = find(store, kind, 71, id).orElseThrow();
                assertThat(loaded.id()).isEqualTo(id);
                assertThat(loaded.legacyId()).isEqualTo(legacyId);
                assertThat(state(loaded.document())).isEqualTo(new State(null, null, null, List.of()));
                assertThat(state(find(store, kind, 72, other).orElseThrow().document()).rows()).hasSize(3);
            }
        }
    }

    @Test
    void stockTotalsReflectTypedEditsDeletesCompanyIsolationAndCallerRollback() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            SSInventory inventory = SSInventory.forPersistence();
            append(inventory, "P1", 20, 3);
            SSIndelivery incoming = SSIndelivery.forPersistence();
            append(incoming, "P1", null, 10);
            SSOutdelivery outgoing = SSOutdelivery.forPersistence();
            append(outgoing, "P1", null, 4);
            long inventoryId = store.addInventory(71, inventory);
            long incomingId = store.addIndelivery(71, incoming);
            long outgoingId = store.addOutdelivery(71, outgoing);
            store.addIndelivery(72, incoming);
            SSProduct product = SSProduct.forPersistence();
            product.setNumber("P1");
            product.setStockProduct(true);
            NormalizedStockStore stock = new NormalizedStockStore(sql);
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(9);
            assertThat(stock.read(72, List.of(product)).getQuantity(product)).isEqualTo(10);
            sql.commit();
            incoming.getRows().get(0).setChange(20);
            store.updateIndelivery(71, incomingId, incoming);
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(19);
            outgoing.getRows().get(0).setChange(-4);
            store.updateOutdelivery(71, outgoingId, outgoing);
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(27);
            inventory.getRows().get(0).setChange(-3);
            store.updateInventory(71, inventoryId, inventory);
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(21);
            store.deleteInventory(71, inventoryId);
            store.deleteOutdelivery(71, outgoingId);
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(20);
            sql.rollback();
            assertThat(stock.read(71, List.of(product)).getQuantity(product)).isEqualTo(9);
        }
    }

    @Test
    void committedDocumentsSurviveFileShutdownAndReopenWithoutLegacyTables() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporaryDirectory.resolve("stock-documents");
        Map<Kind, Long> ids = new LinkedHashMap<>();
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState(true)) {
            initialize(sql);
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            for (Kind kind : Kind.values()) ids.put(kind, add(store, kind, 71, full(kind)));
            sql.commit();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        try (Connection sql = DriverManager.getConnection(url, "sa", ""); LegacyState ignored = new LegacyState(true)) {
            sql.setAutoCommit(false);
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            for (Kind kind : Kind.values()) {
                long id = ids.get(kind);
                assertThat(state(find(store, kind, 71, id).orElseThrow().document())).isEqualTo(state(full(kind)));
                assertThat(find(store, kind, 72, id)).isEmpty();
                delete(store, kind, 71, id);
                assertThat(list(store, kind, 71)).isEmpty();
            }
            sql.commit();
            try (var statement = sql.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void everyWriteRejectsAutocommitWithoutChangingData() throws Exception {
        try (Connection sql = database(); LegacyState ignored = new LegacyState(true)) {
            NormalizedStockDocumentStore store = new NormalizedStockDocumentStore(sql);
            Map<Kind, Long> ids = new LinkedHashMap<>();
            for (Kind kind : Kind.values()) ids.put(kind, add(store, kind, 71, full(kind)));
            sql.commit();
            sql.setAutoCommit(true);
            for (Kind kind : Kind.values()) {
                assertThatThrownBy(() -> add(store, kind, 71, full(kind))).isInstanceOf(SQLException.class)
                        .hasMessageContaining("auto-commit disabled");
                assertThatThrownBy(() -> update(store, kind, 71, ids.get(kind), full(kind)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("auto-commit disabled");
                assertThatThrownBy(() -> delete(store, kind, 71, ids.get(kind))).isInstanceOf(SQLException.class)
                        .hasMessageContaining("auto-commit disabled");
                assertThat(list(store, kind, 71)).hasSize(1);
            }
        }
    }

    @Test
    void legacyConstructorsStillAutoNumberAndCopiesRetainIndependentRows() throws Exception {
        try (LegacyState ignored = new LegacyState(false)) {
            LocalDate before = LocalDate.now();
            SSInventory inventory = new SSInventory();
            SSIndelivery incoming = new SSIndelivery();
            SSOutdelivery outgoing = new SSOutdelivery();
            assertThat(inventory.getNumber()).isEqualTo(9);
            assertThat(incoming.getNumber()).isEqualTo(9);
            assertThat(outgoing.getNumber()).isEqualTo(9);
            assertThat(inventory.getLocalDate()).isBetween(before, LocalDate.now());
            assertThat(incoming.getLocalDate()).isBetween(before, LocalDate.now());
            assertThat(outgoing.getLocalDate()).isBetween(before, LocalDate.now());
            assertThat(inventory.getText()).isEmpty();
            assertThat(incoming.getText()).isNull();
            assertThat(outgoing.getText()).isNull();
            append(inventory, "P1", 5, 2);
            append(incoming, "P1", null, 2);
            append(outgoing, "P1", null, 2);
            SSInventory inventoryCopy = new SSInventory(inventory);
            SSIndelivery incomingCopy = new SSIndelivery(incoming);
            SSOutdelivery outgoingCopy = new SSOutdelivery(outgoing);
            inventoryCopy.getRows().get(0).setChange(9);
            incomingCopy.getRows().get(0).setChange(9);
            outgoingCopy.getRows().get(0).setChange(9);
            assertThat(inventory.getRows().get(0).getChange()).isEqualTo(2);
            assertThat(incoming.getRows().get(0).getChange()).isEqualTo(2);
            assertThat(outgoing.getRows().get(0).getChange()).isEqualTo(2);
        }
    }

    private static String table(Kind kind) {
        return switch (kind) { case INVENTORY -> "inventory"; case IN -> "indelivery"; case OUT -> "outdelivery"; };
    }

    private static Object empty(Kind kind) {
        return switch (kind) {
            case INVENTORY -> SSInventory.forPersistence();
            case IN -> SSIndelivery.forPersistence();
            case OUT -> SSOutdelivery.forPersistence();
        };
    }

    private static Object full(Kind kind) {
        Object document = empty(kind);
        edit(document, 7, "Stored text");
        if (document instanceof SSInventory inventory) inventory.setLocalDate(LocalDate.of(2026, 11, 1));
        if (document instanceof SSIndelivery incoming) incoming.setLocalDate(LocalDate.of(2026, 11, 1));
        if (document instanceof SSOutdelivery outgoing) outgoing.setLocalDate(LocalDate.of(2026, 11, 1));
        append(document, "P1", 20, -3);
        append(document, null, null, null);
        append(document, "UNKNOWN", 0, 0);
        return document;
    }

    private static void edit(Object document, Integer number, String text) {
        if (document instanceof SSInventory inventory) { inventory.setNumber(number); inventory.setText(text); }
        else if (document instanceof SSIndelivery incoming) { incoming.setNumber(number); incoming.setText(text); }
        else { SSOutdelivery outgoing = (SSOutdelivery) document; outgoing.setNumber(number); outgoing.setText(text); }
    }

    private static void append(Object document, String product, Integer stock, Integer change) {
        if (document instanceof SSInventory inventory) {
            SSInventoryRow row = new SSInventoryRow();
            row.setProductNr(product); row.setStockQuantity(stock); row.setChange(change);
            inventory.getRows().add(row);
        } else if (document instanceof SSIndelivery incoming) {
            SSIndeliveryRow row = new SSIndeliveryRow();
            row.setProductNr(product); row.setChange(change);
            incoming.getRows().add(row);
        } else {
            SSOutdeliveryRow row = new SSOutdeliveryRow();
            row.setProductNr(product); row.setChange(change);
            ((SSOutdelivery) document).getRows().add(row);
        }
    }

    private static void removeLast(Object document) {
        if (document instanceof SSInventory inventory) inventory.getRows().removeLast();
        else if (document instanceof SSIndelivery incoming) incoming.getRows().removeLast();
        else ((SSOutdelivery) document).getRows().removeLast();
    }

    private static State state(Object document) {
        if (document instanceof SSInventory inventory) return new State(inventory.getNumber(), inventory.getLocalDate(),
                inventory.getText(), inventory.getRows().stream()
                        .map(row -> new Line(row.getProductNr(), row.getStockQuantity(), row.getChange())).toList());
        if (document instanceof SSIndelivery incoming) return new State(incoming.getNumber(), incoming.getLocalDate(),
                incoming.getText(), incoming.getRows().stream()
                        .map(row -> new Line(row.getProductNr(), null, row.getChange())).toList());
        SSOutdelivery outgoing = (SSOutdelivery) document;
        return new State(outgoing.getNumber(), outgoing.getLocalDate(), outgoing.getText(), outgoing.getRows().stream()
                .map(row -> new Line(row.getProductNr(), null, row.getChange())).toList());
    }

    private static long add(NormalizedStockDocumentStore store, Kind kind, int company, Object document)
            throws SQLException {
        return switch (kind) {
            case INVENTORY -> store.addInventory(company, (SSInventory) document);
            case IN -> store.addIndelivery(company, (SSIndelivery) document);
            case OUT -> store.addOutdelivery(company, (SSOutdelivery) document);
        };
    }

    private static void update(NormalizedStockDocumentStore store, Kind kind, int company, long id, Object document)
            throws SQLException {
        switch (kind) {
            case INVENTORY -> store.updateInventory(company, id, (SSInventory) document);
            case IN -> store.updateIndelivery(company, id, (SSIndelivery) document);
            case OUT -> store.updateOutdelivery(company, id, (SSOutdelivery) document);
        }
    }

    private static void delete(NormalizedStockDocumentStore store, Kind kind, int company, long id)
            throws SQLException {
        switch (kind) {
            case INVENTORY -> store.deleteInventory(company, id);
            case IN -> store.deleteIndelivery(company, id);
            case OUT -> store.deleteOutdelivery(company, id);
        }
    }

    private static Optional<? extends NormalizedStockDocumentStore.Stored<?>> find(NormalizedStockDocumentStore store,
            Kind kind, int company, long id) throws SQLException {
        return switch (kind) {
            case INVENTORY -> store.findInventory(company, id);
            case IN -> store.findIndelivery(company, id);
            case OUT -> store.findOutdelivery(company, id);
        };
    }

    private static List<? extends NormalizedStockDocumentStore.Stored<?>> list(NormalizedStockDocumentStore store,
            Kind kind, int company) throws SQLException {
        return switch (kind) {
            case INVENTORY -> store.getInventories(company);
            case IN -> store.getIndeliveries(company);
            case OUT -> store.getOutdeliveries(company);
        };
    }

    private static Connection memory() throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:stock_store_" + UUID.randomUUID(), "sa", "");
    }

    private static Connection database() throws Exception {
        Connection sql = memory(); initialize(sql); return sql;
    }

    private static void initialize(Connection sql) throws Exception {
        sql.setAutoCommit(false);
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        new SchemaMigrationRunner(clock).migrate(sql, NormalizedSchemaMigrations.load());
        for (int id : List.of(71, 72)) {
            SSNewCompany company = new SSNewCompany(); company.setId(id); company.setName("Company " + id);
            new NormalizedAccountingWriter(clock).addCompany(sql, company);
        }
        sql.commit();
    }

    private static String companyName(Connection sql, int company) throws SQLException {
        try (var statement = sql.prepareStatement("SELECT name FROM company WHERE legacy_id=?")) {
            statement.setInt(1, company);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
        }
    }

    private static final class LegacyState implements AutoCloseable {
        private final SSDB db = SSDB.getInstance();
        private final Map<Field, Object> saved = new LinkedHashMap<>();

        LegacyState(boolean blocked) throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iInventories",
                    "iIndeliveries", "iOutdeliveries")) {
                Field field = SSDB.class.getDeclaredField(name);
                field.setAccessible(true);
                saved.put(field, field.get(db));
            }
            set("iCurrentCompany", blocked ? new SSNewCompany() : null);
            set("iCurrentYear", null);
            SSInventory inventory = SSInventory.forPersistence(); inventory.setNumber(8);
            SSIndelivery incoming = SSIndelivery.forPersistence(); incoming.setNumber(8);
            SSOutdelivery outgoing = SSOutdelivery.forPersistence(); outgoing.setNumber(8);
            set("iInventories", blocked ? null : List.of(inventory));
            set("iIndeliveries", blocked ? null : List.of(incoming));
            set("iOutdeliveries", blocked ? null : List.of(outgoing));
            set("iConnection", blocked ? Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }) : null);
        }

        private void set(String name, Object value) throws Exception {
            Field field = SSDB.class.getDeclaredField(name); field.setAccessible(true); field.set(db, value);
        }

        @Override public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(db, entry.getValue());
        }
    }
}
