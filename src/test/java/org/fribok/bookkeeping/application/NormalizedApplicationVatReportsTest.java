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

class NormalizedApplicationVatReportsTest {
    static final LocalDate FROM = LocalDate.of(2026, 6, 1),
            TO = LocalDate.of(2026, 6, 30),
            DAY = LocalDate.of(2026, 6, 15);

    static void accounts(Fixture f) throws Exception {
        NormalizedApplicationManualVoucherTest.accounts(f);
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE account SET vat_code='MP1' WHERE number=3010 AND"
                        + " accounting_year_id=(SELECT id FROM accounting_year WHERE"
                        + " legacy_id=11)");
            for (String values : List.of("2610,'Output VAT','U1'", "2640,'Input VAT','I'"))
                s.executeUpdate(
                        "INSERT INTO"
                            + " account(accounting_year_id,number,description,vat_code,active,project_required,result_unit_required)"
                            + " SELECT id,"
                                + values
                                + ",TRUE,FALSE,FALSE FROM accounting_year WHERE legacy_id=11");
        }
    }

    static Draft draft(LocalDate date) {
        return new Draft(
                date,
                "VAT source",
                List.of(
                        new Row(1910, new BigDecimal("116.23"), null),
                        new Row(2640, new BigDecimal("10.75"), null),
                        new Row(3010, null, new BigDecimal("100.99")),
                        new Row(2610, null, new BigDecimal("25.99"))));
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
    void exactBoxesPerAccountRoundingAndSnapshotReadNeverWriteOrUseLegacySqlTest()
            throws Exception {
        try (var f = new Fixture();
                var setup = f.owner()) {
            prepared(f, setup);
            var before = f.snapshot();
            try (var observed = new Observed(f);
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var reports = observed.owner.select(71, 11).reports();
                observed.reset();
                var value = reports.vatReport(FROM, TO);
                assertEquals(71, value.scope().company());
                assertEquals(11, value.scope().year());
                assertEquals(1, value.scope().maximumVoucher());
                assertEquals(16, value.report().boxes().size());
                money("100", box(value.report(), 5));
                money("25", box(value.report(), 10));
                money("10", box(value.report(), 48));
                money("15", box(value.report(), 49));
                money("15", value.report().vatToPayOrRefund());
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
                assertEquals(2, observed.rollbacks.get());
                assertEquals(1, observed.closes.get());
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
            money("15", lease.reports().vatReport(DAY, DAY).report().vatToPayOrRefund());
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
            money("0", lease.reports().vatReport(FROM, TO).report().vatToPayOrRefund());
            money(
                    "-15",
                    lease.reports()
                            .vatReport(DAY.plusDays(1), DAY.plusDays(1))
                            .report()
                            .vatToPayOrRefund());
            money(
                    "15",
                    lease.reports()
                            .vatReport(LocalDate.of(2026, 1, 1), TO)
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
            var old = reports.vatReport(FROM, TO);
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET"
                                    + " vat_code='U2',active=FALSE,description='<html>new' WHERE"
                                    + " number=2610");
            }
            var before = f.snapshot();
            var updated = reports.vatReport(FROM, TO);
            money("25", box(old.report(), 10));
            money("0", box(updated.report(), 10));
            money("25", box(updated.report(), 11));
            money("15", updated.report().vatToPayOrRefund());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void foreignAndEmptyYearsStayZeroEvenWithOtherCompanySourcesTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var empty = owner.select(71, 11).reports().vatReport(FROM, TO);
            assertEquals(16, empty.report().boxes().size());
            money("0", empty.report().vatToPayOrRefund());
            prepared(f, owner);
            var foreign = owner.select(72, 12).reports().vatReport(FROM, TO);
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
            assertThrows(IllegalArgumentException.class, () -> reports.vatReport(TO, FROM));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.vatReport(LocalDate.of(2025, 1, 1), TO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reports.vatReport(FROM, LocalDate.of(2027, 1, 1)));
            int opens = f.opens.get();
            assertThrows(NullPointerException.class, () -> reports.vatReport(null, TO));
            assertEquals(opens, f.opens.get());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> reports.vatReport(FROM, TO));
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
                assertThrows(SQLException.class, () -> reports.vatReport(FROM, TO));
                assertFalse(observed.owner.isBlocked());
                assertEquals(0, observed.commits.get());
                observed.failure = "";
                money("0", reports.vatReport(FROM, TO).report().vatToPayOrRefund());
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
            assertThrows(SQLException.class, () -> lease.reports().vatReport(FROM, TO));
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
            assertThrows(SQLException.class, () -> reports.vatReport(FROM, TO));
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
            var pending = executor.submit(() -> reports.vatReport(FROM, TO));
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
