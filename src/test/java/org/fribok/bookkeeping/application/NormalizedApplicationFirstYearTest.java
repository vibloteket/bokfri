package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSNewCompany;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class NormalizedApplicationFirstYearTest {
    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 12, 31);

    @Test
    void firstYearNeedsNoPriorDocumentLeaseAndPreservesEveryExistingRow() throws Exception {
        try (var f = new Fixture(false);
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            var before = f.snapshot();
            assertEquals(2, owner.firstYearAccountPlans(71).size());
            var preview = owner.previewFirstYear(71, FROM, TO, f.planId);
            assertEquals(71, preview.company().id());
            assertEquals(before, f.snapshot());
            var created = owner.createYear(preview);
            assertEquals(0, created.id()); // Empty global year catalogue, not just empty company.
            assertEquals(FROM, created.from());
            assertEquals(TO, created.to());
            assertTrue(f.snapshot().containsAll(before));
            assertTrue(owner.companies().get(1).years().isEmpty());
            try (var c = f.open()) {
                var accounting = new NormalizedAccountingStore(c, Clock.systemUTC());
                accounting.setCurrentCompany(accounting.getCompanies().get(0));
                var year = accounting.getYears().get(0);
                accounting.setCurrentYear(year);
                assertEquals(1, accounting.getAccounts().size());
                assertEquals(1910, accounting.getAccounts().get(0).getNumber());
                assertTrue(accounting.getVouchers().isEmpty());
                assertTrue(accounting.getOpeningBalances(year).isEmpty());
            }
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            assertThrows(SQLException.class, () -> owner.firstYearAccountPlans(71));
            assertThrows(
                    SQLException.class,
                    () -> owner.previewFirstYear(71, FROM.plusYears(1), TO.plusYears(1), f.planId));
            assertEquals(1, owner.createYear(owner.previewFirstYear(72, FROM, TO, f.planId)).id());
        }
    }

    @Test
    void companyOnlyPreviewAndWriteDoNotReplaceTheActiveDocumentLease() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            addYearlessCompany(f);
            var oldLease = owner.select(71, 11);
            var preview = owner.previewFirstYear(73, FROM, TO, f.planId);
            var lease = owner.select(72, 12);
            assertFalse(oldLease.isActive());
            var before = f.snapshot();
            owner.createYear(preview);
            assertTrue(f.snapshot().containsAll(before));
            assertTrue(lease.isActive());
            assertEquals(72, lease.company().id());
            assertEquals(12, lease.year().id());
            assertEquals(1, owner.companies().get(2).years().size());
            assertDoesNotThrow(() -> lease.customers().documents());
            assertDoesNotThrow(() -> lease.suppliers().documents());
        }
    }

    @Test
    void explicitMissingOrNonemptyCompanyAndInvalidDatesOrTemplateNeverWrite() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            addYearlessCompany(f);
            var before = f.snapshot();
            for (int company : List.of(-1, 999, 71, 72)) {
                assertThrows(SQLException.class, () -> owner.firstYearAccountPlans(company));
                assertThrows(
                        SQLException.class,
                        () -> owner.previewFirstYear(company, FROM, TO, f.planId));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> owner.previewFirstYear(73, null, TO, f.planId));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> owner.previewFirstYear(73, TO, FROM, f.planId));
            assertThrows(SQLException.class, () -> owner.previewFirstYear(73, FROM, TO, -1));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void changedCompanyTemplateOrNewlyCreatedYearRejectsOldConfirmation() throws Exception {
        for (String sql :
                List.of(
                        "UPDATE company SET name = 'renamed' WHERE legacy_id = 71",
                        "UPDATE account_plan_catalog_account SET description = 'changed'",
                        "new-year")) {
            try (var f = new Fixture(false);
                    var owner = f.owner()) {
                var preview = owner.previewFirstYear(71, FROM, TO, f.planId);
                if (sql.equals("new-year")) {
                    try (var other = f.owner()) {
                        other.createYear(other.previewFirstYear(71, FROM, TO, f.planId));
                    }
                } else {
                    try (var c = f.open();
                            var s = c.createStatement()) {
                        s.executeUpdate(sql);
                    }
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> owner.createYear(preview));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
        }
    }

    @Test
    void foreignAndClosedOwnersCannotConfirmOrConsumeTheOriginalToken() throws Exception {
        try (var f = new Fixture(false);
                var owner = f.owner();
                var foreign = f.owner()) {
            var preview = owner.previewFirstYear(71, FROM, TO, f.planId);
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.createYear(preview));
            assertEquals(opens, f.opens.get());
            assertNotNull(owner.createYear(preview));
            var pending = owner.previewFirstYear(72, FROM, TO, f.planId);
            owner.close();
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> owner.createYear(pending));
            assertThrows(SQLException.class, () -> owner.firstYearAccountPlans(72));
            assertThrows(SQLException.class, () -> owner.previewFirstYear(72, FROM, TO, f.planId));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void lateInsertFailureRollsBackAndKnownCommitCloseFailureReturnsSuccess() throws Exception {
        try (var f = new Fixture(false);
                var owner = f.owner()) {
            var preview = owner.previewFirstYear(71, FROM, TO, f.planId);
            var before = f.snapshot();
            f.failure = "account";
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            var retry = owner.previewFirstYear(71, FROM, TO, f.planId);
            f.failure = "close";
            assertNotNull(owner.createYear(retry));
            assertFalse(owner.isBlocked());
            assertEquals(1, owner.companies().get(0).years().size());
            assertThrows(SQLException.class, () -> owner.createYear(retry));
        }
    }

    @Test
    void failedFirstYearCommitBlocksBothDocumentsAndAllYearCommandsBeforeOpening()
            throws Exception {
        for (var failure : List.of("before", "after", "runtime")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                addYearlessCompany(f);
                var lease = owner.select(71, 11);
                var preview = owner.previewFirstYear(73, FROM, TO, f.planId);
                f.failure = failure;
                assertThrows(Exception.class, () -> owner.createYear(preview));
                assertTrue(owner.isBlocked());
                int opens = f.opens.get();
                assertThrows(SQLException.class, () -> owner.createYear(preview));
                assertThrows(
                        SQLException.class, () -> owner.previewFirstYear(73, FROM, TO, f.planId));
                assertThrows(SQLException.class, () -> owner.firstYearAccountPlans(73));
                assertThrows(SQLException.class, () -> owner.accountPlans(lease));
                assertThrows(
                        SQLException.class, () -> owner.previewYear(lease, FROM, TO, f.planId));
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertEquals(opens, f.opens.get());
                try (var fresh = f.owner()) {
                    assertEquals(
                            failure.equals("after") ? 1 : 0,
                            fresh.companies().get(2).years().size());
                }
            }
        }
    }

    @Test
    void acceptedFirstYearFinishesOnItsOriginalCompanyAfterOwnerClose() throws Exception {
        try (var f = new Fixture(false);
                var owner = f.owner()) {
            var preview = owner.previewFirstYear(71, FROM, TO, f.planId);
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> owner.createYear(preview));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
                f.release.countDown();
                assertNotNull(pending.get(5, TimeUnit.SECONDS));
                try (var fresh = f.owner()) {
                    assertEquals(1, fresh.companies().get(0).years().size());
                    assertTrue(fresh.companies().get(1).years().isEmpty());
                }
            } finally {
                f.release.countDown();
                pool.shutdownNow();
            }
        }
    }

    static void addYearlessCompany(Fixture fixture) throws Exception {
        try (var c = fixture.open()) {
            var company = new SSNewCompany();
            company.setId(73);
            company.setName("Yearless company");
            new NormalizedAccountingStore(c, Clock.systemUTC()).addCompany(company);
        }
    }
}
