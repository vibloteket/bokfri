package org.fribok.bookkeeping.service.invoice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import se.swedsoft.bookkeeping.data.SSInvoice;

/** Bounded unbooked editing; caller owns transaction, commit and exclusive application access. */
public final class NormalizedCustomerFinancialEditService {
    /** Document family; never inferred from a displayed business number. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /**
     * Immutable stored-row values; nullable amounts preserve SQL NULL.
     *
     * @param rowNumber persisted row key
     * @param description unchanged description
     * @param account unchanged nullable account number
     * @param quantity nullable exact quantity
     * @param unitPrice nullable exact unit price
     */
    public record Line(
            int rowNumber,
            String description,
            Integer account,
            BigDecimal quantity,
            BigDecimal unitPrice) {}

    /**
     * Immutable original values; no hydrated domain objects escape.
     *
     * @param companyId company migration identity
     * @param kind document family
     * @param id stable SQL identity
     * @param number nullable business number
     * @param customer nullable customer snapshot
     * @param currency nullable currency snapshot code
     * @param values original dates and complete row amounts
     * @param lines stored rows in key order
     * @param total original total with explicit company rounding
     */
    public record Data(
            int companyId,
            Kind kind,
            long id,
            Integer number,
            String customer,
            String currency,
            FinancialValues values,
            List<Line> lines,
            BigDecimal total) {
        public Data {
            lines = List.copyOf(lines);
        }
    }

    /** Opaque source token with immutable presentation values. */
    public static final class Snapshot {
        private final Data data;
        private final String signature;

        private Snapshot(Data data, String signature) {
            this.data = data;
            this.signature = signature;
        }

        /**
         * @return immutable original values
         */
        public Data data() {
            return data;
        }
    }

    /** Opaque preview binding a source token to exact proposed values and totals. */
    public static final class Plan {
        private final Snapshot snapshot;
        private final FinancialValues proposed;
        private final BigDecimal total;

        private Plan(Snapshot snapshot, FinancialValues proposed, BigDecimal total) {
            this.snapshot = snapshot;
            this.proposed = proposed;
            this.total = total;
        }

        /**
         * @return immutable original values
         */
        public Data original() {
            return snapshot.data;
        }

        /**
         * @return complete immutable proposed dates and row amounts
         */
        public FinancialValues proposed() {
            return proposed;
        }

        /**
         * @return total computed from proposed values and explicit company rounding
         */
        public BigDecimal total() {
            return total;
        }
    }

    /**
     * Verified write result; the caller still owns the outer commit.
     *
     * @param companyId company migration identity
     * @param kind document family
     * @param id stable SQL identity
     * @param total verified stored total
     */
    public record Saved(int companyId, Kind kind, long id, BigDecimal total) {}

    private record Source(SSInvoice document, Data data, boolean rounding, String signature) {}

    private final Connection connection;
    private final java.time.Clock clock;

    /**
     * Uses only the supplied context; never opens legacy storage.
     *
     * @param connection caller-owned connection
     * @param clock explicit accounting-store clock
     */
    public NormalizedCustomerFinancialEditService(Connection connection, java.time.Clock clock) {
        this.connection = Objects.requireNonNull(connection);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Reads an unbooked document without writes.
     *
     * @param company company migration identity
     * @param kind document family
     * @param id stable SQL identity
     * @return opaque original snapshot
     * @throws SQLException for a missing, foreign, booked or unsupported document
     */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        var source = read(company, kind, id);
        return new Snapshot(source.data, source.signature);
    }

    /**
     * Revalidates the source and calculates a complete proposed patch without writes.
     *
     * @param expected original snapshot
     * @param proposed dates and the complete unchanged stored row-key set
     * @return opaque totals preview
     * @throws SQLException for storage errors or unsupported document projection
     * @throws IllegalArgumentException for stale or invalid inputs
     */
    public Plan plan(Snapshot expected, FinancialValues proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "proposed");
        var before = expected.data;
        var source = read(before.companyId(), before.kind(), before.id());
        if (!expected.signature.equals(source.signature))
            throw new IllegalArgumentException("Financial source is stale; reload before editing");
        var indexed = indexed(proposed.rows());
        if (indexed.size() != source.data.lines().size())
            throw new IllegalArgumentException("Row insertion/removal is not supported");
        for (int i = 0; i < source.data.lines().size(); i++) {
            var line = source.data.lines().get(i);
            var values = indexed.remove(line.rowNumber());
            if (values == null)
                throw new IllegalArgumentException("Missing/foreign stored row key");
            source.document.getRows().get(i).setQuantity(values.quantity());
            source.document.getRows().get(i).setUnitprice(values.unitPrice());
        }
        if (!indexed.isEmpty()) throw new IllegalArgumentException("Foreign stored row key");
        return new Plan(
                expected,
                proposed,
                se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                        source.document, source.rounding));
    }

    /**
     * Revalidates, narrowly updates and verifies a preview under a savepoint; never commits.
     *
     * @param expected confirmed immutable preview
     * @return verified write identity and total
     * @throws SQLException for persistence, book-state or transaction errors
     * @throws IllegalArgumentException if the source or preview became stale
     */
    public Saved save(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit())
            throw new SQLException("Financial edits require a caller-owned transaction");
        Savepoint point = connection.setSavepoint();
        try {
            var fresh = plan(expected.snapshot, expected.proposed);
            var original = fresh.original();
            Map<Integer, LineAmounts> proposed = indexed(fresh.proposed.rows());
            List<LineAmounts> changed =
                    original.lines().stream()
                            .filter(
                                    line -> {
                                        var value = proposed.get(line.rowNumber());
                                        return !same(line.quantity(), value.quantity())
                                                || !same(line.unitPrice(), value.unitPrice());
                                    })
                            .map(line -> proposed.get(line.rowNumber()))
                            .toList();
            var values =
                    new FinancialValues(fresh.proposed.date(), fresh.proposed.dueDate(), changed);
            var store = new NormalizedInvoiceStore(connection);
            if (original.kind() == Kind.INVOICE)
                store.updateInvoiceFinancialValues(original.companyId(), original.id(), values);
            else store.updateCreditFinancialValues(original.companyId(), original.id(), values);
            var verified = read(original.companyId(), original.kind(), original.id()).data;
            if (!Objects.equals(verified.values().date(), fresh.proposed.date())
                    || !Objects.equals(verified.values().dueDate(), fresh.proposed.dueDate())
                    || verified.total().compareTo(fresh.total) != 0)
                throw new SQLException("Stored financial values differ from preview");
            for (var row : verified.lines()) {
                var wanted = proposed.get(row.rowNumber());
                if (wanted == null
                        || !same(row.quantity(), wanted.quantity())
                        || !same(row.unitPrice(), wanted.unitPrice()))
                    throw new SQLException("Stored line values differ from preview");
            }
            connection.releaseSavepoint(point);
            return new Saved(
                    original.companyId(), original.kind(), original.id(), verified.total());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(point);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private Source read(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedInvoiceStore(connection);
        SSInvoice document =
                kind == Kind.INVOICE
                        ? store.findInvoice(company, id)
                                .orElseThrow(() -> new SQLException("Missing or foreign invoice"))
                                .document()
                        : store.findCreditInvoice(company, id)
                                .orElseThrow(() -> new SQLException("Missing or foreign credit"))
                                .document();
        if (document.isEntered())
            throw new SQLException("Booked documents cannot be financially edited");
        boolean rounding =
                new NormalizedAccountingStore(connection, clock)
                        .getCompanies().stream()
                                .filter(c -> c.getId() == company)
                                .findFirst()
                                .orElseThrow(() -> new SQLException("Missing company"))
                                .isRoundingOff();
        String table =
                kind == Kind.INVOICE ? "customer_invoice_row" : "customer_credit_invoice_row";
        String owner = kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
        List<Line> lines = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT row_number,description,account_number,quantity,unit_price FROM "
                                + table
                                + " WHERE "
                                + owner
                                + "=? AND company_id=(SELECT id FROM company WHERE legacy_id=?)"
                                + " ORDER BY row_number")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    lines.add(
                            new Line(
                                    rows.getInt(1),
                                    rows.getString(2),
                                    rows.getObject(3, Integer.class),
                                    rows.getBigDecimal(4),
                                    rows.getBigDecimal(5)));
            }
        }
        if (lines.size() != document.getRows().size())
            throw new SQLException("Unsupported row projection");
        var values =
                new FinancialValues(
                        document.getLocalDate(),
                        document.getLocalDueDate(),
                        lines.stream()
                                .map(
                                        line ->
                                                new LineAmounts(
                                                        line.rowNumber(),
                                                        line.quantity(),
                                                        line.unitPrice()))
                                .toList());
        var data =
                new Data(
                        company,
                        kind,
                        id,
                        document.getNumber(),
                        document.getCustomerName(),
                        document.getCurrency() == null ? null : document.getCurrency().getName(),
                        values,
                        lines,
                        se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(
                                document, rounding));
        var writer = new CanonicalFingerprintWriter();
        writer.writeInteger(company).writeBoolean(rounding);
        CustomerDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
        return new Source(document, data, rounding, writer.finish());
    }

    private static Map<Integer, LineAmounts> indexed(List<LineAmounts> rows) {
        Map<Integer, LineAmounts> map = new HashMap<>();
        for (var row : rows)
            if (map.put(row.rowNumber(), row) != null)
                throw new IllegalArgumentException("Duplicate stored row key");
        return map;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
