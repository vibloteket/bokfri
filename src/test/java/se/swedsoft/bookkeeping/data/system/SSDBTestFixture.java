package se.swedsoft.bookkeeping.data.system;

import org.junit.jupiter.api.Tag;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared in-memory HSQLDB fixture for integration tests.
 *
 * <p>Call {@link #setupOnce()} from a {@code @BeforeAll} method in each test
 * class to ensure the database is open.  Call {@link #resetCaches()} from a
 * {@code @BeforeEach} method to clear SSDB's in-memory list caches so that
 * every test reads fresh data from the DB.</p>
 *
 * <p>The fixture is intentionally kept as a plain utility class (not a JUnit
 * extension) so that test classes have full control over their lifecycle.</p>
 *
 * <p>All integration tests should carry {@code @Tag("integration")} so they
 * can be run independently from the fast unit-test suite.</p>
 */
@Tag("integration")
public final class SSDBTestFixture {

    /** JDBC URL for the shared in-memory HSQLDB instance. */
    static final String JDBC_URL = "jdbc:hsqldb:mem:bokfri_test";

    /**
     * Collects uncaught exceptions from background threads (e.g. HSQLDB
     * trigger threads).  Tests should call {@link #drainUncaughtExceptions()}
     * after exercising code that fires database triggers to ensure no
     * exceptions were silently swallowed on a background thread.
     */
    private static final CopyOnWriteArrayList<Throwable> uncaughtExceptions =
            new CopyOnWriteArrayList<>();

    private static boolean started = false;

    private SSDBTestFixture() {}

    /**
     * Opens the in-memory database (once per JVM), creates a test company and
     * accounting year, and sets them as the current company/year in SSDB.
     *
     * <p>Safe to call repeatedly — subsequent calls are no-ops once the DB is
     * already open.</p>
     *
     * @throws Exception if the database cannot be opened or populated
     */
    public static synchronized void setupOnce() throws Exception {
        if (started) {
            return;
        }

        // Install a default handler that captures uncaught exceptions from
        // background threads (such as HSQLDB trigger threads) so that tests
        // can assert that no errors occurred asynchronously.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                uncaughtExceptions.add(throwable));

        // SSDBConfig.load() runs in a static initializer when SSDBConfig is
        // first touched (which happens inside startupLocal).  It reads/writes
        // database.config in Path.APP_BASE (= current working directory).
        // That is the Maven project root during tests, which is writable.
        // No special override is needed.

        Class.forName("org.hsqldb.jdbcDriver");
        Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");

        // startupLocal creates tables, seeds the Java-generated demo company, imports account
        // plans (slow, one-time), and reads last-used company/year from config.
        SSDB.getInstance().startupLocal(conn);

        // Ensure we have at least one company.  startupLocal already seeds an
        // bundled demo company, so getCompanies() is non-empty
        // on a fresh DB.  On subsequent JVM runs the example company is already
        // present so the seed is skipped.
        List<SSNewCompany> companies = SSDB.getInstance().getCompanies();
        SSNewCompany company;
        if (companies == null || companies.isEmpty()) {
            company = buildTestCompany();
            SSDB.getInstance().addCompany(company);
            // addCompany sets the id on the object — re-read the id from DB.
            company = SSDB.getInstance().getCompanies().get(0);
        } else {
            company = companies.get(0);
        }
        SSDB.getInstance().setCurrentCompany(company);

        // Ensure we have at least one accounting year for the company.
        List<SSNewAccountingYear> years = SSDB.getInstance().getYears();
        SSNewAccountingYear year;
        if (years == null || years.isEmpty()) {
            year = buildTestYear();
            SSDB.getInstance().addAccountingYear(year);
            year = SSDB.getInstance().getYears().get(0);
        } else {
            year = years.get(0);
        }
        SSDB.getInstance().setCurrentYear(year);

        // Warm up in-memory caches (no Swing dialog).
        SSDB.getInstance().init(false);

        started = true;
    }

    /**
     * Clears all SSDB in-memory list caches by re-setting the current company
     * and accounting year, then eagerly reloads the voucher list.
     *
     * <p>A brief sleep before clearing is necessary because HSQLDB fires
     * {@code AFTER} triggers on a background thread.  The trigger handler calls
     * {@code getVoucher()} which can return {@code null} if the row has not yet
     * been committed, and that {@code null} gets appended to {@code iVouchers}.
     * Waiting for the background thread to finish before nulling {@code iVouchers}
     * avoids this race condition.</p>
     *
     * <p>Eagerly calling {@link SSDB#getVouchers()} after the reset ensures that
     * {@code iVouchers} is populated from the DB before the test body runs, so
     * any subsequent trigger callbacks find a non-null list and operate on real
     * voucher objects.</p>
     */
    public static void resetCaches() {
        try {
            // Let any in-flight HSQLDB background trigger threads complete.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SSDB db = SSDB.getInstance();
        SSNewCompany current = db.getCurrentCompany();
        if (current != null) {
            db.setCurrentCompany(current);
        }
        SSNewAccountingYear currentYear = db.getCurrentYear();
        if (currentYear != null) {
            db.setCurrentYear(currentYear);
        }
        // Eagerly populate iVouchers from the DB so background triggers that
        // fire afterwards operate on a non-null list and cannot add nulls.
        db.getVouchers();
    }

    /**
     * Drains all uncaught exceptions captured since the last call and throws
     * an {@link AssertionError} if any were recorded.
     *
     * <p>Call this at the end of each test (e.g. in an {@code @AfterEach}
     * method) to ensure that background-thread errors cause the test to
     * fail.</p>
     */
    public static void drainUncaughtExceptions() {
        // Give background trigger threads a moment to finish.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<Throwable> captured = List.copyOf(uncaughtExceptions);
        uncaughtExceptions.clear();

        if (!captured.isEmpty()) {
            AssertionError error = new AssertionError(
                    "Background thread(s) threw " + captured.size()
                    + " uncaught exception(s); first: " + captured.get(0));
            for (Throwable t : captured) {
                error.addSuppressed(t);
            }
            throw error;
        }
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    private static SSNewCompany buildTestCompany() {
        SSNewCompany c = new SSNewCompany();
        c.setName("Test Company AB");
        c.setCorporateID("556000-0001");
        return c;
    }

    private static SSNewAccountingYear buildTestYear() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 12, 31);

        SSNewAccountingYear year = new SSNewAccountingYear();

        year.setLocalFrom(from);
        year.setLocalTo(to);
        return year;
    }
}
