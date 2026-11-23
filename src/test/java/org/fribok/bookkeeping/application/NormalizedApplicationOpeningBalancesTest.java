package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.application.NormalizedOpeningBalanceCommands.Draft;
import org.fribok.bookkeeping.application.NormalizedOpeningBalanceCommands.Entry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationOpeningBalancesTest {
    static Draft draft() {
        return new Draft(
                List.of(
                        new Entry(1910, new BigDecimal("123.45")),
                        new Entry(2010, new BigDecimal("-123.45"))));
    }

    static void accounts(Fixture f) throws Exception {
        try (var c = f.open();
                var s = c.createStatement()) {
            for (String values :
                    List.of(
                            "2010,'Equity <html>',TRUE",
                            "1510,'Inactive balance',FALSE",
                            "3010,'Result account',TRUE"))
                s.executeUpdate(
                        "INSERT INTO"
                            + " account(accounting_year_id,number,description,active,project_required,result_unit_required)"
                            + " SELECT id,"
                                + values
                                + ",FALSE,FALSE FROM accounting_year WHERE legacy_id=11");
            s.executeUpdate(
                    "INSERT INTO"
                        + " account(accounting_year_id,number,description,active,project_required,result_unit_required)"
                        + " SELECT id,1210,'Foreign balance',TRUE,FALSE,FALSE FROM accounting_year"
                        + " WHERE legacy_id=12");
        }
    }

    static int count(Fixture f, int year) throws Exception {
        try (var c = f.open();
                var r =
                        c.createStatement()
                                .executeQuery(
                                        "SELECT COUNT(*) FROM opening_balance WHERE"
                                            + " accounting_year_id=(SELECT id FROM accounting_year"
                                            + " WHERE legacy_id="
                                                + year
                                                + ")")) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void
            cataloguePreviewAndAtomicInitialInsertPreserveEveryOldRowAndReopenInReportsWithoutLegacySql()
                    throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            accounts(f);
            var lease = owner.select(71, 11);
            var commands = lease.openingBalances();
            var before = f.snapshot();
            assertEquals(
                    List.of(1910, 2010),
                    commands.accounts().stream()
                            .map(NormalizedOpeningBalanceCommands.Account::number)
                            .toList());
            var token = commands.preview(draft());
            assertEquals(before, f.snapshot());
            assertEquals("BAS95", token.planType());
            assertEquals(0, token.plan().difference().signum());
            assertEquals(0, new BigDecimal("123.45").compareTo(token.plan().debitTotal()));
            assertThrows(
                    UnsupportedOperationException.class, () -> token.plan().balances().clear());
            assertEquals(2, commands.create(token));
            assertTrue(lease.isActive());
            var after = f.snapshot();
            assertTrue(after.containsAll(before));
            assertEquals(before.size() + 2, after.size());
            assertEquals(0, count(f, 12));
            assertThrows(SQLException.class, () -> commands.create(token));
            assertThrows(SQLException.class, commands::accounts);
            assertThrows(SQLException.class, () -> commands.preview(draft()));
            try (var reopened = f.owner()) {
                var ledger =
                        reopened.select(71, 11)
                                .reports()
                                .accountLedger(
                                        1910, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
                assertTrue(ledger.report().rows().isEmpty());
                assertEquals(0, new BigDecimal("123.45").compareTo(ledger.report().opening()));
                assertEquals(ledger.report().opening(), ledger.report().closing());
            }
        }
    }

    @Test
    void resultInactiveForeignDuplicateEmptyZeroImbalancedAndExtremeValuesAreRefusedWithoutWrites()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).openingBalances();
            var before = f.snapshot();
            var inputs = new ArrayList<Draft>();
            inputs.add(new Draft(List.of()));
            inputs.add(new Draft(List.of(new Entry(1910, BigDecimal.ONE))));
            for (int account : List.of(3010, 1510, 1210, 9999))
                inputs.add(
                        new Draft(
                                List.of(
                                        new Entry(account, BigDecimal.ONE),
                                        new Entry(1910, BigDecimal.ONE.negate()))));
            for (String amount : List.of("0", "0.001", "1E+2147483647", "1E-2147483647"))
                inputs.add(
                        new Draft(
                                List.of(
                                        new Entry(1910, new BigDecimal(amount)),
                                        new Entry(2010, new BigDecimal(amount).negate()))));
            inputs.add(
                    new Draft(
                            List.of(
                                    new Entry(1910, BigDecimal.ONE),
                                    new Entry(1910, BigDecimal.ONE.negate()))));
            inputs.add(
                    new Draft(
                            List.of(
                                    new Entry(1910, new BigDecimal("1.00")),
                                    new Entry(2010, new BigDecimal("-0.99")))));
            inputs.add(
                    new Draft(
                            List.of(
                                    new Entry(1910, null),
                                    new Entry(2010, BigDecimal.ONE.negate()))));
            for (var input : inputs)
                assertThrows(IllegalArgumentException.class, () -> commands.preview(input));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void rawMissingOrUnknownPlanTypesAreRefusedInsteadOfLegacyBasDefaults() throws Exception {
        for (String type : Arrays.asList(null, "", "UNKNOWN"))
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var commands = owner.select(71, 11).openingBalances();
                try (var c = f.open();
                        var q =
                                c.prepareStatement(
                                        "UPDATE account_plan SET plan_type=? WHERE"
                                            + " accounting_year_id=(SELECT id FROM accounting_year"
                                            + " WHERE legacy_id=11)")) {
                    q.setString(1, type);
                    q.executeUpdate();
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, commands::accounts);
                assertThrows(SQLException.class, () -> commands.preview(draft()));
                assertEquals(before, f.snapshot());
            }
    }

    @Test
    void anyExistingBalanceEvenZeroOrJournalEvenEmptyBlocksInitialSetup() throws Exception {
        for (boolean balance : List.of(true, false))
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var commands = owner.select(71, 11).openingBalances();
                if (balance)
                    try (var c = f.open()) {
                        c.createStatement()
                                .executeUpdate(
                                        "INSERT INTO"
                                            + " opening_balance(accounting_year_id,account_id,amount)"
                                            + " SELECT accounting_year_id,id,0 FROM account WHERE"
                                            + " number=1910 AND accounting_year_id=(SELECT id FROM"
                                            + " accounting_year WHERE legacy_id=11)");
                    }
                else NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "No rows");
                var before = f.snapshot();
                assertThrows(SQLException.class, commands::accounts);
                assertThrows(SQLException.class, () -> commands.preview(draft()));
                assertEquals(before, f.snapshot());
            }
    }

    @Test
    void staleCataloguesCompanyYearTypeOrNewJournalInvalidateSingleUsePreview() throws Exception {
        for (String sql :
                List.of(
                        "UPDATE account SET description='Changed' WHERE number=2010",
                        "UPDATE account SET active=FALSE WHERE number=2010",
                        "UPDATE account_plan SET plan_type='BAS96' WHERE accounting_year_id=(SELECT"
                                + " id FROM accounting_year WHERE legacy_id=11)",
                        "UPDATE company SET name='Changed' WHERE legacy_id=71",
                        "UPDATE accounting_year SET ends_on=DATE '2026-12-30' WHERE"
                                + " legacy_id=11")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var commands = owner.select(71, 11).openingBalances();
                var token = commands.preview(draft());
                try (var c = f.open()) {
                    c.createStatement().executeUpdate(sql);
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.create(token));
                assertEquals(before, f.snapshot());
                assertThrows(SQLException.class, () -> commands.create(token));
            }
        }
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).openingBalances();
            var token = commands.preview(draft());
            NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "Inserted since preview");
            var before = f.snapshot();
            assertThrows(SQLException.class, () -> commands.create(token));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void lateSecondInsertFailureRollsBackEveryBalanceAndCanRetryOnlyWithFreshPreview()
            throws Exception {
        try (var f = new Fixture()) {
            accounts(f);
            var fail = new AtomicBoolean(true);
            try (var owner = failingOwner(f, fail)) {
                var commands = owner.select(71, 11).openingBalances();
                var token = commands.preview(draft());
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.create(token));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
                assertThrows(SQLException.class, () -> commands.create(token));
                fail.set(false);
                assertEquals(2, commands.create(commands.preview(draft())));
                assertEquals(2, count(f, 11));
            }
        }
    }

    @Test
    void commitUncertaintyBlocksAllFamiliesAndKnownCommitCloseFailureReportsSuccess()
            throws Exception {
        for (String mode : List.of("before", "after", "runtime", "close"))
            try (var f = new Fixture();
                    var owner = f.owner()) {
                accounts(f);
                var lease = owner.select(71, 11);
                var commands = lease.openingBalances();
                var token = commands.preview(draft());
                f.failure = mode;
                if (mode.equals("close")) {
                    assertEquals(2, commands.create(token));
                    assertFalse(owner.isBlocked());
                } else {
                    if (mode.equals("runtime"))
                        assertThrows(IllegalStateException.class, () -> commands.create(token));
                    else assertThrows(SQLException.class, () -> commands.create(token));
                    assertTrue(owner.isBlocked());
                    int opens = f.opens.get();
                    assertThrows(SQLException.class, commands::accounts);
                    assertThrows(SQLException.class, () -> lease.reports().accounts());
                    assertThrows(SQLException.class, () -> lease.voucherRegister().page(null));
                    assertThrows(SQLException.class, () -> lease.vouchers().accounts());
                    assertThrows(SQLException.class, owner::companies);
                    assertEquals(opens, f.opens.get());
                }
                assertEquals(mode.equals("after") || mode.equals("close") ? 2 : 0, count(f, 11));
            }
    }

    @Test
    void foreignReplacedAndClosedTokensRefuseBeforeSqlWhileOtherYearDataIsPreserved()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var other = f.owner()) {
            accounts(f);
            var commands = owner.select(71, 11).openingBalances();
            var token = commands.preview(draft());
            var foreign = other.select(72, 12).openingBalances();
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.create(token));
            assertEquals(opens, f.opens.get());
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET description='Foreign changed' WHERE"
                                        + " number=1210");
            }
            assertEquals(2, commands.create(token));
            assertEquals(0, count(f, 12));
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, commands::accounts);
            assertEquals(opens, f.opens.get());
            owner.close();
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> owner.select(71, 11));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void acceptedInsertCompletesOnOriginalLeaseAfterOwnerClosure() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var executor = Executors.newSingleThreadExecutor()) {
            accounts(f);
            var commands = owner.select(71, 11).openingBalances();
            var token = commands.preview(draft());
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var pending = executor.submit(() -> commands.create(token));
            try {
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
            } finally {
                f.release.countDown();
            }
            assertEquals(2, pending.get(5, TimeUnit.SECONDS));
            assertEquals(2, count(f, 11));
            assertEquals(0, count(f, 12));
        }
    }

    static NormalizedApplicationOwner failingOwner(Fixture f, AtomicBoolean fail) {
        return new NormalizedApplicationOwner(
                () -> {
                    var c = f.open();
                    var count = new AtomicInteger();
                    return (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (p, m, a) -> {
                                        try {
                                            var value = m.invoke(c, a);
                                            if (m.getName().equals("prepareStatement")
                                                    && ((String) a[0])
                                                            .startsWith(
                                                                    "INSERT INTO"
                                                                            + " opening_balance"
                                                                            + " ")) {
                                                var statement = (PreparedStatement) value;
                                                return Proxy.newProxyInstance(
                                                        PreparedStatement.class.getClassLoader(),
                                                        new Class<?>[] {PreparedStatement.class},
                                                        (sp, sm, sa) -> {
                                                            try {
                                                                var result =
                                                                        sm.invoke(statement, sa);
                                                                if (sm.getName()
                                                                                .equals(
                                                                                        "executeUpdate")
                                                                        && count.incrementAndGet()
                                                                                == 2
                                                                        && fail.get())
                                                                    throw new SQLException(
                                                                            "Late second opening"
                                                                                    + " insert"
                                                                                    + " failure");
                                                                return result;
                                                            } catch (InvocationTargetException e) {
                                                                throw e.getCause();
                                                            }
                                                        });
                                            }
                                            return value;
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                },
                Clock.systemUTC());
    }
}
