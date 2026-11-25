package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only, non-exhaustive account/dimension observations for an account-level VAT proposal. An
 * empty result is NOT bookability approval or authorization to post. No catalogues or source values
 * are repaired, assigned or resolved through ambient storage.
 */
public final class VatSettlementDiagnostics {
    private VatSettlementDiagnostics() {}

    public enum Kind {
        INACTIVE_ACCOUNT,
        REQUIRED_PROJECT_MISSING,
        REQUIRED_UNIT_MISSING,
        SOURCE_PROJECT_NOT_CARRIED,
        SOURCE_UNIT_NOT_CARRIED
    }

    /**
     * Frozen raw references; source row is one-based. Source fields are null for proposal issues.
     */
    public record Finding(
            Kind kind, int account, Integer sourceVoucher, Integer sourceRow, String dimension) {}

    /**
     * Observes current proposal accounts and eligible tax-source dimensions without SQL. Source
     * dimensions are observed even if account balances net to zero and no clearing row exists.
     * Non-null raw dimension keys, including blank keys, are never silently discarded. Does not
     * validate project/unit ownership, source correctness, periods/duplicates/locks, posting
     * transactions or commit durability.
     *
     * @param report explicit field-total declaration report and inclusive source period
     * @param accounts explicit selected-year account catalogue
     * @param sources explicit selected-year source vouchers
     * @param proposal unnumbered account-level proposal to inspect, without mutating it
     * @return immutable observations in proposal order followed by source voucher/row order
     */
    public static List<Finding> inspect(
            VatReport report,
            List<SSAccount> accounts,
            List<SSVoucher> sources,
            SSVoucher proposal) {
        Objects.requireNonNull(report, "declaration report");
        Objects.requireNonNull(accounts, "explicit accounts");
        Objects.requireNonNull(sources, "explicit sources");
        Objects.requireNonNull(proposal, "proposal");
        if (report.from() == null || report.to() == null || report.to().isBefore(report.from())) {
            throw new IllegalArgumentException("Diagnostic period is invalid");
        }
        if (proposal.getNumber() != 0 || !report.to().equals(proposal.getLocalDate())) {
            throw new IllegalArgumentException(
                    "Diagnostic proposal must be unnumbered and match period end");
        }
        var selected = new HashMap<Integer, SSAccount>();
        for (SSAccount account : accounts) {
            if (account == null
                    || account.getNumber() == null
                    || selected.putIfAbsent(account.getNumber(), account) != null) {
                throw new IllegalArgumentException(
                        "Diagnostic accounts must have unique known numbers");
            }
        }
        var proposalIssues = new LinkedHashSet<Finding>();
        for (var row : proposal.getRows()) {
            SSAccount account = selected.get(row.getAccountNr());
            if (account == null) throw new IllegalArgumentException("Unknown proposal account");
            int number = account.getNumber();
            if (!account.isActive()) {
                proposalIssues.add(new Finding(Kind.INACTIVE_ACCOUNT, number, null, null, null));
            }
            if (account.isProjectRequired() && absent(row.getProjectNr())) {
                proposalIssues.add(
                        new Finding(Kind.REQUIRED_PROJECT_MISSING, number, null, null, null));
            }
            if (account.isResultUnitRequired() && absent(row.getResultUnitNr())) {
                proposalIssues.add(
                        new Finding(Kind.REQUIRED_UNIT_MISSING, number, null, null, null));
            }
        }
        var result = new ArrayList<>(proposalIssues);
        Set<Integer> fields = Set.of(10, 11, 12, 30, 31, 32, 48, 60, 61, 62);
        Set<String> codes = new HashSet<>();
        for (VatReportBox box : report.boxes()) {
            if (fields.contains(box.number())) codes.addAll(List.of(box.vatCodes().split(",")));
        }
        for (SSVoucher voucher : SSVoucherMath.getVouchers(sources, report.from(), report.to())) {
            int index = 0;
            for (var row : voucher.getRows()) {
                index++;
                SSAccount account = selected.get(row.getAccountNr());
                if (row.isCrossed()
                        || account == null
                        || !codes.contains(account.getVATCode())
                        || !((row.getDebet() != null && row.getDebet().signum() != 0)
                                || (row.getCredit() != null && row.getCredit().signum() != 0))) {
                    continue;
                }
                if (row.getProjectNr() != null) {
                    result.add(
                            new Finding(
                                    Kind.SOURCE_PROJECT_NOT_CARRIED,
                                    account.getNumber(),
                                    voucher.getNumber(),
                                    index,
                                    row.getProjectNr()));
                }
                if (row.getResultUnitNr() != null) {
                    result.add(
                            new Finding(
                                    Kind.SOURCE_UNIT_NOT_CARRIED,
                                    account.getNumber(),
                                    voucher.getNumber(),
                                    index,
                                    row.getResultUnitNr()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean absent(String value) {
        return value == null || value.isBlank();
    }
}
