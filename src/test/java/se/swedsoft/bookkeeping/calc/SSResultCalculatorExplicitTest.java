package se.swedsoft.bookkeeping.calc;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SSResultCalculatorExplicitTest {
    @Test
    void explicitInputsPreservePeriodYearBudgetPreviousYearAndDimensions() throws Exception {
        SSNewAccountingYear year = year("2024-01-01", "2024-12-31");
        SSNewAccountingYear previous = year("2023-01-01", "2023-12-31");
        SSVoucherRow period = row(3010, "30");
        period.setProjectNr("P");
        period.setResultUnitNr("R");
        SSVoucherRow crossed = row(3010, "700");
        crossed.setCrossed(true);
        year.setVouchers(List.of(voucher("2024-01-01", row(3010, "100")),
                voucher("2024-02-29", period, crossed, row(9999, "800"), row(3010, null)),
                voucher("2024-03-01", row(3010, "200"))));
        previous.setVouchers(List.of(voucher("2023-02-28", row(3010, "40")),
                voucher("2023-03-01", row(3010, "900"))));
        SSAccount account = year.getAccounts().get(0);
        year.getBudget().setYear(year);
        year.getBudget().setSumForAccount(account, new BigDecimal("1200"));
        var context = new SSResultCalculator.Context(List.of(previous, year),
                List.of(new SSNewProject("P", "Project", "")),
                List.of(new SSNewResultUnit("R", "Result unit")));
        SSResultCalculator calculator = new SSResultCalculator(year,
                LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29), null, null);
        calculator.calculate(context);

        assertThat(calculator.getChange().get(account)).isEqualByComparingTo("330");
        assertThat(calculator.getChangePeriod().get(account)).isEqualByComparingTo("30");
        assertThat(calculator.getChangeLastYear().get(account)).isEqualByComparingTo("40");
        assertThat(calculator.getChangeBudget().get(account)).isEqualByComparingTo("100");
        assertThat(calculator.getProjectChangePeriod().get(account)).isEqualByComparingTo("30");
        assertThat(calculator.getResultunitChangePeriod().get(account)).isEqualByComparingTo("30");
        assertThat(calculator.getChange().keySet()).containsExactly(account);
    }

    @Test
    void fiscalYearComparisonUsesCalendarDatesRatherThanAdjacentYearIdentity() throws Exception {
        SSNewAccountingYear year = year("2023-07-01", "2024-06-30");
        SSNewAccountingYear previous = year("2022-07-01", "2023-06-30");
        year.setVouchers(List.of(voucher("2024-03-01", row(3010, "20"))));
        previous.setVouchers(List.of(voucher("2023-03-01", row(3010, "10")),
                voucher("2023-04-01", row(3010, "90"))));
        SSResultCalculator calculator = new SSResultCalculator(year,
                LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), null, null);
        calculator.calculate(new SSResultCalculator.Context(List.of(year, previous), List.of(), List.of()));
        SSAccount account = year.getAccounts().get(0);
        assertThat(calculator.getChangePeriod().get(account)).isEqualByComparingTo("20");
        assertThat(calculator.getChangeLastYear().get(account)).isEqualByComparingTo("10");
        assertThat(calculator.getChangeBudget()).isEmpty();
    }

    @Test
    void missingPreviousYearAndEmptyPeriodRemainEmpty() throws Exception {
        SSNewAccountingYear year = year("2024-01-01", "2024-12-31");
        SSResultCalculator calculator = new SSResultCalculator(year,
                year.getLocalFrom(), year.getLocalTo(), null, null);
        calculator.calculate(new SSResultCalculator.Context(List.of(year), List.of(), List.of()));
        assertThat(calculator.getChange()).isEmpty();
        assertThat(calculator.getChangePeriod()).isEmpty();
        assertThat(calculator.getChangeLastYear()).isEmpty();
        assertThat(calculator.getChangeBudget()).isEmpty();
    }

    private static SSNewAccountingYear year(String from, String to) {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(LocalDate.parse(from));
        year.setLocalTo(LocalDate.parse(to));
        SSAccountPlan plan = new SSAccountPlan();
        plan.getAccounts().add(new SSAccount(3010));
        year.setAccountPlan(plan);
        year.setVouchers(List.of());
        return year;
    }

    private static SSVoucher voucher(String date, SSVoucherRow... rows) {
        SSVoucher voucher = new SSVoucher(1);
        voucher.setLocalDate(LocalDate.parse(date));
        voucher.getRows().addAll(List.of(rows));
        return voucher;
    }

    private static SSVoucherRow row(int number, String credit) {
        SSVoucherRow row = new SSVoucherRow() {
            @Override public SSAccount getAccount() { throw new AssertionError("Legacy account lookup"); }
            @Override public SSNewProject getProject() { throw new AssertionError("Legacy project lookup"); }
            @Override public SSNewResultUnit getResultUnit() { throw new AssertionError("Legacy unit lookup"); }
            @Override public boolean isValid() { throw new AssertionError("Legacy validity lookup"); }
        };
        row.setAccountNr(number);
        row.setCredit(credit == null ? null : new BigDecimal(credit));
        return row;
    }
}
