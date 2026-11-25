package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;
import org.fribok.bookkeeping.service.vat.VatReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class NormalizedApplicationVatSettlementPreviewTest {
    static final LocalDate FROM = LocalDate.of(2026, 6, 1),
            TO = LocalDate.of(2026, 6, 30),
            DAY = LocalDate.of(2026, 6, 15);

    static void accounts(Fixture f) throws Exception {
        NormalizedApplicationVatDeclarationReportsTest.accounts(f);
        try (var c = f.open();
                var stmt = c.createStatement()) {
            for (int year : List.of(11, 12))
                for (String values :
                        List.of(
                                "1650,'VAT receivable','R1'",
                                "2650,'VAT payable','R2'",
                                "3740,'VAT rounding','A'"))
                    stmt.executeUpdate(
                            "INSERT INTO"
                                + " account(accounting_year_id,number,description,vat_code,active,project_required,result_unit_required)"
                                + " SELECT id,"
                                    + values
                                    + ",TRUE,FALSE,FALSE FROM accounting_year WHERE legacy_id="
                                    + year);
        }
    }

    static Draft draft(LocalDate date) {
        return new Draft(
                date,
                "Split VAT source",
                List.of(
                        new Row(1910, new BigDecimal("1.98"), null),
                        new Row(2610, null, new BigDecimal("0.99")),
                        new Row(2611, null, new BigDecimal("0.99"))));
    }

    static void prepared(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        accounts(f);
        var commands = owner.select(71, 11).vouchers();
        commands.post(commands.preview(draft(DAY)));
    }

    static BigDecimal box(VatReport report, int number) {
        return report.boxes().stream()
                .filter(b -> b.number() == number)
                .findFirst()
                .orElseThrow()
                .amount();
    }

    @Test
    void fieldTotalsDifferFromCompatibilityAndSnapshotReadNeverWritesOrUsesLegacySqlTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                var value = reports.vatSettlementPreview(FROM, TO);
                assertEquals(71, value.scope().company());
                assertEquals(11, value.scope().year());
                assertEquals(1, value.scope().maximumVoucher());
                assertEquals(16, value.report().boxes().size());
                assertEquals(4, value.rows().size());
                assertEquals(TO, value.date());
                assertEquals(2650, value.rows().get(2).account());
                money("1", value.rows().get(2).credit());
                assertEquals(3740, value.rows().get(3).account());
                money("0.98", value.rows().get(3).credit());
                money("1.98", value.debitTotal());
                money("1.98", value.creditTotal());
                assertThrows(UnsupportedOperationException.class, () -> value.rows().clear());
                money("0", box(value.report(), 5));
                money("1", box(value.report(), 10));
                money("0", box(value.report(), 48));
                money("1", box(value.report(), 49));
                money("1", value.report().vatToPayOrRefund());
                money("0", reports.vatReport(FROM, TO).report().vatToPayOrRefund());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                assertEquals(4, observed.rollbacks.get());
                assertEquals(2, observed.closes.get());
                assertThrows(
                        UnsupportedOperationException.class, () -> value.report().boxes().clear());
            }
        }
    }

    @Test
    void inclusivePeriodsExcludeOpeningAndPriorEntriesAndIncludeExactReversalTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var lease = owner.select(71, 11);
            lease.vouchers().post(lease.vouchers().preview(draft(LocalDate.of(2026, 5, 1))));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "INSERT INTO opening_balance(accounting_year_id,account_id,amount)"
                                        + " SELECT accounting_year_id,id,999.99 FROM account WHERE"
                                        + " number=2610 AND accounting_year_id=(SELECT id FROM"
                                        + " accounting_year WHERE legacy_id=11)");
            }
            money("1", lease.reports().vatSettlementPreview(DAY, DAY).report().vatToPayOrRefund());
            long id =
                    lease.voucherRegister().page(null).headers().stream()
                            .filter(v -> v.number() == 1)
                            .findFirst()
                            .orElseThrow()
                            .id();
            lease.vouchers()
                    .postReversal(
                            lease.vouchers()
                                    .previewReversal(id, DAY.plusDays(1), "Reverse VAT source"));
            money("0", lease.reports().vatSettlementPreview(FROM, TO).report().vatToPayOrRefund());
            money(
                    "-1",
                    lease.reports()
                            .vatSettlementPreview(DAY.plusDays(1), DAY.plusDays(1))
                            .report()
                            .vatToPayOrRefund());
            money(
                    "1",
                    lease.reports()
                            .vatSettlementPreview(LocalDate.of(2026, 1, 1), TO)
                            .report()
                            .vatToPayOrRefund());
        }
    }

    @Test
    void changedCurrentVatCodesRefreshExplicitlyWithoutMutatingEarlierSnapshotsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepared(f, owner);
            var reports = owner.select(71, 11).reports();
            var old = reports.vatSettlementPreview(FROM, TO);
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET"
                                    + " vat_code='U2',active=FALSE,description='<html>new' WHERE"
                                    + " number=2610");
            }
            var before = f.snapshot();
            var updated = reports.vatSettlementPreview(FROM, TO);
            money("1", box(old.report(), 10));
            money("0", box(updated.report(), 10));
            money("0", box(updated.report(), 11));
            money("0", updated.report().vatToPayOrRefund());
            assertEquals("Output VAT", old.rows().get(0).description());
            assertEquals("<html>new", updated.rows().get(0).description());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void foreignAndEmptyYearsStayZeroEvenWithOtherCompanySourcesTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var empty = owner.select(71, 11).reports().vatSettlementPreview(FROM, TO);
            assertEquals(16, empty.report().boxes().size());
            money("0", empty.report().vatToPayOrRefund());
            var commands = owner.select(71, 11).vouchers();
            commands.post(commands.preview(draft(DAY)));
            var foreign = owner.select(72, 12).reports().vatSettlementPreview(FROM, TO);
            assertEquals(72, foreign.scope().company());
            money("0", foreign.report().vatToPayOrRefund());
            money("0", empty.report().vatToPayOrRefund());
        }
    }

    @Test
    void malformedOutOfYearNullAndRetiredScopeAreRefusedWithoutChangesTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var reports = owner.select(71, 11).reports();
            var before = f.snapshot();
            assertThrows(
                    IllegalArgumentException.class, () -> reports.vatSettlementPreview(TO, FROM));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.vatSettlementPreview(LocalDate.of(2025, 1, 1), TO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.vatSettlementPreview(FROM, LocalDate.of(2027, 1, 1)));
            int opens = f.opens.get();
            assertThrows(NullPointerException.class, () -> reports.vatSettlementPreview(null, TO));
            assertEquals(opens, f.opens.get());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> reports.vatSettlementPreview(FROM, TO));
            assertEquals(opens, f.opens.get());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void readQueryRollbackAndCloseErrorsDoNotSetCommitUncertaintyTest() throws Exception {
        for (String failure : List.of("query", "rollback", "close"))
            try (var f = new Fixture();
                    var observed = new Observed(f)) {
                accounts(f);
                var reports = observed.owner.select(71, 11).reports();
                observed.failure = failure;
                assertThrows(SQLException.class, () -> reports.vatSettlementPreview(FROM, TO));
                assertFalse(observed.owner.isBlocked());
                assertEquals(0, observed.commits.get());
                observed.failure = "";
                money("0", reports.vatSettlementPreview(FROM, TO).report().vatToPayOrRefund());
            }
    }

    @Test
    void uncertainCommitAndChangedYearBoundsFailClosedForVatReadsTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var token = lease.vouchers().preview(draft(DAY));
            f.failure = "after";
            assertThrows(SQLException.class, () -> lease.vouchers().post(token));
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> lease.reports().vatSettlementPreview(FROM, TO));
            assertEquals(opens, f.opens.get());
        }
        try (var f = new Fixture();
                var owner = f.owner()) {
            var reports = owner.select(71, 11).reports();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE"
                                        + " legacy_id=11");
            }
            assertThrows(SQLException.class, () -> reports.vatSettlementPreview(FROM, TO));
        }
    }

    @Test
    void closedOwnerFencesPendingReadWithoutCommitOrMutationTest() throws Exception {
        try (var f = new Fixture();
                var observed = new Observed(f);
                var executor = Executors.newSingleThreadExecutor()) {
            accounts(f);
            var reports = observed.owner.select(71, 11).reports();
            var before = f.snapshot();
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            var pending = executor.submit(() -> reports.vatSettlementPreview(FROM, TO));
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

    @Test
    void missingOrAmbiguousRolesRefuseProposalButNotTheReadOnlyDeclarationReportTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var reports = owner.select(71, 11).reports();
            try (var c = f.open();
                    var stmt = c.createStatement()) {
                stmt.executeUpdate(
                        "UPDATE account SET vat_code=NULL WHERE number=3740 AND"
                            + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
            }
            var before = f.snapshot();
            assertThrows(IllegalStateException.class, () -> reports.vatSettlementPreview(FROM, TO));
            money("0", reports.vatDeclarationReport(FROM, TO).report().vatToPayOrRefund());
            assertEquals(before, f.snapshot());
            try (var c = f.open();
                    var stmt = c.createStatement()) {
                stmt.executeUpdate(
                        "UPDATE account SET vat_code='A' WHERE number IN (2640,3740) AND"
                            + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                            + " legacy_id=11)");
            }
            before = f.snapshot();
            assertThrows(IllegalStateException.class, () -> reports.vatSettlementPreview(FROM, TO));
            assertEquals(before, f.snapshot());
        }
    }
}
