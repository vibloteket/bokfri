package org.fribok.bookkeeping.service.openingbalance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OpeningBalanceServiceIntegrationTest {
    @BeforeAll
    static void setup() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @BeforeEach
    void reset() {
        SSDBTestFixture.resetCaches();
    }

    @AfterEach
    void drain() {
        SSDBTestFixture.drainUncaughtExceptions();
    }

    @Test
    void balancedValuesCanBeReplaced() {
        SSDB database = SSDB.getInstance();
        SSNewAccountingYear year = database.getCurrentYear();
        List<SSAccount> accounts = SSAccountMath.getBalanceAccounts(year);
        OpeningBalanceService service = new OpeningBalanceService(database);
        Map<SSAccount, BigDecimal> old = new HashMap<>(year.getInBalance());
        try {
            OpeningBalancePlan result = service.replace(year, Map.of(
                    accounts.get(0).getNumber(), new BigDecimal("10"),
                    accounts.get(1).getNumber(), new BigDecimal("-10")));
            assertThat(result.difference()).isZero();
            assertThat(service.current(year).balances()).hasSize(2);
        } finally {
            year.setInBalance(old);
            database.updateAccountingYear(year);
        }
    }

    @Test
    void carryForwardRoundsAndDisclosesLargestRemainderAdjustment() {
        SSDB database = SSDB.getInstance();
        SSNewAccountingYear from = database.getCurrentYear();
        SSNewAccountingYear to = new SSNewAccountingYear();
        to.setLocalFrom(from.getLocalTo().plusDays(1));
        to.setLocalTo(from.getLocalTo().plusYears(1));
        to.setAccountPlan(from.getAccountPlan());
        List<SSAccount> accounts = SSAccountMath.getBalanceAccounts(from);
        Map<SSAccount, BigDecimal> old = new HashMap<>(from.getInBalance());
        List<SSVoucher> oldVouchers = new ArrayList<>(from.getVouchers());
        try {
            from.getVouchers().clear();
            from.setInBalance(Map.of(
                    accounts.get(0), new BigDecimal("100.004"),
                    accounts.get(1), new BigDecimal("50.004"),
                    accounts.get(2), new BigDecimal("-150.008")));

            OpeningBalancePlan result = new OpeningBalanceService(database)
                    .carryForward(from, to, false);

            assertThat(result.difference()).isZero();
            assertThat(result.adjustment()).isNotNull();
            assertThat(result.adjustment().amount().abs()).isEqualByComparingTo("0.01");
            assertThat(result.balances()).allSatisfy(entry ->
                    assertThat(entry.amount().scale()).isEqualTo(2));
            assertThat(to.getInBalance()).isEmpty();
        } finally {
            from.setInBalance(old);
            from.getVouchers().clear();
            from.getVouchers().addAll(oldVouchers);
        }
    }
}
