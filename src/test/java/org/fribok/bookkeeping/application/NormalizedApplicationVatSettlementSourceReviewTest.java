package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementPreviewTest.prepared;
import static org.fribok.bookkeeping.application.NormalizedFinancialReports.VatSettlementSourceState.CHANGED;
import static org.fribok.bookkeeping.application.NormalizedFinancialReports.VatSettlementSourceState.UNCHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationVatSettlementSourceReviewTest {
    static void sql(Fixture f, String... statements) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            for (var statement : statements) s.executeUpdate(statement);
        }
    }

    @Test
    void issueAndRepeatedChecksUseOneRollbackOnlyConnectionPerCallWithoutMutatingSqlTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                int opens = observed.opens.get();
                var review = reports.vatSettlementReview(FROM, TO);
                assertEquals(opens + 1, observed.opens.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(1, observed.closes.get());
                assertEquals(0, observed.commits.get());
                for (int i = 0; i < 2; i++)
                    assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
                assertEquals(opens + 3, observed.opens.get());
                assertEquals(6, observed.rollbacks.get());
                assertEquals(3, observed.closes.get());
                assertEquals(0, observed.commits.get());
                assertEquals(reports.vatSettlementPreview(FROM, TO), review.preview());
                assertEquals(before, f.snapshot());
                assertThrows(
                        UnsupportedOperationException.class, () -> review.preview().rows().clear());
                assertEquals(
                        List.of("preview"),
                        Arrays.stream(review.getClass().getDeclaredMethods())
                                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                                .map(m -> m.getName())
                                .toList());
            }
        }
    }

    @Test
    void
            sourceRowMetadataChangeWithEqualPreviewAndSameMaxNumberIsDetectedWithoutRefreshingOldReviewTest()
                    throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            prepared(f, owner);
            var reports = owner.select(71, 11).reports();
            var review = reports.vatSettlementReview(FROM, TO);
            sql(
                    f,
                    "UPDATE voucher_row SET edited_signature='' WHERE accounting_year_id=(SELECT id"
                            + " FROM accounting_year WHERE legacy_id=11)");
            assertEquals(review.preview(), reports.vatSettlementPreview(FROM, TO));
            assertEquals(CHANGED, reports.checkVatSettlementSources(review));
            // State equality is NOT an intervening-transaction log: an exact restoration compares
            // equal.
            sql(
                    f,
                    "UPDATE voucher_row SET edited_signature=NULL WHERE accounting_year_id=(SELECT"
                            + " id FROM accounting_year WHERE legacy_id=11)");
            assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
        }
    }

    @Test
    void fullVoucherHeaderAndRawAmountEditsAreDetectedWithoutRelyingOnAssignedNumberTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var reports = owner.select(71, 11).reports();
            var first = reports.vatSettlementReview(FROM, TO);
            sql(
                    f,
                    "UPDATE voucher SET description='Changed source description' WHERE"
                            + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
            assertEquals(first.preview(), reports.vatSettlementPreview(FROM, TO));
            assertEquals(CHANGED, reports.checkVatSettlementSources(first));
            var second = reports.vatSettlementReview(FROM, TO);
            sql(
                    f,
                    "UPDATE voucher_row SET credit=credit+1 WHERE account_id=(SELECT id FROM"
                            + " account WHERE number=2610 AND accounting_year_id=(SELECT id FROM"
                            + " accounting_year WHERE legacy_id=11))");
            assertEquals(CHANGED, reports.checkVatSettlementSources(second));
        }
    }

    @Test
    void openingAccountAndUnreferencedCompanyCataloguesAreConservativelyTrackedTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var reports = owner.select(71, 11).reports();
            for (String statement :
                    List.of(
                            "INSERT INTO opening_balance(accounting_year_id,account_id,amount)"
                                + " SELECT accounting_year_id,id,10 FROM account WHERE number=1910"
                                + " AND accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                + " legacy_id=11)",
                            "UPDATE account_plan SET name='Changed selected plan' WHERE"
                                + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                                + " legacy_id=11)",
                            "UPDATE account SET description='Unrelated selected-year account' WHERE"
                                    + " number=3010 AND accounting_year_id=(SELECT id FROM"
                                    + " accounting_year WHERE legacy_id=11)",
                            "INSERT INTO project(company_id,number,name,description,concluded)"
                                + " SELECT id,'unused','Unused','Unused',FALSE FROM company WHERE"
                                + " legacy_id=71",
                            "UPDATE project SET concluded=TRUE WHERE number='unused' AND"
                                    + " company_id=(SELECT id FROM company WHERE legacy_id=71)",
                            "INSERT INTO result_unit(company_id,number,name,description) SELECT"
                                + " id,'unused','Unused','Unused' FROM company WHERE legacy_id=71",
                            "UPDATE company SET corporate_id='Changed selected company' WHERE"
                                    + " legacy_id=71")) {
                var review = reports.vatSettlementReview(FROM, TO);
                sql(f, statement);
                assertEquals(CHANGED, reports.checkVatSettlementSources(review), statement);
            }
        }
    }

    @Test
    void foreignCompanyAndOtherYearDataDoNotChangeSelectedYearSourceStateTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            sql(
                    f,
                    "INSERT INTO accounting_year(company_id,legacy_id,starts_on,ends_on)"
                            + " SELECT company_id,13,DATE '2027-01-01',DATE '2027-12-31'"
                            + " FROM accounting_year WHERE legacy_id=11");
            var reports = owner.select(71, 11).reports();
            var review = reports.vatSettlementReview(FROM, TO);
            sql(
                    f,
                    "UPDATE company SET name='Foreign changed' WHERE legacy_id=72",
                    "UPDATE accounting_year SET ends_on=DATE '2027-12-30' WHERE legacy_id=13",
                    "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE legacy_id=12",
                    "UPDATE account SET description='Foreign changed' WHERE"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE legacy_id=12)",
                    "INSERT INTO project(company_id,number,name,description,concluded) SELECT"
                            + " id,'other','Other','Other',FALSE FROM company WHERE legacy_id=72");
            assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
        }
    }

    @Test
    void outsidePeriodPostingStillChangesConservativeWholeYearReviewWithoutChangingAmountsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            var reports = lease.reports();
            var review = reports.vatSettlementReview(FROM, TO);
            lease.vouchers().post(lease.vouchers().preview(draft(DAY.minusMonths(1))));
            var fresh = reports.vatSettlementPreview(FROM, TO);
            assertEquals(review.preview().rows(), fresh.rows());
            assertEquals(review.preview().report(), fresh.report());
            assertEquals(CHANGED, reports.checkVatSettlementSources(review));
            assertEquals(1, review.preview().scope().maximumVoucher());
            assertEquals(2, fresh.scope().maximumVoucher());
        }
    }

    @Test
    void nullAndForeignIssuerReviewsAreRejectedBeforeSqlEvenForIdenticalSelectionTest()
            throws Exception {
        try (var f = new Fixture();
                var first = f.owner();
                var other = f.owner()) {
            prepared(f, first);
            var report = first.select(71, 11).reports();
            var review = report.vatSettlementReview(FROM, TO);
            var foreign = other.select(71, 11).reports();
            int opens = f.opens.get();
            assertThrows(NullPointerException.class, () -> report.vatSettlementReview(null, TO));
            assertThrows(NullPointerException.class, () -> report.vatSettlementReview(FROM, null));
            assertThrows(NullPointerException.class, () -> report.checkVatSettlementSources(null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> foreign.checkVatSettlementSources(review));
            assertEquals(opens, f.opens.get());
            first.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> report.checkVatSettlementSources(review));
            assertThrows(SQLException.class, () -> report.vatSettlementReview(FROM, TO));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void commitUncertaintyBlocksReviewAndRecheckBeforeOpeningSqlTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            var reports = lease.reports();
            var review = reports.vatSettlementReview(FROM, TO);
            var token = lease.vouchers().preview(draft(DAY));
            f.failure = "after";
            assertThrows(SQLException.class, () -> lease.vouchers().post(token));
            int opens = f.opens.get();
            assertTrue(owner.isBlocked());
            assertThrows(SQLException.class, () -> reports.checkVatSettlementSources(review));
            assertThrows(SQLException.class, () -> reports.vatSettlementReview(FROM, TO));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void queryRollbackAndCloseFailuresDoNotReturnSuccessfulChecksOrCreateCommitUncertaintyTest()
            throws Exception {
        for (String mode : List.of("query", "rollback", "close"))
            try (var f = new Fixture();
                    var setup = f.owner()) {
                prepared(f, setup);
                try (var observed = new Observed(f)) {
                    var reports = observed.owner.select(71, 11).reports();
                    var review = reports.vatSettlementReview(FROM, TO);
                    observed.failure = mode;
                    assertThrows(
                            SQLException.class, () -> reports.checkVatSettlementSources(review));
                    observed.failure = mode;
                    assertThrows(SQLException.class, () -> reports.vatSettlementReview(FROM, TO));
                    assertFalse(observed.owner.isBlocked());
                    assertEquals(0, observed.commits.get());
                    observed.failure = "";
                    assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
                }
            }
    }

    @Test
    void sourceQueriesAreLazySerializableAndFailClosedIndependentlyOfOrdinaryPreviewTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            var count = new AtomicInteger();
            var fail = new AtomicBoolean();
            try (var owner =
                    new NormalizedApplicationOwner(
                            () -> {
                                var c = f.open();
                                return (Connection)
                                        Proxy.newProxyInstance(
                                                Connection.class.getClassLoader(),
                                                new Class<?>[] {Connection.class},
                                                (p, m, a) -> {
                                                    try {
                                                        if (m.getName().equals("prepareStatement")
                                                                && ((String) a[0])
                                                                        .contains(
                                                                                "/* VAT source"
                                                                                        + " review"
                                                                                        + " */")) {
                                                            assertFalse(c.getAutoCommit());
                                                            assertEquals(
                                                                    Connection
                                                                            .TRANSACTION_SERIALIZABLE,
                                                                    c.getTransactionIsolation());
                                                            count.incrementAndGet();
                                                            if (fail.get())
                                                                throw new SQLException(
                                                                        "Injected source-review"
                                                                                + " query failure");
                                                        }
                                                        if (m.getName().equals("commit"))
                                                            throw new AssertionError(
                                                                    "Review must never commit");
                                                        return m.invoke(c, a);
                                                    } catch (InvocationTargetException e) {
                                                        throw e.getCause();
                                                    }
                                                });
                            },
                            java.time.Clock.systemUTC())) {
                var reports = owner.select(71, 11).reports();
                reports.vatSettlementPreview(FROM, TO);
                assertEquals(0, count.get());
                var review = reports.vatSettlementReview(FROM, TO);
                assertEquals(9, count.get());
                assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
                assertEquals(18, count.get());
                fail.set(true);
                assertThrows(SQLException.class, () -> reports.vatSettlementReview(FROM, TO));
                assertThrows(SQLException.class, () -> reports.checkVatSettlementSources(review));
                reports.vatSettlementPreview(FROM, TO);
                assertFalse(owner.isBlocked());
                fail.set(false);
                assertEquals(UNCHANGED, reports.checkVatSettlementSources(review));
            }
        }
    }

    @Test
    void changedSelectedYearBoundsFailClosedRatherThanClassifyingUnavailableScopeTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var reports = owner.select(71, 11).reports();
            var review = reports.vatSettlementReview(FROM, TO);
            sql(f, "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE legacy_id=11");
            assertThrows(SQLException.class, () -> reports.checkVatSettlementSources(review));
            assertThrows(SQLException.class, () -> reports.vatSettlementReview(FROM, TO));
        }
    }

    @Test
    void ownerCloseRejectsLateReviewAndLateRecheckWithoutWritingTest() throws Exception {
        for (boolean check : List.of(false, true))
            try (var f = new Fixture();
                    var setup = f.owner();
                    var observed = new Observed(f);
                    var executor = Executors.newSingleThreadExecutor()) {
                prepared(f, setup);
                var reports = observed.owner.select(71, 11).reports();
                var review = reports.vatSettlementReview(FROM, TO);
                var before = f.snapshot();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                var pending =
                        executor.submit(
                                () ->
                                        check
                                                ? reports.checkVatSettlementSources(review)
                                                : reports.vatSettlementReview(FROM, TO));
                try {
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    observed.owner.close();
                } finally {
                    observed.release.countDown();
                }
                var error =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> pending.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, error.getCause());
                assertEquals(0, observed.commits.get());
                assertEquals(before, f.snapshot());
            }
    }
}
