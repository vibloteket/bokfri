package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.DAY;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatReportsTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementIsolationTest.load;
import static org.fribok.bookkeeping.application.NormalizedApplicationVatSettlementIsolationTest.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedManualVoucherCommands.Row;
import org.fribok.bookkeeping.service.vat.VatService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationVatDeclarationPolicyTest {
    private static void prepare(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        NormalizedApplicationVatSettlementIsolationTest.prepare(f, owner);
        try (var c = f.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO"
                        + " account(accounting_year_id,number,description,vat_code,active,project_required,result_unit_required)"
                        + " SELECT id,2611,'Second output','U1',TRUE,FALSE,FALSE FROM"
                        + " accounting_year WHERE legacy_id=11");
        }
        var commands = owner.select(71, 11).vouchers();
        var draft =
                new Draft(
                        DAY,
                        "Split field source",
                        List.of(
                                new Row(1910, new BigDecimal("1.98"), null),
                                new Row(2610, null, new BigDecimal("0.99")),
                                new Row(2611, null, new BigDecimal("0.99"))));
        commands.post(commands.preview(draft));
    }

    @Test
    void hydratedSqlDeclarationMatchesControlWithLegacyBlockedAndDoesNotWriteTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                c.setAutoCommit(false);
                var store = load(c, 71, 11);
                var writes = new AtomicInteger();
                var service = new VatService(store.getCurrentYear(), v -> writes.incrementAndGet());
                var plan = service.declarationPlan(FROM, TO);
                money("17", plan.report().vatToPayOrRefund());
                money("17", plan.voucher().getRows().get(3).getCredit());
                money("0.22", plan.voucher().getRows().get(4).getCredit());
                money("16", service.report(FROM, TO).vatToPayOrRefund());
                assertEquals(0, plan.voucher().getNumber());
                assertEquals(TO, plan.voucher().getLocalDate());
                assertEquals(0, writes.get());
                c.rollback();
            }
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void explicitWriterNumbersAndReopenPreservesBothSourcesAndExactBalancedClearingTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            List<List<String>> sources;
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                c.setAutoCommit(false);
                var store = load(c, 71, 11);
                sources =
                        store.getVouchers().stream()
                                .map(NormalizedApplicationVatSettlementIsolationTest::rows)
                                .toList();
                var service =
                        new VatService(
                                store.getCurrentYear(),
                                v -> {
                                    assertEquals(0, v.getNumber());
                                    v.setNumber(3);
                                    store.addVoucher(v);
                                    c.commit();
                                });
                var plan = service.declarationPlan(FROM, TO);
                assertEquals(3, service.commit(plan).voucherNumber());
            }
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                var store = load(c, 71, 11);
                var vouchers = store.getVouchers();
                assertEquals(3, vouchers.size());
                assertEquals(
                        sources,
                        vouchers.stream()
                                .filter(v -> v.getNumber() < 3)
                                .map(NormalizedApplicationVatSettlementIsolationTest::rows)
                                .toList());
                var settlement =
                        vouchers.stream().filter(v -> v.getNumber() == 3).findFirst().orElseThrow();
                BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
                for (var row : settlement.getRows()) {
                    if (row.getDebet() != null) debit = debit.add(row.getDebet());
                    if (row.getCredit() != null) credit = credit.add(row.getCredit());
                }
                money("27.97", debit);
                money("27.97", credit);
                money("17", settlement.getRows().get(3).getCredit());
                var foreign = load(c, 72, 12);
                assertTrue(foreign.getVouchers().isEmpty());
            }
        }
    }

    @Test
    void lateWriterFailureCanBeRolledBackWithoutPersistingAnyDeclarationRowsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                c.setAutoCommit(false);
                var store = load(c, 71, 11);
                var service =
                        new VatService(
                                store.getCurrentYear(),
                                v -> {
                                    v.setNumber(3);
                                    store.addVoucher(v);
                                    throw new SQLException("late declaration writer failure");
                                });
                var plan = service.declarationPlan(FROM, TO);
                assertThrows(SQLException.class, () -> service.commit(plan));
                c.rollback();
            }
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void foreignEmptyDeclarationDoesNotUseOtherCompanySourcesOrInvokeWriterTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                var store = load(c, 72, 12);
                var writes = new AtomicInteger();
                var service = new VatService(store.getCurrentYear(), v -> writes.incrementAndGet());
                var plan = service.declarationPlan(FROM, TO);
                money("0", plan.report().vatToPayOrRefund());
                assertTrue(plan.voucher().getRows().isEmpty());
                assertEquals(0, plan.voucher().getNumber());
                assertThrows(IllegalArgumentException.class, () -> service.commit(plan));
                assertEquals(0, writes.get());
            }
            assertEquals(before, f.snapshot());
        }
    }
}
