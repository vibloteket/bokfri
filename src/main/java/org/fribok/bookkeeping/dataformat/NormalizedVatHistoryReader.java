package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Period;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Scope;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads recorded history claims only; never accepts reviews or produces a trusted baseline. */
public final class NormalizedVatHistoryReader {
    /** SQL BIGINT row identities are distinct from the legacy selection identifiers in scope. */
    public record History(long companyRow, long yearRow, Scope scope, List<Evidence> evidence) {
        public History {
            Objects.requireNonNull(scope, "scope");
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * Frozen recorded claims and attribution, not authenticated review/certification. Reference is
     * opaque text, not a verified voucher identity. Coverage is NOT automatically accepted coverage.
     */
    public record Evidence(
            long id, Instant recordedAt, String recordedBy, String reference,
            List<Period> claimedCoverage, List<Period> knownSettlements) {
        public Evidence {
            Objects.requireNonNull(recordedAt, "recorded at");
            Objects.requireNonNull(recordedBy, "recorded by");
            Objects.requireNonNull(reference, "reference");
            claimedCoverage = List.copyOf(claimedCoverage);
            knownSettlements = List.copyOf(knownSettlements);
        }
    }

    /**
     * Reads one explicit company/year, including empty history. Requires the caller's serializable
     * transaction to keep the scope, attribution and child lists consistent. Does not change
     * connection state or commit/rollback/close it. An absent selection, changed fiscal bounds or
     * malformed persisted facts fail closed. Nothing is inferred from vouchers or account balances.
     *
     * @param connection caller-owned serializable transaction, not the legacy singleton
     * @param company legacy selection identifier, including zero if stored
     * @param year legacy selection identifier, including zero if stored
     * @return immutable recorded evidence; never a policy Snapshot or posting capability
     * @throws SQLException if scope/evidence is invalid or the read/transaction is unavailable
     */
    public History read(Connection connection, int company, int year) throws SQLException {
        Objects.requireNonNull(connection, "explicit connection");
        if (connection.getAutoCommit()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("History reads require a caller-owned serializable transaction");
        }
        long companyRow;
        long yearRow;
        Period fiscal;
        try (var statement = connection.prepareStatement("""
                SELECT c.id, y.id, y.starts_on, y.ends_on
                FROM accounting_year y JOIN company c ON c.id=y.company_id
                WHERE c.legacy_id=? AND y.legacy_id=?
                """)) {
            statement.setInt(1, company);
            statement.setInt(2, year);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("History selection does not exist");
                }
                companyRow = rows.getLong(1);
                yearRow = rows.getLong(2);
                fiscal = period(rows.getObject(3, LocalDate.class), rows.getObject(4, LocalDate.class));
                if (rows.next()) {
                    throw new SQLException("Ambiguous history selection");
                }
            }
        }
        var evidence = new ArrayList<Evidence>();
        try (var statement = connection.prepareStatement("""
                SELECT id, fiscal_first, fiscal_last, recorded_at, recorded_by, evidence_reference
                FROM vat_history_evidence WHERE company_id=? AND accounting_year_id=? ORDER BY id
                """)) {
            statement.setLong(1, companyRow);
            statement.setLong(2, yearRow);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    var recordedFiscal = period(
                            rows.getObject(2, LocalDate.class), rows.getObject(3, LocalDate.class));
                    if (!fiscal.equals(recordedFiscal)) {
                        throw new SQLException("History evidence fiscal bounds no longer match");
                    }
                    OffsetDateTime recordedAt = rows.getObject(4, OffsetDateTime.class);
                    String actor = rows.getString(5);
                    String reference = rows.getString(6);
                    if (recordedAt == null || actor == null || actor.isBlank()
                            || reference == null || reference.isBlank()) {
                        throw new SQLException("Missing history evidence attribution");
                    }
                    evidence.add(new Evidence(id, recordedAt.toInstant(), actor, reference,
                            periods(connection, id, fiscal, true),
                            periods(connection, id, fiscal, false)));
                }
            }
        }
        return new History(companyRow, yearRow, new Scope(company, year, fiscal), evidence);
    }

    private static List<Period> periods(
            Connection connection, long evidence, Period fiscal, boolean coverage) throws SQLException {
        // Table is chosen internally, never interpolated from a caller-provided SQL identifier.
        String table = coverage ? "vat_history_coverage" : "vat_history_known_settlement";
        var periods = new ArrayList<Period>();
        try (var statement = connection.prepareStatement(
                "SELECT position, period_first, period_last FROM " + table
                        + " WHERE evidence_id=? ORDER BY position")) {
            statement.setLong(1, evidence);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt(1) != periods.size() + 1) {
                        throw new SQLException("History evidence positions are not consecutive");
                    }
                    var period = period(
                            rows.getObject(2, LocalDate.class), rows.getObject(3, LocalDate.class));
                    if (period.first().isBefore(fiscal.first()) || period.last().isAfter(fiscal.last())) {
                        throw new SQLException("History evidence period outside fiscal year");
                    }
                    periods.add(period);
                }
            }
        }
        return List.copyOf(periods);
    }

    private static Period period(LocalDate first, LocalDate last) throws SQLException {
        if (first == null || last == null || first.isAfter(last)) {
            throw new SQLException("Missing or reversed history dates");
        }
        return new Period(first, last);
    }
}
