package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only, non-exhaustive account/dimension observations for an explicit VAT proposal. An
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
     * dimensions are reported when exact raw-key group reversals are missing or differ, even
     * when account totals net to zero. Cancelling groups need no row. Does not
     * validate project/unit ownership, source correctness, periods/duplicates/locks, posting
     * transactions or commit durability.
     *
     * @param report explicit field-total declaration report and inclusive source period
     * @param accounts explicit selected-year account catalogue
     * @param sources explicit selected-year source vouchers
     * @param proposal unnumbered explicit proposal to inspect, without mutating it
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
        var selected = VatSettlementGroups.accounts(accounts);
        var expected = VatSettlementGroups.balances(report, accounts, sources);
        var actual = new HashMap<VatSettlementGroups.Key, java.math.BigDecimal>();
        for (var row : proposal.getRows()) {
            actual.merge(
                    VatSettlementGroups.key(row),
                    VatSettlementGroups.creditMinusDebit(row).negate(),
                    java.math.BigDecimal::add);
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
        Set<String> codes = VatSettlementGroups.codes(report);
        for (SSVoucher voucher : SSVoucherMath.getVouchers(sources, report.from(), report.to())) {
            int index = 0;
            for (var row : voucher.getRows()) {
                index++;
                SSAccount account = selected.get(row.getAccountNr());
                if (!VatSettlementGroups.eligible(row, selected, codes)) continue;
                var key = VatSettlementGroups.key(row);
                if (expected.get(key).compareTo(actual.getOrDefault(key, java.math.BigDecimal.ZERO))
                        == 0) continue;
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
