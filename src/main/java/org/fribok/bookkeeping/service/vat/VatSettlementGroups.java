package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared exact raw-key grouping for the explicit planner and its read-only diagnostics. */
final class VatSettlementGroups {
    private VatSettlementGroups() {}

    /** Null, blank and delimiter-containing keys remain distinct without string concatenation. */
    record Key(int account, String project, String unit) {}

    static Map<Integer, SSAccount> accounts(List<SSAccount> accounts) {
        Map<Integer, SSAccount> selected = new LinkedHashMap<>();
        for (SSAccount account : accounts) {
            if (account == null
                    || account.getNumber() == null
                    || selected.putIfAbsent(account.getNumber(), account) != null) {
                throw new IllegalArgumentException("VAT accounts must have unique known numbers");
            }
        }
        return selected;
    }

    static Set<String> codes(VatReport report) {
        Set<Integer> fields = Set.of(10, 11, 12, 30, 31, 32, 48, 60, 61, 62);
        Set<String> codes = new HashSet<>();
        for (VatReportBox box : report.boxes()) {
            if (fields.contains(box.number())) codes.addAll(List.of(box.vatCodes().split(",")));
        }
        return codes;
    }

    static boolean eligible(SSVoucherRow row, Map<Integer, SSAccount> selected, Set<String> codes) {
        SSAccount account = selected.get(row.getAccountNr());
        return !row.isCrossed()
                && account != null
                && codes.contains(account.getVATCode())
                && ((row.getDebet() != null && row.getDebet().signum() != 0)
                        || (row.getCredit() != null && row.getCredit().signum() != 0));
    }

    static Key key(SSVoucherRow row) {
        return new Key(row.getAccountNr(), row.getProjectNr(), row.getResultUnitNr());
    }

    static BigDecimal creditMinusDebit(SSVoucherRow row) {
        BigDecimal credit = row.getCredit() == null ? BigDecimal.ZERO : row.getCredit();
        BigDecimal debit = row.getDebet() == null ? BigDecimal.ZERO : row.getDebet();
        return credit.subtract(debit);
    }

    /** Selected-account order, then first eligible occurrence of each raw dimension pair. */
    static Map<Key, BigDecimal> balances(
            VatReport report, List<SSAccount> accounts, List<SSVoucher> sources) {
        Map<Integer, SSAccount> selected = accounts(accounts);
        Set<String> codes = codes(report);
        Map<Integer, Map<Key, BigDecimal>> buckets = new HashMap<>();
        for (SSVoucher voucher : SSVoucherMath.getVouchers(sources, report.from(), report.to())) {
            for (SSVoucherRow row : voucher.getRows()) {
                if (eligible(row, selected, codes)) {
                    Key key = key(row);
                    buckets.computeIfAbsent(key.account(), ignored -> new LinkedHashMap<>())
                            .merge(key, creditMinusDebit(row), BigDecimal::add);
                }
            }
        }
        Map<Key, BigDecimal> result = new LinkedHashMap<>();
        for (SSAccount account : accounts) {
            Map<Key, BigDecimal> groups = buckets.get(account.getNumber());
            if (groups != null) result.putAll(groups);
        }
        return result;
    }
}
