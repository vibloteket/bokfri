package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSIndelivery;
import se.swedsoft.bookkeeping.data.SSIndeliveryRow;
import se.swedsoft.bookkeeping.data.SSInventory;
import se.swedsoft.bookkeeping.data.SSInventoryRow;
import se.swedsoft.bookkeeping.data.SSOutdelivery;
import se.swedsoft.bookkeeping.data.SSOutdeliveryRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Complete normalized inventory and in/out-delivery snapshots. Does not read SSDB or allocate document numbers.
 * Writes require auto-commit disabled; local savepoints preserve the caller's earlier transaction work.
 * The caller owns the connection and transaction; this store never commits or closes either.
 */
public final class NormalizedStockDocumentStore {
    private enum Kind {
        INVENTORY("inventory", "inventory_date", "inventory_text"),
        IN("indelivery", "delivery_date", "delivery_text"),
        OUT("outdelivery", "delivery_date", "delivery_text");

        final String table;
        final String date;
        final String text;

        Kind(String table, String date, String text) {
            this.table = table;
            this.date = date;
            this.text = text;
        }

        String parent() { return table + "_id"; }
    }

    /**
     * Storage identity, migration identity and detached editable snapshot. Use id plus company scope for writes,
     * not nullable or duplicate historical document numbers. Both identities remain stable on updates.
     * @param id SQL storage identity
     * @param legacyId migration identity
     * @param document detached snapshot
     * @param <T> inventory, indelivery or outdelivery
     */
    public record Stored<T>(long id, int legacyId, T document) {}

    private record Line(String product, Integer stock, Integer change) {}
    private record Snapshot(Integer number, LocalDate date, String text, List<Line> rows) {}
    private final Connection connection;

    /** Uses the caller-owned normalized connection and transaction. */
    public NormalizedStockDocumentStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** Loads complete inventory snapshots for one company, in storage order. */
    public List<Stored<SSInventory>> getInventories(int company) throws SQLException {
        return read(Kind.INVENTORY, company, null).stream().map(value -> map(value,
                NormalizedStockDocumentStore::inventory)).toList();
    }

    /** Loads complete incoming-delivery snapshots for one company, in storage order. */
    public List<Stored<SSIndelivery>> getIndeliveries(int company) throws SQLException {
        return read(Kind.IN, company, null).stream().map(value -> map(value,
                NormalizedStockDocumentStore::incoming)).toList();
    }

    /** Loads complete outgoing-delivery snapshots for one company, in storage order. */
    public List<Stored<SSOutdelivery>> getOutdeliveries(int company) throws SQLException {
        return read(Kind.OUT, company, null).stream().map(value -> map(value,
                NormalizedStockDocumentStore::outgoing)).toList();
    }

    /** Finds an inventory by company and storage identity. */
    public Optional<Stored<SSInventory>> findInventory(int company, long id) throws SQLException {
        return read(Kind.INVENTORY, company, id).stream().findFirst()
                .map(value -> map(value, NormalizedStockDocumentStore::inventory));
    }

    /** Finds an incoming delivery by company and storage identity. */
    public Optional<Stored<SSIndelivery>> findIndelivery(int company, long id) throws SQLException {
        return read(Kind.IN, company, id).stream().findFirst()
                .map(value -> map(value, NormalizedStockDocumentStore::incoming));
    }

    /** Finds an outgoing delivery by company and storage identity. */
    public Optional<Stored<SSOutdelivery>> findOutdelivery(int company, long id) throws SQLException {
        return read(Kind.OUT, company, id).stream().findFirst()
                .map(value -> map(value, NormalizedStockDocumentStore::outgoing));
    }

    /** Inserts an inventory atomically, returning its storage identity; numbering remains caller-owned. */
    public long addInventory(int company, SSInventory document) throws SQLException {
        return insert(Kind.INVENTORY, company, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Inserts an incoming delivery atomically, returning its storage identity. */
    public long addIndelivery(int company, SSIndelivery document) throws SQLException {
        return insert(Kind.IN, company, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Inserts an outgoing delivery atomically, returning its storage identity. */
    public long addOutdelivery(int company, SSOutdelivery document) throws SQLException {
        return insert(Kind.OUT, company, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Replaces an inventory header and ordered rows atomically without changing either identity. */
    public void updateInventory(int company, long id, SSInventory document) throws SQLException {
        update(Kind.INVENTORY, company, id, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Replaces an incoming delivery atomically without changing either identity. */
    public void updateIndelivery(int company, long id, SSIndelivery document) throws SQLException {
        update(Kind.IN, company, id, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Replaces an outgoing delivery atomically without changing either identity. */
    public void updateOutdelivery(int company, long id, SSOutdelivery document) throws SQLException {
        update(Kind.OUT, company, id, snapshot(Objects.requireNonNull(document, "document")));
    }

    /** Deletes an inventory and its rows atomically; rejects missing/foreign-company identities. */
    public void deleteInventory(int company, long id) throws SQLException { delete(Kind.INVENTORY, company, id); }

    /** Deletes an incoming delivery and its rows atomically; rejects missing/foreign-company identities. */
    public void deleteIndelivery(int company, long id) throws SQLException { delete(Kind.IN, company, id); }

    /** Deletes an outgoing delivery and its rows atomically; rejects missing/foreign-company identities. */
    public void deleteOutdelivery(int company, long id) throws SQLException { delete(Kind.OUT, company, id); }

    private List<Stored<Snapshot>> read(Kind kind, int company, Long selectedId) throws SQLException {
        Map<Long, Stored<Snapshot>> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,legacy_id,number,"
                + kind.date + "," + kind.text + " FROM " + kind.table
                + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                + (selectedId == null ? "" : " AND id=?") + " ORDER BY id")) {
            statement.setInt(1, company);
            if (selectedId != null) statement.setLong(2, selectedId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    stored.put(id, new Stored<>(id, rows.getInt("legacy_id"), new Snapshot(integer(rows, "number"),
                            rows.getObject(kind.date, LocalDate.class), rows.getString(kind.text), new ArrayList<>())));
                }
            }
        }
        if (!stored.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT r.* FROM " + kind.table
                    + "_row r JOIN " + kind.table + " p ON p.id=r." + kind.parent()
                    + " WHERE p.company_id=(SELECT id FROM company WHERE legacy_id=?)"
                    + (selectedId == null ? "" : " AND p.id=?") + " ORDER BY r." + kind.parent() + ",r.row_number")) {
                statement.setInt(1, company);
                if (selectedId != null) statement.setLong(2, selectedId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var value = stored.get(rows.getLong(kind.parent()));
                        if (value != null) value.document().rows().add(new Line(rows.getString("product_number"),
                                kind == Kind.INVENTORY ? integer(rows, "stock_quantity") : null,
                                integer(rows, "change_quantity")));
                    }
                }
            }
        }
        return new ArrayList<>(stored.values());
    }

    private long insert(Kind kind, int company, Snapshot snapshot) throws SQLException {
        return atomic(() -> {
            int legacyId;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT COALESCE(MAX(legacy_id),-1)+1 FROM " + kind.table)) {
                rows.next();
                long next = rows.getLong(1);
                if (next > Integer.MAX_VALUE) throw new SQLException("Stock-document migration identity exhausted");
                legacyId = (int) next;
            }
            long id;
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table
                    + " (legacy_id,company_id,number," + kind.date + "," + kind.text
                    + ") SELECT ?,id,?,?,? FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, Arrays.asList(legacyId, snapshot.number(), snapshot.date(), snapshot.text(), company));
                if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + company);
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing stock-document storage identity");
                    id = keys.getLong(1);
                }
            }
            writeRows(kind, id, snapshot.rows());
            return id;
        });
    }

    private void update(Kind kind, int company, long id, Snapshot snapshot) throws SQLException {
        atomic(() -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + kind.table + " SET number=?,"
                    + kind.date + "=?," + kind.text + "=? WHERE id=?"
                    + " AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                bind(statement, Arrays.asList(snapshot.number(), snapshot.date(), snapshot.text(), id, company));
                if (statement.executeUpdate() != 1) throw missing(kind, company, id);
            }
            deleteRows(kind, id);
            writeRows(kind, id, snapshot.rows());
            return null;
        });
    }

    private void delete(Kind kind, int company, long id) throws SQLException {
        atomic(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + kind.table
                    + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                bind(statement, List.of(id, company));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw missing(kind, company, id);
                }
            }
            deleteRows(kind, id);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + kind.table
                    + " WHERE id=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
                bind(statement, List.of(id, company));
                if (statement.executeUpdate() != 1) throw missing(kind, company, id);
            }
            return null;
        });
    }

    private void deleteRows(Kind kind, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + kind.table
                + "_row WHERE " + kind.parent() + "=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private void writeRows(Kind kind, long id, List<Line> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table + "_row ("
                + kind.parent() + ",row_number,product_number,"
                + (kind == Kind.INVENTORY ? "stock_quantity," : "") + "change_quantity) VALUES ("
                + (kind == Kind.INVENTORY ? "?,?,?,?,?" : "?,?,?,?") + ")")) {
            int number = 0;
            for (Line row : rows) {
                List<Object> values = new ArrayList<>(Arrays.asList(id, number++, row.product()));
                if (kind == Kind.INVENTORY) values.add(row.stock());
                values.add(row.change());
                bind(statement, values);
                statement.executeUpdate();
            }
        }
    }

    private static Snapshot snapshot(SSInventory document) {
        return new Snapshot(document.getNumber(), document.getLocalDate(), document.getText(),
                document.getRows().stream()
                .map(row -> new Line(row.getProductNr(), row.getStockQuantity(), row.getChange())).toList());
    }

    private static Snapshot snapshot(SSIndelivery document) {
        return new Snapshot(document.getNumber(), document.getLocalDate(), document.getText(),
                document.getRows().stream()
                .map(row -> new Line(row.getProductNr(), null, row.getChange())).toList());
    }

    private static Snapshot snapshot(SSOutdelivery document) {
        return new Snapshot(document.getNumber(), document.getLocalDate(), document.getText(),
                document.getRows().stream()
                .map(row -> new Line(row.getProductNr(), null, row.getChange())).toList());
    }

    private static SSInventory inventory(Snapshot snapshot) {
        SSInventory document = SSInventory.forPersistence();
        document.setNumber(snapshot.number());
        document.setLocalDate(snapshot.date());
        document.setText(snapshot.text());
        for (Line line : snapshot.rows()) {
            SSInventoryRow row = new SSInventoryRow();
            row.setProductNr(line.product());
            row.setStockQuantity(line.stock());
            row.setChange(line.change());
            document.getRows().add(row);
        }
        return document;
    }

    private static SSIndelivery incoming(Snapshot snapshot) {
        SSIndelivery document = SSIndelivery.forPersistence();
        document.setNumber(snapshot.number());
        document.setLocalDate(snapshot.date());
        document.setText(snapshot.text());
        for (Line line : snapshot.rows()) {
            SSIndeliveryRow row = new SSIndeliveryRow();
            row.setProductNr(line.product());
            row.setChange(line.change());
            document.getRows().add(row);
        }
        return document;
    }

    private static SSOutdelivery outgoing(Snapshot snapshot) {
        SSOutdelivery document = SSOutdelivery.forPersistence();
        document.setNumber(snapshot.number());
        document.setLocalDate(snapshot.date());
        document.setText(snapshot.text());
        for (Line line : snapshot.rows()) {
            SSOutdeliveryRow row = new SSOutdeliveryRow();
            row.setProductNr(line.product());
            row.setChange(line.change());
            document.getRows().add(row);
        }
        return document;
    }

    private static <T> Stored<T> map(Stored<Snapshot> value, Function<Snapshot, T> hydrate) {
        return new Stored<>(value.id(), value.legacyId(), hydrate.apply(value.document()));
    }

    private static void bind(PreparedStatement statement, List<?> values) throws SQLException {
        int index = 1;
        for (Object value : values) statement.setObject(index++, value);
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static SQLException missing(Kind kind, int company, long id) {
        return new SQLException("Missing " + kind.table + " " + id + " in company " + company);
    }

    private <T> T atomic(SqlOperation<T> operation) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Stock-document writes require auto-commit disabled");
        Savepoint savepoint = connection.setSavepoint();
        try {
            T value = operation.run();
            connection.releaseSavepoint(savepoint);
            return value;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                // HSQLDB discards the target savepoint when rolling back to it.
                connection.rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }
}
