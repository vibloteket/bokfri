package org.fribok.bookkeeping.service.openingbalance;

import se.swedsoft.bookkeeping.calc.SSBalanceCalculator;
import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opening balance validation, replacement, and carry-forward. */
public final class OpeningBalanceService {
    private final SSDB db;

    public OpeningBalanceService(SSDB database) {
        db = database;
    }

    public OpeningBalancePlan current(SSNewAccountingYear year) {
        return plan(year, year.getInBalance(), null);
    }

    public OpeningBalancePlan validate(SSNewAccountingYear year, Map<Integer, BigDecimal> input) {
        Map<SSAccount, BigDecimal> values = accountValues(year, input);
        OpeningBalancePlan result = plan(year, values, null);
        if (result.difference().setScale(2, RoundingMode.HALF_UP).signum() != 0) {
            throw new IllegalArgumentException("Opening balance is not balanced");
        }
        return result;
    }

    public OpeningBalancePlan replace(SSNewAccountingYear year, Map<Integer, BigDecimal> input) {
        OpeningBalancePlan result = validate(year, input);
        store(year, result);
        return result;
    }

    /**
     * Carries rounded balance-account totals into a new year. If separate rounding creates an
     * öre difference, the account with the largest discarded remainder is adjusted to preserve
     * the exact rounded grand total. The proposed adjustment is included in the returned plan.
     */
    public OpeningBalancePlan carryForward(SSNewAccountingYear from, SSNewAccountingYear to,
                                           boolean commit) {
        Map<Integer, BigDecimal> source = carryForwardSource(from, to);
        Map<Integer, BigDecimal> rounded = new LinkedHashMap<>();
        source.forEach((account, value) -> rounded.put(account, money(value)));

        BigDecimal difference = totals(rounded).difference();
        OpeningBalanceAdjustment adjustment = null;
        if (difference.signum() != 0) {
            Map.Entry<Integer, BigDecimal> selected = selectAdjustment(source, rounded, difference);
            BigDecimal before = rounded.get(selected.getKey());
            BigDecimal after = money(before.subtract(difference));
            rounded.put(selected.getKey(), after);
            SSAccount account = to.getAccountPlan().getAccount(selected.getKey());
            adjustment = new OpeningBalanceAdjustment(account.getNumber(), account.getDescription(),
                    before, after, after.subtract(before));
        }

        OpeningBalancePlan result = plan(to, accountValues(to, rounded), adjustment);
        if (result.difference().signum() != 0) {
            throw new IllegalArgumentException("Opening balance is not balanced after rounding");
        }
        if (commit) {
            store(to, result);
        }
        return result;
    }

    private Map<Integer, BigDecimal> carryForwardSource(SSNewAccountingYear from,
                                                        SSNewAccountingYear to) {
        Map<Integer, BigDecimal> input = new LinkedHashMap<>();
        for (Map.Entry<SSAccount, BigDecimal> entry
                : SSBalanceCalculator.getOutBalance(from).entrySet()) {
            SSAccount target = to.getAccountPlan().getAccount(entry.getKey().getNumber());
            if (target != null && SSAccountMath.isBalanceAccount(target, to)
                    && entry.getValue().signum() != 0) {
                input.put(target.getNumber(), entry.getValue());
            }
        }
        return input;
    }

    private Map<SSAccount, BigDecimal> accountValues(SSNewAccountingYear year,
                                                      Map<Integer, BigDecimal> input) {
        Map<SSAccount, BigDecimal> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, BigDecimal> entry : input.entrySet()) {
            SSAccount account = year.getAccountPlan().getAccount(entry.getKey());
            if (account == null) {
                throw new IllegalArgumentException("Account not found: " + entry.getKey());
            }
            if (!SSAccountMath.isBalanceAccount(account, year)) {
                throw new IllegalArgumentException("Not a balance account: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Amount missing for account " + entry.getKey());
            }
            values.put(account, entry.getValue());
        }
        return values;
    }

    private Map.Entry<Integer, BigDecimal> selectAdjustment(Map<Integer, BigDecimal> source,
                                                             Map<Integer, BigDecimal> rounded,
                                                             BigDecimal difference) {
        return source.entrySet().stream()
                .filter(entry -> rounded.get(entry.getKey()).subtract(difference).signum()
                        == rounded.get(entry.getKey()).signum())
                .min(Comparator.<Map.Entry<Integer, BigDecimal>, BigDecimal>comparing(entry ->
                        rounded.get(entry.getKey()).subtract(difference)
                                .subtract(entry.getValue()).abs())
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No balance account can absorb the rounding difference " + difference));
    }

    private void store(SSNewAccountingYear year, OpeningBalancePlan result) {
        Map<SSAccount, BigDecimal> values = new HashMap<>();
        for (OpeningBalanceEntry entry : result.balances()) {
            values.put(year.getAccountPlan().getAccount(entry.account()), entry.amount());
        }
        year.setInBalance(values);
        db.updateAccountingYear(year);
    }

    private OpeningBalancePlan plan(SSNewAccountingYear year,
                                    Map<SSAccount, BigDecimal> values,
                                    OpeningBalanceAdjustment adjustment) {
        List<OpeningBalanceEntry> rows = values.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() != 0)
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(SSAccount::getNumber)))
                .map(entry -> new OpeningBalanceEntry(entry.getKey().getNumber(),
                        entry.getKey().getDescription(), entry.getValue()))
                .toList();
        Totals totals = totals(rows.stream().collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.account(), entry.amount()), Map::putAll));
        return new OpeningBalancePlan(rows, totals.debit(), totals.credit(), totals.difference(),
                adjustment);
    }

    private static Totals totals(Map<Integer, BigDecimal> values) {
        BigDecimal debit = values.values().stream().filter(value -> value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = values.values().stream().filter(value -> value.signum() < 0)
                .map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Totals(debit, credit, debit.subtract(credit));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Totals(BigDecimal debit, BigDecimal credit, BigDecimal difference) {}
}
