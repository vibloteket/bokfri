package org.fribok.bookkeeping.service.year;

import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.time.LocalDate;
import java.util.List;

/** Accounting year creation service. */
public final class AccountingYearService {
    private final SSDB database;

    /** @param database legacy storage for GUI and legacy CLI callers */
    public AccountingYearService(SSDB database) {
        this.database = database;
    }

    /** @return global reusable account-plan templates */
    public List<SSAccountPlan> accountPlans() {
        return database.getAccountPlans();
    }

    /**
     * Validates and saves a year in legacy storage using the shared detached-year factory.
     * @param from first day, inclusive
     * @param to last day, inclusive
     * @param plan global template to copy
     * @return newly persisted year
     */
    public SSNewAccountingYear create(LocalDate from, LocalDate to, SSAccountPlan plan) {
        validateInputs(from, to, plan);
        SSNewAccountingYear year = prepare(from, to, plan, database.getYears());
        database.addAccountingYear(year);
        return year;
    }

    /**
     * Validates dates/overlap and makes a detached year with a deep account-plan copy, without storage access.
     * @param from first day, inclusive
     * @param to last day, inclusive (the same date is permitted)
     * @param plan global template, never modified
     * @param existingYears years of the selected company only
     * @return new year with no identity assigned by storage yet
     * @throws IllegalArgumentException if dates/template are missing or the inclusive period overlaps
     */
    public static SSNewAccountingYear prepare(LocalDate from, LocalDate to, SSAccountPlan plan,
            List<SSNewAccountingYear> existingYears) {
        validateInputs(from, to, plan);
        boolean overlap = existingYears.stream().anyMatch(year ->
                !to.isBefore(year.getLocalFrom()) && !from.isAfter(year.getLocalTo()));
        if (overlap) throw new IllegalArgumentException("Accounting year overlaps an existing year");
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setLocalFrom(from);
        year.setLocalTo(to);
        year.setAccountPlan(new SSAccountPlan(plan, true));
        return year;
    }

    private static void validateInputs(LocalDate from, LocalDate to, SSAccountPlan plan) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Accounting year dates are invalid");
        }
        if (plan == null) throw new IllegalArgumentException("Account plan is required");
    }
}
