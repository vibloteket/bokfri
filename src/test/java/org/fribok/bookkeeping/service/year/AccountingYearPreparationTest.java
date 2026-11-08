package org.fribok.bookkeeping.service.year;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingYearPreparationTest {
    @Test
    void makesAnIndependentAccountGraphAndDerivesBaseNameExactlyAsLegacyCreation() {
        SSAccountPlan template = new SSAccountPlan("Global template");
        template.setId(77);
        template.setBaseName("Earlier base");
        template.setAssessementYear("2028");
        SSAccount account = new SSAccount(1930);
        account.setDescription("Bank");
        account.setActive(false);
        account.setProjectRequired(true);
        account.setResultUnitRequired(true);
        template.addAccount(account);
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        SSNewAccountingYear year = AccountingYearService.prepare(leapDay, leapDay, template, List.of());
        assertThat(year.getLocalFrom()).isEqualTo(leapDay);
        assertThat(year.getLocalTo()).isEqualTo(leapDay);
        assertThat(year.getAccountPlan()).isNotSameAs(template);
        assertThat(year.getAccountPlan().getId()).isNull();
        assertThat(year.getAccountPlan().getBaseName()).isEqualTo("Global template");
        assertThat(year.getAccountPlan().getAssessementYear()).isEqualTo("2028");
        SSAccount copied = year.getAccounts().get(0);
        assertThat(copied).isNotSameAs(account);
        assertThat(copied.isActive()).isFalse();
        assertThat(copied.isProjectRequired()).isTrue();
        assertThat(copied.isResultUnitRequired()).isTrue();
        copied.setDescription("Independent description");
        copied.setActive(true);
        assertThat(account.getDescription()).isEqualTo("Bank");
        assertThat(account.isActive()).isFalse();
        assertThat(template.getId()).isEqualTo(77);
        assertThat(template.getBaseName()).isEqualTo("Earlier base");
        assertThat(year.getInBalance()).isEmpty();
        assertThat(year.getBudget().getBudget().values()).allMatch(java.util.Map::isEmpty);
    }

    @Test
    void rejectsMissingDatesOrTemplateAndPreservesInclusiveOverlapRules() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2024, 12, 31);
        SSAccountPlan plan = new SSAccountPlan("Template");
        assertThatThrownBy(() -> new AccountingYearService(null).create(null, end, plan))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Accounting year dates are invalid");
        assertThatThrownBy(() -> new AccountingYearService(null).create(start, end, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Account plan is required");
        assertThatThrownBy(() -> AccountingYearService.prepare(null, end, plan, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Accounting year dates are invalid");
        assertThatThrownBy(() -> AccountingYearService.prepare(start, null, plan, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Accounting year dates are invalid");
        assertThatThrownBy(() -> AccountingYearService.prepare(end, start, plan, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Accounting year dates are invalid");
        assertThatThrownBy(() -> AccountingYearService.prepare(start, end, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Account plan is required");
        SSNewAccountingYear existing = new SSNewAccountingYear();
        existing.setLocalFrom(start);
        existing.setLocalTo(end);
        assertThatThrownBy(() -> AccountingYearService.prepare(end, end.plusDays(1), plan, List.of(existing)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlaps");
        assertThat(AccountingYearService.prepare(end.plusDays(1), end.plusDays(1), plan, List.of(existing)))
                .isNotNull();
    }
}
