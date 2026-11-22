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

class NormalizedApplicationVoucherDimensionsTest {
    static final LocalDate DATE = LocalDate.of(2026, 6, 15);

    static Draft draft() {
        return draft(DATE, "P-common", "U-common");
    }

    static Draft draft(LocalDate date, String project, String unit) {
        return new Draft(
                date,
                "Explicit dimension entry",
                List.of(
                        new Row(4010, new BigDecimal("125.50"), null, project, unit),
                        new Row(6010, null, new BigDecimal("125.50"), project, unit)));
    }

    static void dimensions(Fixture f) throws Exception {
        NormalizedApplicationManualVoucherTest.accounts(f);
        try (var c = f.open();
                var s = c.createStatement()) {
            for (String values :
                    List.of(
                            "'P-common','Local project','Local description',FALSE,NULL",
                            "'P-closed','Closed project','Closed',TRUE,DATE '2026-06-15'",
                            "'P-future','Dated closure','Future',TRUE,DATE '2026-07-01'",
                            "'P-unknown','Unknown closure','Unknown',TRUE,NULL",
                            "'P-open','Flag false with historical date','Explicit flag',FALSE,DATE"
                                    + " '2026-01-01'")) {
                s.executeUpdate(
                        "INSERT INTO"
                            + " project(company_id,number,name,description,concluded,concluded_on)"
                            + " SELECT id,"
                                + values
                                + " FROM company WHERE legacy_id=71");
            }
            s.executeUpdate(
                    "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                            + " id,'P-common','Foreign project','Foreign',FALSE FROM company WHERE"
                            + " legacy_id=72");
            s.executeUpdate(
                    "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                            + " id,'P-foreign','Foreign only','Foreign',FALSE FROM company WHERE"
                            + " legacy_id=72");
            s.executeUpdate(
                    "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                            + " id,'U-common','Local unit','Local description' FROM company WHERE"
                            + " legacy_id=71");
            s.executeUpdate(
                    "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                            + " id,'U-common','Foreign unit','Foreign' FROM company WHERE"
                            + " legacy_id=72");
            s.executeUpdate(
                    "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                            + " id,'U-foreign','Foreign only','Foreign' FROM company WHERE"
                            + " legacy_id=72");
        }
    }

    static void checkStored(Fixture f, int count) throws Exception {
        try (var c = f.open()) {
            var store = new NormalizedAccountingStore(c, Clock.systemUTC());
            store.setCurrentCompany(store.getCompanies().get(0));
            store.setCurrentYear(store.getYears().get(0));
            assertEquals(count, store.getVouchers().size());
            for (var voucher : store.getVouchers()) {
                assertEquals(
                        List.of(4010, 6010),
                        voucher.getRows().stream().map(row -> row.getAccountNr()).toList());
                assertEquals(
                        List.of("P-common", "P-common"),
                        voucher.getRows().stream().map(row -> row.getProjectNr()).toList());
                assertEquals(
                        List.of("U-common", "U-common"),
                        voucher.getRows().stream().map(row -> row.getResultUnitNr()).toList());
                assertEquals(
                        0,
                        voucher.getRows()
                                .get(0)
                                .getDebet()
                                .compareTo(voucher.getRows().get(1).getCredit()));
            }
        }
    }

    @Test
    void catalogueAndPreviewAreReadOnlyAndExplicitDimensionsReopenWithoutLegacySql()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            dimensions(f);
            var lease = owner.select(71, 11);
            var commands = lease.vouchers();
            var before = f.snapshot();
            var choices = commands.catalogue();
            assertEquals(
                    List.of(1910, 3010, 4010, 6010),
                    choices.accounts().stream()
                            .map(NormalizedManualVoucherCommands.Account::number)
                            .toList());
            assertTrue(choices.accounts().get(2).projectRequired());
            assertTrue(choices.accounts().get(3).unitRequired());
            assertEquals(
                    "Local project",
                    choices.projects().stream()
                            .filter(p -> p.number().equals("P-common"))
                            .findFirst()
                            .orElseThrow()
                            .name());
            assertEquals("Local unit", choices.units().get(0).name());
            assertFalse(choices.projects().stream().anyMatch(p -> p.number().equals("P-foreign")));
            assertThrows(UnsupportedOperationException.class, () -> choices.projects().clear());
            var preview = commands.preview(draft());
            assertEquals(before, f.snapshot());
            assertEquals(1, commands.post(preview));
            assertTrue(lease.isActive());
            assertTrue(f.snapshot().containsAll(before));
            assertEquals(before.size() + 3, f.snapshot().size());
            assertEquals(0, NormalizedApplicationManualVoucherTest.count(f, 12));
            checkStored(f, 1);
            assertThrows(SQLException.class, () -> commands.post(preview));
        }
    }

    @Test
    void requiredMissingForeignAndBlankDimensionsAreRefusedWhileOptionalAbsenceIsPreserved()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            dimensions(f);
            var commands = owner.select(71, 11).vouchers();
            var before = f.snapshot();
            for (var invalid :
                    List.of(
                            draft(DATE, null, "U-common"),
                            draft(DATE, "P-common", null),
                            draft(DATE, "P-foreign", "U-common"),
                            draft(DATE, "P-common", "U-foreign"),
                            draft(DATE, " ", "U-common"),
                            draft(DATE, "P-common", ""))) {
                assertThrows(IllegalArgumentException.class, () -> commands.preview(invalid));
            }
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertEquals(
                    List.of(1910, 3010),
                    commands.accounts().stream()
                            .map(NormalizedManualVoucherCommands.Account::number)
                            .toList());
            assertDoesNotThrow(
                    () ->
                            commands.preview(
                                    new Draft(
                                            DATE,
                                            "Only required dimensions",
                                            List.of(
                                                    new Row(
                                                            4010,
                                                            BigDecimal.ONE,
                                                            null,
                                                            "P-common",
                                                            null),
                                                    new Row(
                                                            6010,
                                                            null,
                                                            BigDecimal.ONE,
                                                            null,
                                                            "U-common")))));
            var plain = NormalizedApplicationManualVoucherTest.draft();
            assertEquals(1, commands.post(commands.preview(plain)));
            try (var c = f.open();
                    var r =
                            c.createStatement()
                                    .executeQuery(
                                            "SELECT project_number,result_unit_number FROM"
                                                    + " voucher_row")) {
                while (r.next()) {
                    assertNull(r.getString(1));
                    assertNull(r.getString(2));
                }
            }
            try (var c = f.open();
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT INTO project(company_id,number,name,concluded) SELECT"
                                + " id,'001','Exact numeric-looking key',FALSE FROM company WHERE"
                                + " legacy_id=71");
                s.executeUpdate(
                        "INSERT INTO result_unit(company_id,number,name) SELECT id,'007','Exact"
                                + " numeric-looking unit' FROM company WHERE legacy_id=71");
            }
            assertThrows(
                    IllegalArgumentException.class, () -> commands.preview(draft(DATE, "1", "7")));
            assertEquals(2, commands.post(commands.preview(draft(DATE, "001", "007"))));
            try (var c = f.open();
                    var r =
                            c.createStatement()
                                    .executeQuery(
                                            "SELECT project_number,result_unit_number FROM"
                                                    + " voucher_row WHERE project_number IS NOT"
                                                    + " NULL")) {
                int count = 0;
                while (r.next()) {
                    assertEquals("001", r.getString(1));
                    assertEquals("007", r.getString(2));
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    @Test
    void closureDateIsInclusiveUnknownClosureIsRefusedAndFlagFalseDateIsNotInvented()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            dimensions(f);
            var commands = owner.select(71, 11).vouchers();
            var before = f.snapshot();
            assertDoesNotThrow(
                    () -> commands.preview(draft(DATE.minusDays(1), "P-closed", "U-common")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(draft(DATE, "P-closed", "U-common")));
            assertDoesNotThrow(
                    () ->
                            commands.preview(
                                    draft(LocalDate.of(2026, 6, 30), "P-future", "U-common")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            commands.preview(
                                    draft(LocalDate.of(2026, 7, 1), "P-future", "U-common")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.preview(draft(DATE, "P-unknown", "U-common")));
            assertDoesNotThrow(() -> commands.preview(draft(DATE, "P-open", "U-common")));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void changedProjectUnitOrClosureMetadataInvalidatesConfirmationWithoutWrites()
            throws Exception {
        for (String sql :
                List.of(
                        "UPDATE project SET name='changed' WHERE number='P-common'",
                        "UPDATE project SET concluded=TRUE,concluded_on=DATE '2026-01-01' WHERE"
                                + " number='P-common'",
                        "UPDATE result_unit SET description='changed' WHERE number='U-common'",
                        "DELETE FROM project WHERE number='P-common'",
                        "DELETE FROM result_unit WHERE number='U-common'")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                dimensions(f);
                var commands = owner.select(71, 11).vouchers();
                var preview = commands.preview(draft());
                try (var c = f.open()) {
                    c.createStatement().executeUpdate(sql);
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.post(preview));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
        }
    }

    @Test
    void foreignCataloguesCannotRedirectSameKeysOrInvalidateLocalPreviewAndOldFactoriesRetire()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var other = f.owner()) {
            dimensions(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            var foreign = other.select(72, 12).vouchers();
            assertEquals(
                    "Foreign unit",
                    foreign.catalogue().units().stream()
                            .filter(u -> u.number().equals("U-common"))
                            .findFirst()
                            .orElseThrow()
                            .name());
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.post(preview));
            assertEquals(opens, f.opens.get());
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE project SET name='Foreign renamed' WHERE company_id=(SELECT"
                                        + " id FROM company WHERE legacy_id=72)");
            }
            assertEquals(1, commands.post(preview));
            checkStored(f, 1);
            var stale = commands.preview(draft());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, commands::catalogue);
            assertThrows(SQLException.class, () -> commands.post(stale));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void secondDimensionedRowFailureRollsBackEverythingAndKnownCommitCloseFailureIsSuccess()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            dimensions(f);
            var commands = owner.select(71, 11).vouchers();
            var preview = commands.preview(draft());
            var before = f.snapshot();
            f.failure = "voucher-row";
            assertThrows(SQLException.class, () -> commands.post(preview));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            var fresh = commands.preview(draft());
            f.failure = "close";
            assertEquals(1, commands.post(fresh));
            assertFalse(owner.isBlocked());
            checkStored(f, 1);
            assertThrows(SQLException.class, () -> commands.post(fresh));
        }
    }

    @Test
    void uncertainDimensionedCommitBlocksCatalogueAndEveryCommandFamilyBeforeOpening()
            throws Exception {
        for (String failure : List.of("before", "after", "runtime")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                dimensions(f);
                var lease = owner.select(71, 11);
                var commands = lease.vouchers();
                var preview = commands.preview(draft());
                f.failure = failure;
                assertThrows(Exception.class, () -> commands.post(preview));
                assertTrue(owner.isBlocked());
                int opens = f.opens.get();
                assertThrows(SQLException.class, commands::catalogue);
                assertThrows(SQLException.class, commands::accounts);
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertThrows(SQLException.class, owner::companyCurrencies);
                assertThrows(SQLException.class, () -> owner.accountPlans(lease));
                assertEquals(opens, f.opens.get());
                assertEquals(
                        failure.equals("after") ? 1 : 0,
                        NormalizedApplicationManualVoucherTest.count(f, 11));
                if (failure.equals("after")) checkStored(f, 1);
            }
        }
    }

    @Test
    void acceptedDimensionedPostFinishesOriginalScopeAfterCloseWithoutAmbientResolution()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            dimensions(f);
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
                checkStored(f, 1);
                assertEquals(0, NormalizedApplicationManualVoucherTest.count(f, 12));
                int opens = f.opens.get();
                assertThrows(SQLException.class, commands::catalogue);
                assertEquals(opens, f.opens.get());
            } finally {
                f.release.countDown();
                pool.shutdownNow();
            }
        }
    }
}
