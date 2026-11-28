package org.fribok.bookkeeping.dataformat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.service.vat.VatSettlementHistory;
import org.fribok.bookkeeping.service.vat.VatSettlementHistory.Period;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedVatHistoryReaderTest {
    private static final LocalDate FIRST = LocalDate.of(2024, 7, 1);
    private static final LocalDate LAST = LocalDate.of(2025, 6, 30);
    private static final Instant AT = Instant.parse("2026-11-28T09:30:00.123456789Z");
    private final NormalizedVatHistoryReader reader = new NormalizedVatHistoryReader();

    private static Connection connection(int versions) throws Exception {
        var c = DriverManager.getConnection("jdbc:hsqldb:mem:history_" + UUID.randomUUID(), "sa", "");
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        new SchemaMigrationRunner(Clock.fixed(AT, ZoneOffset.UTC)).migrate(c,
                NormalizedSchemaMigrations.load().subList(0, versions));
        return c;
    }

    private static void sql(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static long insert(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) {
                s.setObject(i + 1, values[i]);
            }
            s.executeUpdate();
            try (var keys = s.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long company(Connection c, int legacy) throws SQLException {
        return insert(c, "INSERT INTO company(legacy_id,name) VALUES(?,?)", legacy, "Example");
    }

    private static long year(Connection c, long company, int legacy) throws SQLException {
        return insert(c, "INSERT INTO accounting_year(company_id,legacy_id,starts_on,ends_on) VALUES(?,?,?,?)",
                company, legacy, FIRST, LAST);
    }

    private static long evidence(Connection c, long company, long year) throws SQLException {
        return insert(c, """
                INSERT INTO vat_history_evidence(company_id,accounting_year_id,fiscal_first,fiscal_last,
                  recorded_at,recorded_by,evidence_reference) VALUES(?,?,?,?,?,?,?)
                """, company, year, FIRST, LAST, OffsetDateTime.ofInstant(AT, ZoneOffset.UTC),
                "Named reviewer", "archive:manual-review/75");
    }

    private static void span(Connection c, String table, long evidence, int position,
            LocalDate first, LocalDate last) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO " + table
                + "(evidence_id,position,period_first,period_last) VALUES(?,?,?,?)")) {
            s.setLong(1, evidence);
            s.setInt(2, position);
            s.setObject(3, first);
            s.setObject(4, last);
            s.executeUpdate();
        }
    }

    @Test
    void upgradesV21WithoutInferringHistoryOrChangingExistingAccountingRowsTest() throws Exception {
        try (var c = connection(21)) {
            long company = company(c, 0);
            long year = year(c, company, 0);
            insert(c, "INSERT INTO voucher(accounting_year_id,number,voucher_date,description) VALUES(?,?,?,?)",
                    year, 1, FIRST, "VAT settlement period July - not authoritative metadata");
            c.commit();
            assertEquals(List.of(22), new SchemaMigrationRunner(Clock.systemUTC())
                    .migrate(c, NormalizedSchemaMigrations.load()));
            assertTrue(new SchemaMigrationRunner(Clock.systemUTC())
                    .migrate(c, NormalizedSchemaMigrations.load()).isEmpty());
            var history = reader.read(c, 0, 0);
            assertTrue(history.evidence().isEmpty());
            assertEquals(company, history.companyRow());
            assertEquals(year, history.yearRow());
            assertEquals(0, history.scope().company());
            var unknown = new VatSettlementHistory.Snapshot(history.scope(), List.of(), List.of());
            assertEquals(VatSettlementHistory.Kind.UNREVIEWED_COVERAGE,
                    VatSettlementHistory.inspect(history.scope(), history.scope().fiscalYear(), unknown)
                            .get(0).kind());
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT * FROM voucher")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("number"));
                assertEquals("VAT settlement period July - not authoritative metadata",
                        rows.getString("description"));
                assertEquals(FIRST, rows.getObject("voucher_date", LocalDate.class));
                assertTrue(!rows.next());
            }
        }
    }

    @Test
    void loadsExactAttributionNanosecondsAndOrderedDuplicateClaimsWithoutAcceptingThemTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            long id = evidence(c, company, year);
            span(c, "vat_history_coverage", id, 2, FIRST.plusDays(30), LAST);
            span(c, "vat_history_coverage", id, 1, FIRST, FIRST.plusDays(29));
            span(c, "vat_history_known_settlement", id, 1, FIRST, FIRST.plusDays(29));
            span(c, "vat_history_known_settlement", id, 2, FIRST, FIRST.plusDays(29));
            var h = reader.read(c, 7, 11);
            assertEquals(new Period(FIRST, LAST), h.scope().fiscalYear());
            var e = h.evidence().get(0);
            assertEquals(id, e.id());
            assertEquals(AT, e.recordedAt());
            assertEquals("Named reviewer", e.recordedBy());
            assertEquals("archive:manual-review/75", e.reference());
            assertEquals(List.of(new Period(FIRST, FIRST.plusDays(29)),
                    new Period(FIRST.plusDays(30), LAST)), e.claimedCoverage());
            assertEquals(List.of(new Period(FIRST, FIRST.plusDays(29)),
                    new Period(FIRST, FIRST.plusDays(29))), e.knownSettlements());
            assertThrows(UnsupportedOperationException.class, () -> h.evidence().clear());
            assertThrows(UnsupportedOperationException.class, () -> e.claimedCoverage().clear());
            assertThrows(UnsupportedOperationException.class, () -> e.knownSettlements().clear());
        }
    }

    @Test
    void separatesSqlBigintKeysFromLegacyIdentifiersAndIsolatesCompanyAndYearTest() throws Exception {
        try (var c = connection(22)) {
            sql(c, "ALTER TABLE company ALTER COLUMN id RESTART WITH 3000000000");
            sql(c, "ALTER TABLE accounting_year ALTER COLUMN id RESTART WITH 4000000000");
            long first = company(c, 0);
            long firstYear = year(c, first, 0);
            long second = company(c, -1);
            long secondYear = year(c, second, -1);
            evidence(c, second, secondYear);
            assertTrue(reader.read(c, 0, 0).evidence().isEmpty());
            var selected = reader.read(c, -1, -1);
            assertEquals(second, selected.companyRow());
            assertEquals(secondYear, selected.yearRow());
            assertTrue(selected.yearRow() > Integer.MAX_VALUE);
            assertEquals(1, selected.evidence().size());
            assertThrows(SQLException.class, () -> reader.read(c, 0, -1));
            assertThrows(SQLException.class, () -> reader.read(c, -1, 0));
            assertThrows(SQLException.class, () -> evidence(c, first, secondYear));
            sql(c, "UPDATE accounting_year SET starts_on=DATE '2023-07-01', "
                    + "ends_on=DATE '2024-06-30' WHERE id=" + firstYear);
            long another = year(c, first, 9);
            evidence(c, first, another);
            assertTrue(reader.read(c, 0, 0).evidence().isEmpty());
            assertEquals(firstYear, reader.read(c, 0, 0).yearRow());
        }
    }

    @Test
    void missingEvidenceSchemaIsAReadFailureNotEmptyHistoryTest() throws Exception {
        try (var c = connection(21)) {
            long company = company(c, 7);
            year(c, company, 11);
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
        }
    }

    @Test
    void missingSelectionsAndNullFiscalDatesFailClosedTest() throws Exception {
        try (var c = connection(22)) {
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
            long company = company(c, 7);
            long year = year(c, company, 11);
            sql(c, "UPDATE accounting_year SET starts_on=NULL WHERE id=" + year);
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
        }
    }

    @Test
    void rejectsChangedFiscalBoundsRatherThanSilentlyReusingOldEvidenceTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            evidence(c, company, year);
            sql(c, "UPDATE accounting_year SET ends_on=DATE '2025-06-29' WHERE id=" + year);
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
        }
    }

    @Test
    void rejectsOutOfYearCoverageAndKnownPeriodsAndMissingPositionsTest() throws Exception {
        for (String table : List.of("vat_history_coverage", "vat_history_known_settlement")) {
            try (var c = connection(22)) {
                long company = company(c, 7);
                long year = year(c, company, 11);
                long id = evidence(c, company, year);
                span(c, table, id, 1, FIRST.minusDays(1), FIRST);
                assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
                sql(c, "DELETE FROM " + table);
                span(c, table, id, 2, FIRST, LAST);
                assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
            }
        }
    }

    @Test
    void enforcesForeignKeysAndRequiredAttributionAndValidPeriodShapeTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            long id = evidence(c, company, year);
            assertThrows(SQLException.class, () -> sql(c,
                    "UPDATE vat_history_evidence SET recorded_by=' '"));
            assertThrows(SQLException.class, () -> sql(c,
                    "UPDATE vat_history_evidence SET evidence_reference=NULL"));
            assertThrows(SQLException.class, () -> sql(c,
                    "UPDATE vat_history_evidence SET fiscal_last=DATE '2024-06-30'"));
            assertThrows(SQLException.class, () -> sql(c,
                    "UPDATE vat_history_evidence SET recorded_at=NULL"));
            for (String table : List.of("vat_history_coverage", "vat_history_known_settlement")) {
                assertThrows(SQLException.class, () -> span(c, table, id + 1, 1, FIRST, LAST));
                assertThrows(SQLException.class, () -> span(c, table, id, 0, FIRST, LAST));
                assertThrows(SQLException.class, () -> span(c, table, id, 1, LAST, FIRST));
                assertThrows(SQLException.class, () -> span(c, table, id, 1, null, LAST));
                span(c, table, id, 1, FIRST, LAST);
                assertThrows(SQLException.class, () -> span(c, table, id, 1, FIRST, LAST));
            }
        }
    }

    @Test
    void attributionThatSqlTrimDoesNotCatchStillFailsClosedAtReadTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            evidence(c, company, year);
            sql(c, "UPDATE vat_history_evidence SET recorded_by=CHAR(9)");
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
        }
    }

    @Test
    void preservesSeparateEvidenceRecordsRatherThanUnioningThemIntoAnAcceptedBaselineTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            long first = evidence(c, company, year);
            long second = evidence(c, company, year);
            span(c, "vat_history_coverage", first, 1, FIRST, LAST);
            span(c, "vat_history_known_settlement", second, 1, FIRST, LAST);
            var history = reader.read(c, 7, 11);
            assertEquals(List.of(first, second), history.evidence().stream().map(e -> e.id()).toList());
            assertTrue(history.evidence().get(0).knownSettlements().isEmpty());
            assertTrue(history.evidence().get(1).claimedCoverage().isEmpty());
        }
    }

    @Test
    void rollbackRemovesUncommittedEvidenceAndFrozenReadDoesNotChangeTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            c.commit();
            long id = evidence(c, company, year);
            span(c, "vat_history_coverage", id, 1, FIRST, LAST);
            var frozen = reader.read(c, 7, 11);
            c.rollback();
            assertTrue(reader.read(c, 7, 11).evidence().isEmpty());
            assertEquals(1, frozen.evidence().size());
            assertEquals(List.of(new Period(FIRST, LAST)), frozen.evidence().get(0).claimedCoverage());
        }
    }

    @Test
    void yearAndCompanyDeletionCascadeOnlyTheirEvidenceTest() throws Exception {
        try (var c = connection(22)) {
            long first = company(c, 7);
            long firstYear = year(c, first, 11);
            long second = company(c, 8);
            long secondYear = year(c, second, 12);
            for (long id : List.of(evidence(c, first, firstYear), evidence(c, second, secondYear))) {
                span(c, "vat_history_coverage", id, 1, FIRST, LAST);
                span(c, "vat_history_known_settlement", id, 1, FIRST, LAST);
            }
            new NormalizedAccountingWriter(Clock.systemUTC()).deleteCompany(c, 7);
            assertEquals(1, reader.read(c, 8, 12).evidence().size());
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
            sql(c, "DELETE FROM accounting_year WHERE id=" + secondYear);
            for (String table : List.of("vat_history_evidence", "vat_history_coverage",
                    "vat_history_known_settlement")) {
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void committedEvidenceSurvivesFileDatabaseShutdownAndReopenTest(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("history").toAbsolutePath();
        NormalizedVatHistoryReader.History frozen;
        try (var c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            new SchemaMigrationRunner(Clock.systemUTC()).migrate(c, NormalizedSchemaMigrations.load());
            long company = company(c, 0);
            long year = year(c, company, 0);
            long id = evidence(c, company, year);
            span(c, "vat_history_coverage", id, 1, FIRST, LAST);
            span(c, "vat_history_known_settlement", id, 1, FIRST, FIRST.plusDays(29));
            c.commit();
            frozen = reader.read(c, 0, 0);
            c.rollback();
            sql(c, "SHUTDOWN");
        }
        try (var c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals(frozen, reader.read(c, 0, 0));
            c.rollback();
            sql(c, "SHUTDOWN");
        }
    }

    @Test
    void requiresSerializableCallerTransactionWithoutChangingItTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            year(c, company, 11);
            c.commit();
            c.setAutoCommit(true);
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
            assertTrue(c.getAutoCommit());
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            assertThrows(SQLException.class, () -> reader.read(c, 7, 11));
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        }
    }

    @Test
    void readUsesOnlySelectsAndNeverCommitsRollsBackClosesOrSetsConnectionStateTest() throws Exception {
        try (var c = connection(22)) {
            long company = company(c, 7);
            long year = year(c, company, 11);
            evidence(c, company, year);
            var reads = new AtomicInteger();
            var guarded = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (List.of("commit", "rollback", "close", "createStatement", "prepareCall")
                                .contains(method.getName()) || method.getName().startsWith("set")) {
                            throw new AssertionError("Unexpected connection mutation " + method.getName());
                        }
                        if (method.getName().equals("prepareStatement")) {
                            assertTrue(((String) args[0]).stripLeading().startsWith("SELECT"));
                            reads.incrementAndGet();
                        }
                        try {
                            return method.invoke(c, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            assertEquals(1, reader.read(guarded, 7, 11).evidence().size());
            assertEquals(4, reads.get());
            assertTrue(!c.isClosed());
            assertTrue(!c.getAutoCommit());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
        }
    }
}
