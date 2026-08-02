package org.fribok.bookkeeping.service.voucher;

import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** UI-independent validation shared by Swing and automation entry points. */
public final class VoucherValidator {
    private VoucherValidator() {}

    public static VoucherValidationResult validate(SSVoucher voucher, SSNewAccountingYear year) {
        List<VoucherValidationIssue> issues = new ArrayList<>();
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;

        if (voucher == null) {
            issues.add(issue("VOUCHER_REQUIRED", "Verifikationen saknas.", null));
            return result(issues, debitTotal, creditTotal);
        }
        if (voucher.getDescription() == null || voucher.getDescription().isBlank()) {
            issues.add(issue("DESCRIPTION_REQUIRED", "Verifikationen saknar beskrivning.", null));
        }
        if (voucher.getLocalDate() == null) {
            issues.add(issue("DATE_REQUIRED", "Verifikationen saknar datum.", null));
        } else if (year != null && (voucher.getLocalDate().isBefore(year.getLocalFrom())
                || voucher.getLocalDate().isAfter(year.getLocalTo()))) {
            issues.add(issue("DATE_OUTSIDE_YEAR",
                    "Datumet ligger utanför det valda bokföringsåret.", null));
        }
        if (voucher.getRows() == null || voucher.getRows().isEmpty()) {
            issues.add(issue("ROWS_REQUIRED", "Verifikationen saknar rader.", null));
            return result(issues, debitTotal, creditTotal);
        }

        for (int index = 0; index < voucher.getRows().size(); index++) {
            SSVoucherRow row = voucher.getRows().get(index);
            int rowNumber = index + 1;
            if (row == null) {
                issues.add(issue("ROW_REQUIRED", "Raden saknas.", rowNumber));
                continue;
            }
            if (row.isCrossed()) {
                if (row.getEditedSignature() == null || row.getEditedSignature().isBlank()) {
                    issues.add(issue("CROSSED_ROW_SIGNATURE_REQUIRED",
                            "Struken rad saknar signatur.", rowNumber));
                }
                continue;
            }
            if (row.getAccount() == null) {
                issues.add(issue("ACCOUNT_NOT_FOUND", "Raden saknar ett giltigt konto.", rowNumber));
            }

            BigDecimal debit = normalized(row.getDebet());
            BigDecimal credit = normalized(row.getCredit());
            boolean hasDebit = debit != null && debit.signum() != 0;
            boolean hasCredit = credit != null && credit.signum() != 0;
            if (hasDebit == hasCredit) {
                issues.add(issue("ROW_AMOUNT_INVALID",
                        "Raden måste ha ett positivt belopp i antingen debet eller kredit.", rowNumber));
            } else if ((hasDebit && debit.signum() < 0) || (hasCredit && credit.signum() < 0)) {
                issues.add(issue("ROW_AMOUNT_NEGATIVE", "Radens belopp får inte vara negativt.", rowNumber));
            } else {
                if (hasDebit) {
                    debitTotal = debitTotal.add(debit);
                }
                if (hasCredit) {
                    creditTotal = creditTotal.add(credit);
                }
            }

            if (row.getAccount() != null && row.getAccount().isProjectRequired()
                    && row.getProject() == null) {
                issues.add(issue("PROJECT_REQUIRED", "Raden saknar projekt.", rowNumber));
            }
            if (row.getAccount() != null && row.getAccount().isResultUnitRequired()
                    && row.getResultUnit() == null) {
                issues.add(issue("RESULT_UNIT_REQUIRED", "Raden saknar resultatenhet.", rowNumber));
            }
            if (row.isAdded() && (row.getEditedSignature() == null
                    || row.getEditedSignature().isBlank())) {
                issues.add(issue("ADDED_ROW_SIGNATURE_REQUIRED",
                        "Tillagd rad saknar signatur.", rowNumber));
            }
        }

        BigDecimal difference = debitTotal.subtract(creditTotal).setScale(2, RoundingMode.HALF_UP);
        if (difference.signum() != 0) {
            issues.add(issue("VOUCHER_NOT_BALANCED",
                    "Verifikationens differens är inte noll.", null));
        }
        if (debitTotal.setScale(2, RoundingMode.HALF_UP).signum() == 0) {
            issues.add(issue("VOUCHER_TOTAL_ZERO", "Omslutningen är noll.", null));
        }
        return result(issues, debitTotal, creditTotal);
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static VoucherValidationIssue issue(String code, String message, Integer row) {
        return new VoucherValidationIssue(code, message, row);
    }

    private static VoucherValidationResult result(List<VoucherValidationIssue> issues,
            BigDecimal debitTotal, BigDecimal creditTotal) {
        return new VoucherValidationResult(issues.isEmpty(), issues, debitTotal, creditTotal);
    }
}
