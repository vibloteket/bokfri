package org.fribok.bookkeeping.application;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lease-owned read-only journal projections. Never hydrates legacy models or repairs stored data.
 */
public final class NormalizedVoucherRegister implements AutoCloseable {
    public static final int PAGE_SIZE = 100;

    /**
     * SQL identity and raw stored header values; correction numbers are scoped presentation only.
     */
    public record Header(
            long id,
            int number,
            LocalDate date,
            String description,
            Long correctsId,
            Integer correctsNumber,
            Long correctedById,
            Integer correctedByNumber) {}

    /** Descending-number keyset page; null nextBefore means no further page in this read. */
    public record Page(List<Header> headers, Integer nextBefore) {
        public Page {
            headers = List.copyOf(headers);
        }
    }

    /**
     * Raw ordered row plus separately labelled current catalogue metadata (not historical labels).
     */
    public record Row(
            int number,
            Long accountId,
            Integer accountNumber,
            String accountDescription,
            BigDecimal debit,
            BigDecimal credit,
            String project,
            String projectName,
            boolean projectExists,
            String unit,
            String unitName,
            boolean unitExists,
            OffsetDateTime editedAt,
            String signature,
            boolean crossed,
            boolean added) {}

    /** One read-transaction snapshot of the header, rows and current labels. */
    public record Detail(Header header, List<Row> rows) {
        public Detail {
            rows = List.copyOf(rows);
        }
    }

    @FunctionalInterface
    private interface Read<T> {
        T run(Connection connection, long year) throws SQLException;
    }

    private final NormalizedApplicationOwner.Connections connections;
    private final Object gate;
    private final NormalizedApplicationOwner.Selection selection;
    private volatile boolean active = true;

    NormalizedVoucherRegister(
            NormalizedApplicationOwner.Connections connections,
            Object gate,
            NormalizedApplicationOwner.Selection selection) {
        this.connections = connections;
        this.gate = gate;
        this.selection = selection;
    }

    private static final String HEADERS =
            "SELECT v.id,v.number,v.voucher_date,v.description,v.corrects_voucher_id,c.number,"
                    + "v.corrected_by_voucher_id,b.number FROM voucher v LEFT JOIN voucher c ON"
                    + " c.id=v.corrects_voucher_id AND c.accounting_year_id=v.accounting_year_id"
                    + " LEFT JOIN voucher b ON b.id=v.corrected_by_voucher_id AND"
                    + " b.accounting_year_id=v.accounting_year_id WHERE v.accounting_year_id=?";

    /** Explicit refresh/next-page read; no implicit company/year or storage writes. Run off EDT. */
    public Page page(Integer beforeNumber) throws SQLException {
        return read(
                (connection, year) -> {
                    List<Header> headers = new ArrayList<>();
                    try (var query =
                            connection.prepareStatement(
                                    HEADERS
                                            + (beforeNumber == null ? "" : " AND v.number<?")
                                            + " ORDER BY v.number DESC LIMIT "
                                            + (PAGE_SIZE + 1))) {
                        query.setLong(1, year);
                        if (beforeNumber != null) query.setInt(2, beforeNumber);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) headers.add(header(rows));
                        }
                    }
                    boolean more = headers.size() > PAGE_SIZE;
                    if (more) headers.remove(PAGE_SIZE);
                    return new Page(headers, more ? headers.get(PAGE_SIZE - 1).number() : null);
                });
    }

    /** Reopens by SQL identity, never by display number. Foreign/missing identities are refused. */
    public Detail detail(long id) throws SQLException {
        return read(
                (connection, year) -> {
                    Header header;
                    try (var query = connection.prepareStatement(HEADERS + " AND v.id=?")) {
                        query.setLong(1, year);
                        query.setLong(2, id);
                        try (var rows = query.executeQuery()) {
                            if (!rows.next()) {
                                throw new SQLException("Voucher missing from selected year");
                            }
                            header = header(rows);
                        }
                    }
                    List<Row> result = new ArrayList<>();
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT r.row_number, r.account_id, a.number, a.description,"
                                        + " r.debit, r.credit, r.project_number, p.name, p.number,"
                                        + " r.result_unit_number, u.name, u.number, r.edited_at,"
                                        + " r.edited_signature, r.crossed, r.added FROM voucher_row"
                                        + " r LEFT JOIN account a ON a.id=r.account_id AND"
                                        + " a.accounting_year_id=r.accounting_year_id LEFT JOIN"
                                        + " accounting_year y ON y.id=r.accounting_year_id LEFT"
                                        + " JOIN project p ON p.company_id=y.company_id AND"
                                        + " p.number=r.project_number LEFT JOIN result_unit u ON"
                                        + " u.company_id=y.company_id AND"
                                        + " u.number=r.result_unit_number WHERE"
                                        + " r.accounting_year_id=? AND r.voucher_id=? ORDER BY"
                                        + " r.row_number")) {
                        query.setLong(1, year);
                        query.setLong(2, id);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                result.add(
                                        new Row(
                                                rows.getInt(1),
                                                rows.getObject(2, Long.class),
                                                rows.getObject(3, Integer.class),
                                                rows.getString(4),
                                                rows.getBigDecimal(5),
                                                rows.getBigDecimal(6),
                                                rows.getString(7),
                                                rows.getString(8),
                                                rows.getObject(9) != null,
                                                rows.getString(10),
                                                rows.getString(11),
                                                rows.getObject(12) != null,
                                                rows.getObject(13, OffsetDateTime.class),
                                                rows.getString(14),
                                                rows.getBoolean(15),
                                                rows.getBoolean(16)));
                            }
                        }
                    }
                    return new Detail(header, result);
                });
    }

    private static Header header(ResultSet rows) throws SQLException {
        return new Header(
                rows.getLong(1),
                rows.getInt(2),
                rows.getObject(3, LocalDate.class),
                rows.getString(4),
                rows.getObject(5, Long.class),
                rows.getObject(6, Integer.class),
                rows.getObject(7, Long.class),
                rows.getObject(8, Integer.class));
    }

    private long year(Connection connection) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT y.id,y.starts_on,y.ends_on FROM accounting_year y JOIN company c ON"
                                + " c.id=y.company_id WHERE c.legacy_id=? AND y.legacy_id=?")) {
            query.setInt(1, selection.company().id());
            query.setInt(2, selection.year().id());
            try (var rows = query.executeQuery()) {
                if (!rows.next()
                        || !Objects.equals(
                                rows.getObject(2, LocalDate.class), selection.year().from())
                        || !Objects.equals(
                                rows.getObject(3, LocalDate.class), selection.year().to())) {
                    throw new SQLException("Selected company/year changed or no longer exists");
                }
                return rows.getLong(1);
            }
        }
    }

    private <T> T read(Read<T> action) throws SQLException {
        requireAvailable();
        synchronized (gate) {
            requireAvailable();
            try (var connection = connections.open()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    T value = action.run(connection, year(connection));
                    requireAvailable();
                    connection.rollback();
                    return value;
                } catch (SQLException | RuntimeException | Error failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            }
        }
    }

    private void requireAvailable() throws SQLException {
        if (!active || !selection.isActive() || selection.isBlocked()) {
            throw new SQLException("Voucher register context is closed, replaced or blocked");
        }
    }

    /** Retires reads without touching stored data or owning the application/database lifetime. */
    @Override
    public void close() {
        active = false;
    }
}
