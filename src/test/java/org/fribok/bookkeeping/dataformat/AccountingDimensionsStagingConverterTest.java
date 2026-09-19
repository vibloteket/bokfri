package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingDimensionsStagingConverterTest {

    @Test
    void roundTripsCompanyScopedProjectsAndResultUnits() throws Exception {
        try (Connection legacy = connection("dimensions_legacy");
             Connection target = connection("dimensions_target")) {
            createLegacySchema(legacy);
            new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                    .migrate(target, NormalizedSchemaMigrations.load());
            SSNewCompany first = company(1, "First");
            SSNewCompany second = company(2, "Second");
            insertCompany(legacy, first); insertCompany(legacy, second);
            new AccountingCoreStagingConverter().convert(legacy, target);

            insertProject(legacy, 1, project("A", "Ångström", true,
                    LocalDate.of(2026, 6, 30)));
            insertProject(legacy, 2, project("A", "Same key other company", false, null));
            insertResultUnit(legacy, 1, unit("R1", "Nord", ""));
            legacy.commit();

            var result = new AccountingDimensionsStagingConverter().convert(legacy, target);
            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());
            assertThat(result.projects()).isEqualTo(2);
            assertThat(result.resultUnits()).isEqualTo(1);
            assertThat(count(target, "project")).isEqualTo(2);
            assertThat(count(target, "result_unit")).isEqualTo(1);
        }
    }

    @Test
    void rejectsMismatchBetweenLegacyTableKeyAndSerializedObject() throws Exception {
        try (Connection legacy = connection("dimensions_bad_legacy");
             Connection target = connection("dimensions_bad_target")) {
            createLegacySchema(legacy);
            new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                    .migrate(target, NormalizedSchemaMigrations.load());
            insertCompany(legacy, company(1, "First"));
            new AccountingCoreStagingConverter().convert(legacy, target);
            SSNewProject project = project("object-key", "Broken", false, null);
            try (var statement = legacy.prepareStatement(
                    "INSERT INTO tbl_project (number,project,companyid) VALUES (?,?,?)")) {
                statement.setString(1, "table-key"); statement.setObject(2, project);
                statement.setInt(3, 1); statement.executeUpdate(); legacy.commit();
            }
            assertThatThrownBy(() -> new AccountingDimensionsStagingConverter()
                    .convert(legacy, target)).isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("Project key/object mismatch");
        }
    }

    private static SSNewCompany company(int id, String name) {
        SSNewCompany company = new SSNewCompany(); company.setId(id); company.setName(name);
        return company;
    }

    private static SSNewProject project(String number, String name, boolean concluded,
                                        LocalDate date) {
        SSNewProject project = new SSNewProject(number, name, null);
        project.setConcluded(concluded); project.setLocalConcludedDate(date); return project;
    }

    private static SSNewResultUnit unit(String number, String name, String description) {
        SSNewResultUnit unit = new SSNewResultUnit(number, name);
        unit.setDescription(description); return unit;
    }

    private static void insertCompany(Connection connection, SSNewCompany company) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_company (id,company) VALUES (?,?)")) {
            statement.setInt(1, company.getId()); statement.setObject(2, company);
            statement.executeUpdate(); connection.commit();
        }
    }

    private static void insertProject(Connection connection, int company, SSNewProject project)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_project (number,project,companyid) VALUES (?,?,?)")) {
            statement.setString(1, project.getNumber()); statement.setObject(2, project);
            statement.setInt(3, company); statement.executeUpdate();
        }
    }

    private static void insertResultUnit(Connection connection, int company, SSNewResultUnit unit)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_resultunit (number,resultunit,companyid) VALUES (?,?,?)")) {
            statement.setString(1, unit.getNumber()); statement.setObject(2, unit);
            statement.setInt(3, company); statement.executeUpdate();
        }
    }

    private static Connection connection(String name) throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:" + name + "_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false); return connection;
    }

    private static void createLegacySchema(Connection connection) throws Exception {
        try (InputStream input = AccountingDimensionsStagingConverterTest.class
                .getResourceAsStream("/sql/create_tables.sql")) {
            assertThat(input).isNotNull();
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (var statement = connection.createStatement()) {
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql.trim());
            }
            connection.commit();
        }
    }

    private static long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next(); return result.getLong(1);
        }
    }
}
