package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class NormalizedApplicationManualVoucherTest {
    static final LocalDate DATE = LocalDate.of(2026, 6, 15);

    static Draft draft() {
        return new Draft(
                DATE,
                "Manual entry <html> & literal",
                List.of(
                        new Row(1910, new BigDecimal("125.50"), null),
                        new Row(3010, null, new BigDecimal("125.50"))));
    }

    static void accounts(Fixture f) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO account"
                        + " (accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,3010,'Sales',TRUE,FALSE,FALSE FROM accounting_year WHERE"
                        + " legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO account"
                        + " (accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,4010,'Needs project',TRUE,TRUE,FALSE FROM accounting_year"
                        + " WHERE legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO account"
                        + " (accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,5010,'Inactive',FALSE,FALSE,FALSE FROM accounting_year WHERE"
                        + " legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO account"
                        + " (accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,6010,'Needs unit',TRUE,FALSE,TRUE FROM accounting_year WHERE"
                        + " legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO account"
                        + " (accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,8010,'Foreign year',TRUE,FALSE,FALSE FROM accounting_year"
                        + " WHERE legacy_id=12");
        }
    }

    static int count(Fixture f, int year) throws Exception {
        try (var c = f.open();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM voucher WHERE accounting_year_id=(SELECT id"
                                        + " FROM accounting_year WHERE legacy_id="
                                        + year
                                        + ")")) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void readOnlyPreviewThenExactBalancedPostPreservesAllExistingRowsAndOtherYear()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var commands = lease.vouchers();
            var before = f.snapshot();
            assertEquals(
                    List.of(1910, 3010),
                    commands.accounts().stream()
                            .map(NormalizedManualVoucherCommands.Account::number)
                            .toList());
            var preview = commands.preview(draft());
            assertEquals(1, preview.number());
            assertEquals(0, preview.total().compareTo(new BigDecimal("125.50")));
            assertEquals(before, f.snapshot());
            assertEquals(1, commands.post(preview));
            assertTrue(lease.isActive());
            assertTrue(f.snapshot().containsAll(before));
            assertEquals(before.size() + 3, f.snapshot().size());
            assertEquals(0, count(f, 12));
            try (var c = f.open()) {
                var store = new NormalizedAccountingStore(c, Clock.systemUTC());
                store.setCurrentCompany(store.getCompanies().get(0));
                store.setCurrentYear(store.getYears().get(0));
                var voucher = store.getVouchers().get(0);
                assertEquals(DATE, voucher.getLocalDate());
                assertEquals(draft().description(), voucher.getDescription());
                assertEquals(
                        List.of(1910, 3010),
                        voucher.getRows().stream().map(r -> r.getAccountNr()).toList());
                assertEquals(
                        0, voucher.getRows().get(0).getDebet().compareTo(new BigDecimal("125.50")));
                assertNull(voucher.getRows().get(0).getCredit());
                assertNull(voucher.getRows().get(1).getDebet());
                assertNull(voucher.getRows().get(0).getProject(List.of()));
                assertNull(voucher.getRows().get(1).getResultUnit(List.of()));
                assertFalse(voucher.getRows().get(0).isAdded());
                assertFalse(voucher.getRows().get(0).isCrossed());
            }
            assertThrows(SQLException.class, () -> commands.post(preview));
        }
    }

    @Test
    void invalidDatesDescriptionsSidesAmountsAccountsAndDimensionsNeverWrite() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).vouchers();
            var before = f.snapshot();
            for (int account : List.of(9999, 4010, 5010, 6010, 8010)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                commands.preview(
                                        new Draft(
                                                DATE,
                                                "Bad account",
                                                List.of(
                                                        new Row(account, BigDecimal.ONE, null),
                                                        new Row(3010, null, BigDecimal.ONE)))));
            }
            for (var rows :
                    List.of(
                            List.of(new Row(1910, null, null), new Row(3010, null, BigDecimal.ONE)),
                            List.of(
                                    new Row(1910, new BigDecimal("9E+69"), null),
                                            new Row(1910, new BigDecimal("9E+69"), null),
                                    new Row(3010, null, new BigDecimal("9E+69")),
                                            new Row(3010, null, new BigDecimal("9E+69"))),
                            List.of(
                                    new Row(1910, new BigDecimal("1E+2147483647"), null),
                                    new Row(3010, null, new BigDecimal("1E+2147483647"))),
                            List.of(
                                    new Row(1910, new BigDecimal("1E-2147483647"), null),
                                    new Row(3010, null, new BigDecimal("1E-2147483647"))),
                            List.of(
                                    new Row(1910, BigDecimal.ONE, BigDecimal.ONE),
                                    new Row(3010, null, BigDecimal.ONE)),
                            List.of(
                                    new Row(1910, BigDecimal.ZERO, null),
                                    new Row(3010, null, BigDecimal.ZERO)),
                            List.of(
                                    new Row(1910, new BigDecimal("-1"), null),
                                    new Row(3010, null, new BigDecimal("-1"))),
                            List.of(
                                    new Row(1910, new BigDecimal("1.001"), null),
                                    new Row(3010, null, new BigDecimal("1.001"))),
                            List.of(
                                    new Row(1910, BigDecimal.ONE, null),
                                    new Row(3010, null, BigDecimal.TEN)))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> commands.preview(new Draft(DATE, "Bad amount", rows)));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(new Draft(null, "No date", draft().rows())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(new Draft(DATE, " ", draft().rows())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(new Draft(DATE, "x".repeat(4097), draft().rows())));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            commands.preview(
                                    new Draft(DATE.minusYears(1), "Outside year", draft().rows())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(new Draft(DATE, "No rows", List.of())));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void changedAccountsCompanyYearOrJournalNumberingInvalidatePreview() throws Exception {
        for (String sql :
                List.of(
                        "UPDATE account SET description='changed' WHERE number=1910",
                        "UPDATE account SET active=FALSE WHERE number=3010",
                        "UPDATE company SET name='changed' WHERE legacy_id=71",
                        "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE legacy_id=11",
                        "concurrent")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var commands = owner.select(71, 11).vouchers();
                var preview = commands.preview(draft());
                if (sql.equals("concurrent")) {
                    try (var other = f.owner()) {
                        var foreign = other.select(71, 11).vouchers();
                        foreign.post(foreign.preview(draft()));
                    }
                } else {
                    try (var c = f.open()) {
                        c.createStatement().executeUpdate(sql);
                    }
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.post(preview));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
        }
    }

    @Test
    void foreignReplacedAndClosedLeasesRefuseTokensBeforeOpeningOrConsumingThem() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var other = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            var foreign = other.select(71, 11).vouchers();
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.post(preview));
            assertEquals(opens, f.opens.get());
            assertEquals(1, commands.post(preview));
            var pending = commands.preview(draft());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> commands.post(pending));
            assertThrows(SQLException.class, commands::accounts);
            assertEquals(opens, f.opens.get());
            owner.close();
            assertThrows(SQLException.class, () -> commands.preview(draft()));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void lateSecondRowFailureRollsBackHeaderAndBothRowsAndFreshPreviewCanRetry() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            var before = f.snapshot();
            f.failure = "voucher-row";
            assertThrows(SQLException.class, () -> commands.post(preview));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertThrows(SQLException.class, () -> commands.post(preview));
            assertEquals(1, commands.post(commands.preview(draft())));
        }
    }

    @Test
    void uncertainManualCommitBlocksBothDocumentFamiliesAndCompanyYearCommandsBeforeSql()
            throws Exception {
        for (String failure : List.of("before", "after", "runtime")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var lease = owner.select(71, 11);
                var commands = lease.vouchers();
                var preview = commands.preview(draft());
                f.failure = failure;
                assertThrows(Exception.class, () -> commands.post(preview));
                assertTrue(owner.isBlocked());
                int opens = f.opens.get();
                assertThrows(SQLException.class, commands::accounts);
                assertThrows(SQLException.class, () -> commands.post(preview));
                assertThrows(SQLException.class, () -> commands.preview(draft()));
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertThrows(SQLException.class, owner::companyCurrencies);
                assertThrows(SQLException.class, () -> owner.accountPlans(lease));
                assertEquals(opens, f.opens.get());
                assertEquals(failure.equals("after") ? 1 : 0, count(f, 11));
            }
        }
    }

    @Test
    void confirmedCommitCloseFailureReturnsSuccessWithoutAllowingDuplicateRetry() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            f.failure = "close";
            assertEquals(1, commands.post(preview));
            assertFalse(owner.isBlocked());
            assertThrows(SQLException.class, () -> commands.post(preview));
            assertEquals(1, count(f, 11));
            assertEquals(2, commands.preview(draft()).number());
        }
    }

    @Test
    void acceptedManualPostFinishesOriginalYearAfterOwnerClose() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> commands.post(preview));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
                f.release.countDown();
                assertEquals(1, pending.get(5, TimeUnit.SECONDS));
                assertEquals(1, count(f, 11));
                assertEquals(0, count(f, 12));
                assertThrows(SQLException.class, commands::accounts);
            } finally {
                f.release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void explicitValidatorNeverCallsAmbientRowGettersAndRejectsMissingCatalogues() {
        var account = new se.swedsoft.bookkeeping.data.SSAccount(1910);
        account.setProjectRequired(true);
        account.setResultUnitRequired(true);
        var year = new se.swedsoft.bookkeeping.data.SSNewAccountingYear();
        year.setLocalFrom(LocalDate.of(2026, 1, 1));
        year.setLocalTo(LocalDate.of(2026, 12, 31));
        var voucher = new se.swedsoft.bookkeeping.data.SSVoucher(1, DATE);
        voucher.setDescription("Explicit context only");
        for (boolean debit : List.of(true, false)) {
            voucher.getRows()
                    .add(
                            new se.swedsoft.bookkeeping.data.SSVoucherRow(
                                    account,
                                    debit ? BigDecimal.ONE : null,
                                    debit ? null : BigDecimal.ONE,
                                    null,
                                    null) {
                                public se.swedsoft.bookkeeping.data.SSAccount getAccount() {
                                    throw new AssertionError("Ambient accounts");
                                }

                                public se.swedsoft.bookkeeping.data.SSNewProject getProject() {
                                    throw new AssertionError("Ambient projects");
                                }

                                public se.swedsoft.bookkeeping.data.SSNewResultUnit
                                        getResultUnit() {
                                    throw new AssertionError("Ambient units");
                                }
                            });
        }
        var result =
                org.fribok.bookkeeping.service.voucher.VoucherValidator.validate(
                        voucher, year, List.of(account), List.of(), List.of());
        assertFalse(result.valid());
        assertTrue(
                result.issues().stream()
                        .anyMatch(issue -> issue.code().equals("PROJECT_REQUIRED")));
        assertTrue(
                result.issues().stream()
                        .anyMatch(issue -> issue.code().equals("RESULT_UNIT_REQUIRED")));
        assertThrows(
                NullPointerException.class,
                () ->
                        org.fribok.bookkeeping.service.voucher.VoucherValidator.validate(
                                voucher, year, null, null, null));
    }

    @Test
    void numberingUsesYearMaximumAndRefusesOverflowWithoutWrites() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "INSERT INTO voucher"
                                    + " (accounting_year_id,number,voucher_date,description) SELECT"
                                    + " id,7,DATE '2026-01-01','Historical' FROM accounting_year"
                                    + " WHERE legacy_id=11");
            }
            var commands = owner.select(71, 11).vouchers();
            assertEquals(8, commands.post(commands.preview(draft())));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate("UPDATE voucher SET number=2147483647 WHERE number=8");
            }
            var before = f.snapshot();
            assertThrows(ArithmeticException.class, () -> commands.preview(draft()));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }
}
