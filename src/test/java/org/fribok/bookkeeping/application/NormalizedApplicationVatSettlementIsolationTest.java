package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.vat.VatService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationVatSettlementIsolationTest {
    static void prepare(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        NormalizedApplicationVatReportsTest.prepared(f, owner);
        try (var c = f.open();
                var s = c.createStatement()) {
            for (int year : List.of(11, 12))
                for (String values :
                        List.of(
                                "1650,'Receivable','R1'",
                                "2650,'Payable','R2'",
                                "3740,'Rounding','A'"))
                    s.executeUpdate(
                            "INSERT INTO"
                                + " account(accounting_year_id,number,description,vat_code,active,project_required,result_unit_required)"
                                + " SELECT id,"
                                    + values
                                    + ",TRUE,FALSE,FALSE FROM accounting_year WHERE legacy_id="
                                    + year);
        }
    }

    static NormalizedAccountingStore load(Connection c, int companyId, int yearId)
            throws Exception {
        var store = new NormalizedAccountingStore(c, Clock.systemUTC());
        store.setCurrentCompany(
                store.getCompanies().stream()
                        .filter(v -> v.getId() == companyId)
                        .findFirst()
                        .orElseThrow());
        var year =
                store.getYears().stream()
                        .filter(v -> v.getId() == yearId)
                        .findFirst()
                        .orElseThrow();
        year.getAccountPlan().setAccounts(store.getAccounts(year));
        year.setVouchers(store.getVouchers(year));
        store.setCurrentYear(year);
        return store;
    }

    static List<String> rows(se.swedsoft.bookkeeping.data.SSVoucher v) {
        return v.getRows().stream()
                .map(r -> r.getAccountNr() + "/" + r.getDebet() + "/" + r.getCredit())
                .toList();
    }

    @Test
    void normalizedPlanWithLegacyJdbcBlockedIsUnnumberedAndDoesNotChangeAnySqlTest()
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
                var plan =
                        service.plan(
                                NormalizedApplicationVatReportsTest.FROM,
                                NormalizedApplicationVatReportsTest.TO);
                assertEquals(0, plan.voucher().getNumber());
                assertEquals(NormalizedApplicationVatReportsTest.TO, plan.voucher().getLocalDate());
                assertEquals(4, plan.voucher().getRows().size());
                assertEquals(2650, plan.voucher().getRows().get(2).getAccountNr());
                money("15", plan.voucher().getRows().get(2).getCredit());
                money("0.24", plan.voucher().getRows().get(3).getCredit());
                assertEquals(0, writes.get());
                c.rollback();
            }
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void explicitNormalizedWriterAssignsSqlNumberAndReopenPreservesSourceAndBalancedRowsTest()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            List<String> source;
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                c.setAutoCommit(false);
                var store = load(c, 71, 11);
                source = rows(store.getVouchers(store.getCurrentYear()).get(0));
                var writes = new AtomicInteger();
                var service =
                        new VatService(
                                store.getCurrentYear(),
                                v -> {
                                    assertEquals(0, v.getNumber());
                                    writes.incrementAndGet();
                                    v.setNumber(
                                            store.getVouchers().stream()
                                                            .mapToInt(item -> item.getNumber())
                                                            .max()
                                                            .orElse(0)
                                                    + 1);
                                    store.addVoucher(v);
                                    c.commit();
                                });
                var plan =
                        service.plan(
                                NormalizedApplicationVatReportsTest.FROM,
                                NormalizedApplicationVatReportsTest.TO);
                assertEquals(2, service.commit(plan).voucherNumber());
                assertEquals(1, writes.get());
            }
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                var store = load(c, 71, 11);
                var vouchers = store.getVouchers(store.getCurrentYear());
                assertEquals(2, vouchers.size());
                assertEquals(
                        source,
                        rows(
                                vouchers.stream()
                                        .filter(v -> v.getNumber() == 1)
                                        .findFirst()
                                        .orElseThrow()));
                var settlement =
                        vouchers.stream().filter(v -> v.getNumber() == 2).findFirst().orElseThrow();
                BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
                for (var row : settlement.getRows()) {
                    if (row.getDebet() != null) debit = debit.add(row.getDebet());
                    if (row.getCredit() != null) credit = credit.add(row.getCredit());
                }
                money("25.99", debit);
                assertEquals(0, debit.compareTo(credit));
                var foreign = load(c, 72, 12);
                assertTrue(foreign.getVouchers(foreign.getCurrentYear()).isEmpty());
            }
        }
    }

    @Test
    void lateWriterFailurePropagatesAndCallerRollbackLeavesDatabaseUnchangedTest()
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
                                    v.setNumber(
                                            store.getVouchers().stream()
                                                            .mapToInt(item -> item.getNumber())
                                                            .max()
                                                            .orElse(0)
                                                    + 1);
                                    store.addVoucher(v);
                                    throw new SQLException("late explicit writer failure");
                                });
                var plan =
                        service.plan(
                                NormalizedApplicationVatReportsTest.FROM,
                                NormalizedApplicationVatReportsTest.TO);
                assertThrows(SQLException.class, () -> service.commit(plan));
                c.rollback();
            }
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void foreignYearHasNoSourceOrNumberingAndEmptyPlanCannotInvokeWriterTest() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            prepare(f, owner);
            var before = f.snapshot();
            try (var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                    var c = f.open()) {
                var store = load(c, 72, 12);
                var writes = new AtomicInteger();
                var service = new VatService(store.getCurrentYear(), v -> writes.incrementAndGet());
                var plan =
                        service.plan(
                                NormalizedApplicationVatReportsTest.FROM,
                                NormalizedApplicationVatReportsTest.TO);
                assertEquals(0, plan.voucher().getNumber());
                assertTrue(plan.voucher().getRows().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> service.commit(plan));
                assertEquals(0, writes.get());
            }
            assertEquals(before, f.snapshot());
        }
    }

    static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
