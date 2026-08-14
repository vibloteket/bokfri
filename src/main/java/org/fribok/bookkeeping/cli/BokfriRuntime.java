package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Headless database lifecycle used by command-line operations. */
public final class BokfriRuntime implements AutoCloseable {
    private final SSDB database;

    private BokfriRuntime(SSDB database) {
        this.database = database;
    }

    public static BokfriRuntime open(Path dataDir)
            throws IOException, SQLException, ClassNotFoundException {
        Path databaseDirectory = dataDir.toAbsolutePath().normalize().resolve("db");
        Files.createDirectories(databaseDirectory);
        boolean databaseExisted = Files.exists(databaseDirectory.resolve("JFSDB.properties"));
        Class.forName("org.hsqldb.jdbcDriver");
        String databasePath = databaseDirectory.resolve("JFSDB").toString();
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + databasePath, "sa", "");
        try {
            connection.setAutoCommit(false);
            DataFormatManager.checkAndInitialize(connection, databaseExisted);
            SSDB database = SSDB.getInstance();
            database.startupLocal(connection);
            return new BokfriRuntime(database);
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    public SSDB database() {
        return database;
    }

    public SSNewCompany selectCompany(int companyId) {
        SSNewCompany company = database.getCompanies().stream()
                .filter(candidate -> candidate.getId().equals(companyId))
                .findFirst()
                .orElseThrow(() -> new CliException("COMPANY_NOT_FOUND",
                        "No company has id " + companyId));
        database.setCurrentCompany(company);
        return database.getCurrentCompany();
    }

    public SSNewAccountingYear selectYear(SSNewCompany company, int yearId) {
        List<SSNewAccountingYear> years = database.getYearsForCompany(company);
        SSNewAccountingYear year = years.stream()
                .filter(candidate -> candidate.getId().equals(yearId))
                .findFirst()
                .orElseThrow(() -> new CliException("YEAR_NOT_FOUND",
                        "Company " + company.getId() + " has no accounting year with id " + yearId));
        database.setCurrentYear(year);
        return database.getCurrentYear();
    }

    @Override
    public void close() {
        database.setCurrentYear(null);
        database.setCurrentCompany(null);
        database.clearLists();
        database.shutdown();
    }
}
