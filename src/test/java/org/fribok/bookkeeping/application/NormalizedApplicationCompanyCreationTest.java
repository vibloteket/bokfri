package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner.CompanyDraft;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class NormalizedApplicationCompanyCreationTest {
    static CompanyDraft draft(String name) {
        return new CompanyDraft(name, " 123 ", " VAT-123 ", "SEK", 3, false);
    }

    static void currency(Fixture f) throws Exception {
        try (var c = f.open()) {
            c.createStatement()
                    .executeUpdate("INSERT INTO currency VALUES ('SEK','Swedish krona', 7.5)");
        }
    }

    @Test
    void readOnlyPreviewThenCompanyOnlyInsertPreservesLeaseAndUnusedCurrencyRate()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            currency(f);
            var lease = owner.select(71, 11);
            var before = f.snapshot();
            assertEquals("SEK", owner.companyCurrencies().get(0).code());
            var preview = owner.previewCompany(draft("  New company  "));
            assertEquals("New company", preview.draft().name());
            assertEquals("123", preview.draft().corporateId());
            assertEquals(before, f.snapshot());
            try (var c = f.open()) {
                c.createStatement().executeUpdate("UPDATE currency SET exchange_rate = 9.75");
            }
            before = f.snapshot();
            var created = owner.createCompany(preview);
            assertEquals(73, created.id());
            assertTrue(created.years().isEmpty());
            assertTrue(lease.isActive());
            assertTrue(f.snapshot().containsAll(before));
            assertEquals(
                    before.size() + 1, f.snapshot().size(), "Only the new company row is added");
            try (var c = f.open();
                    var row =
                            c.createStatement()
                                    .executeQuery("SELECT * FROM company WHERE legacy_id=73")) {
                assertTrue(row.next());
                assertEquals("New company", row.getString("name"));
                assertEquals("123", row.getString("corporate_id"));
                assertEquals("VAT-123", row.getString("vat_number"));
                assertEquals("SEK", row.getString("currency_code"));
                assertEquals(3, row.getInt("vat_period"));
                assertFalse(row.getBoolean("rounding_off"));
                assertNull(row.getObject("tax_rate_1"));
                assertNull(row.getObject("standard_unit_name"));
                assertNull(row.getObject("payment_term_name"));
            }
            assertThrows(SQLException.class, () -> owner.createCompany(preview));
        }
    }

    @Test
    void emptyCompanyCatalogueStartsAtZeroThenFirstYearIsExplicit() throws Exception {
        try (var f = new Fixture(false);
                var owner = f.owner()) {
            currency(f);
            try (var c = f.open()) {
                c.createStatement().executeUpdate("DELETE FROM company");
            }
            var company =
                    owner.createCompany(
                            owner.previewCompany(
                                    new CompanyDraft("First", " ", null, "SEK", 12, true)));
            assertEquals(0, company.id());
            assertTrue(company.years().isEmpty());
            var preview =
                    owner.previewFirstYear(
                            0, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), f.planId);
            assertTrue(owner.companies().get(0).years().isEmpty());
            assertEquals(0, owner.createYear(preview).id());
            assertEquals(1, owner.companies().get(0).years().size());
        }
    }

    @Test
    void invalidAndDuplicateInputsNeverWriteOrInventCurrencies() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            assertTrue(owner.companyCurrencies().isEmpty());
            assertThrows(SQLException.class, () -> owner.previewCompany(draft("New")));
            currency(f);
            var before = f.snapshot();
            for (var input :
                    List.of(
                            draft(" "),
                            draft(" Company 0 "),
                            draft("n".repeat(256)),
                            new CompanyDraft("New", "x".repeat(65), null, "SEK", 1, false),
                            new CompanyDraft("New", null, "x".repeat(65), "SEK", 1, false),
                            new CompanyDraft("New", null, null, " ", 1, false),
                            new CompanyDraft("New", null, null, "SEK", 2, false))) {
                assertThrows(IllegalArgumentException.class, () -> owner.previewCompany(input));
            }
            assertThrows(
                    SQLException.class,
                    () ->
                            owner.previewCompany(
                                    new CompanyDraft("New", null, null, "EUR", 1, false)));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void changedIdentityCatalogueOrCurrencyRefusesStaleConfirmation() throws Exception {
        for (String sql :
                List.of(
                        "UPDATE company SET name='changed' WHERE legacy_id=71",
                        "UPDATE currency SET description='changed'",
                        "DELETE FROM currency",
                        "concurrent")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                currency(f);
                var preview = owner.previewCompany(draft("New"));
                if (sql.equals("concurrent")) {
                    try (var other = f.owner()) {
                        other.createCompany(other.previewCompany(draft("New")));
                    }
                } else {
                    try (var c = f.open()) {
                        c.createStatement().executeUpdate(sql);
                    }
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> owner.createCompany(preview));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
        }
    }

    @Test
    void foreignClosedAndChangedDocumentSelectionsCannotRedirectCompanyToken() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var other = f.owner()) {
            currency(f);
            owner.select(71, 11);
            var preview = owner.previewCompany(draft("New"));
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> other.createCompany(preview));
            assertEquals(opens, f.opens.get());
            var lease = owner.select(72, 12);
            assertNotNull(owner.createCompany(preview));
            assertTrue(lease.isActive());
            var pending = owner.previewCompany(draft("Another"));
            owner.close();
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> owner.createCompany(pending));
            assertThrows(SQLException.class, owner::companyCurrencies);
            assertThrows(SQLException.class, () -> owner.previewCompany(draft("Other")));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void lateCompanyInsertRollsBackAndKnownCommitCloseFailureReturnsSuccess() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            currency(f);
            var preview = owner.previewCompany(draft("New"));
            var before = f.snapshot();
            f.failure = "company-row";
            assertThrows(SQLException.class, () -> owner.createCompany(preview));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertThrows(SQLException.class, () -> owner.createCompany(preview));
            var retry = owner.previewCompany(draft("New"));
            f.failure = "close";
            assertNotNull(owner.createCompany(retry));
            assertFalse(owner.isBlocked());
            assertEquals(3, owner.companies().size());
            assertThrows(SQLException.class, () -> owner.createCompany(retry));
        }
    }

    @Test
    void uncertainCompanyCommitBlocksDocumentsYearsAndCompanyCommandsBeforeSql() throws Exception {
        for (String failure : List.of("before", "after", "runtime")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                currency(f);
                var lease = owner.select(71, 11);
                var preview = owner.previewCompany(draft("New"));
                f.failure = failure;
                assertThrows(Exception.class, () -> owner.createCompany(preview));
                assertTrue(owner.isBlocked());
                int opens = f.opens.get();
                assertThrows(SQLException.class, owner::companyCurrencies);
                assertThrows(SQLException.class, () -> owner.previewCompany(draft("Other")));
                assertThrows(SQLException.class, () -> owner.createCompany(preview));
                assertThrows(SQLException.class, () -> owner.firstYearAccountPlans(71));
                assertThrows(SQLException.class, () -> owner.accountPlans(lease));
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertEquals(opens, f.opens.get());
                try (var fresh = f.owner()) {
                    assertEquals(failure.equals("after") ? 3 : 2, fresh.companies().size());
                }
            }
        }
    }

    @Test
    void acceptedCompanyWriteFinishesAfterOwnerCloseWithoutSelectingIt() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            currency(f);
            var preview = owner.previewCompany(draft("New"));
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> owner.createCompany(preview));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
                f.release.countDown();
                assertEquals("New", pending.get(5, TimeUnit.SECONDS).name());
                try (var fresh = f.owner()) {
                    assertEquals(3, fresh.companies().size());
                }
            } finally {
                f.release.countDown();
                pool.shutdownNow();
            }
        }
    }
}
