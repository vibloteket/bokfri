package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.calc.util.SSVATUtil;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Headless VAT report and settlement use cases. */
public final class VatService {
    private final SSDB db;
    private final SSNewAccountingYear year;
    private final VoucherWriter writer;

    public VatService(SSDB db) {
        this.db = db;
        year = null;
        writer = null;
    }

    /** Creates a service from a hydrated year and an explicit voucher persistence operation. */
    public VatService(SSNewAccountingYear year, VoucherWriter writer) {
        db = null;
        this.year = year;
        this.writer = writer;
    }

    /** Writes and numbers a settlement voucher in the selected storage backend. */
    @FunctionalInterface
    public interface VoucherWriter {
        void write(SSVoucher voucher) throws SQLException;
    }

    public VatReport report(LocalDate from, LocalDate to) {
        return report(from, to, false);
    }

    /**
     * Calculates the explicit whole-krona field policy: aggregate each field before discarding
     * fractional kronor, then derive box 49 from the declared tax fields. Available only for a
     * hydrated year; existing report/plan compatibility is unchanged. This is not a complete filing
     * specification or a posting authorization.
     *
     * @param from inclusive period start
     * @param to inclusive period end
     * @return immutable field amounts under the explicit declaration policy
     */
    public VatReport declarationReport(LocalDate from, LocalDate to) {
        requireExplicitDeclarationContext();
        return report(from, to, true);
    }

    private VatReport report(LocalDate from, LocalDate to, boolean declaration) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("VAT period is invalid");
        }
        List<SSVoucher> vouchers = SSVoucherMath.getVouchers(vouchers(), from, to);
        // A supplied hydrated year is an explicit context: never resolve rows through SSDB.
        // Keep the legacy SSDB constructor's existing account/validity behavior unchanged.
        List<SSAccount> selected = accounts();
        if (db == null) {
            Set<Integer> numbers = new java.util.HashSet<>();
            for (SSAccount account : selected) {
                if (account == null
                        || account.getNumber() == null
                        || !numbers.add(account.getNumber())) {
                    throw new IllegalArgumentException(
                            "Explicit VAT accounts must have unique known numbers");
                }
            }
        }
        Map<SSAccount, BigDecimal> credit =
                db == null
                        ? SSVoucherMath.getCreditMinusDebetSum(vouchers, selected)
                        : SSVoucherMath.getCreditMinusDebetSum(vouchers);
        Map<SSAccount, BigDecimal> debit =
                db == null
                        ? SSVoucherMath.getDebetMinusCreditSum(vouchers, selected)
                        : SSVoucherMath.getDebetMinusCreditSum(vouchers);
        List<VatReportBox> boxes = new ArrayList<>();
        add(boxes, 5, "MP1,MP2,MP3,PTOG", sum(credit, declaration, "MP1", "MP2", "MP3", "PTOG"));
        add(boxes, 10, "U1,UVL", sum(credit, declaration, "U1", "UVL"));
        add(boxes, 11, "U2", sum(credit, declaration, "U2"));
        add(boxes, 12, "U3", sum(credit, declaration, "U3"));
        add(boxes, 20, "VFEU", sum(debit, declaration, "VFEU"));
        add(boxes, 21, "TFEU", sum(debit, declaration, "TFEU"));
        add(boxes, 22, "TFFU", sum(debit, declaration, "TFFU"));
        add(boxes, 30, "U1MI,UEU,UTFU", sum(credit, declaration, "U1MI", "UEU", "UTFU"));
        add(boxes, 31, "U2MI", sum(credit, declaration, "U2MI"));
        add(boxes, 32, "U3MI", sum(credit, declaration, "U3MI"));
        add(boxes, 48, "I,IVL", sum(debit, declaration, "I", "IVL"));
        add(
                boxes,
                50,
                "IBU,IBU1,IBU2,IBU3",
                sum(credit, declaration, "IBU", "IBU1", "IBU2", "IBU3"));
        add(boxes, 60, "UI1", sum(credit, declaration, "UI1"));
        add(boxes, 61, "UI2", sum(credit, declaration, "UI2"));
        add(boxes, 62, "UI3", sum(credit, declaration, "UI3"));
        BigDecimal vat = box(boxes, 10).add(box(boxes, 11)).add(box(boxes, 12))
                .add(box(boxes, 30)).add(box(boxes, 31)).add(box(boxes, 32))
                .add(box(boxes, 60)).add(box(boxes, 61)).add(box(boxes, 62)).subtract(box(boxes, 48));
        add(boxes, 49, "R1,R2", vat);
        return new VatReport(from, to, boxes, vat);
    }

    public VatSettlementPlan plan(LocalDate from, LocalDate to) {
        VatReport report = report(from, to);
        List<SSAccount> accounts = accounts();
        SSAccount receivable =
                SSAccountMath.getAccountWithVATCode(accounts, "R1", fallback(1650))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "VAT receivable account is ambiguous or missing"));
        SSAccount payable =
                SSAccountMath.getAccountWithVATCode(accounts, "R2", fallback(2650))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "VAT payable account is ambiguous or missing"));
        SSAccount rounding =
                SSAccountMath.getAccountWithVATCode(accounts, "A", fallback(3740))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "VAT rounding account is ambiguous or missing"));
        SSVoucher voucher =
                db == null
                        ? SSVATUtil.generateVATVoucher(
                                "VAT",
                                from,
                                to,
                                receivable,
                                payable,
                                rounding,
                                accounts,
                                vouchers())
                        : SSVATUtil.generateVATVoucher(
                                "VAT", from, to, receivable, payable, rounding);
        return new VatSettlementPlan(report, voucher);
    }

    /**
     * Plans an unnumbered settlement using {@link #declarationReport} field amounts. Exact balances
     * of every supported tax-field account are reversed; the signed control equals box 49 and the
     * remaining exact difference goes to rounding. Requires exactly one explicit R1, R2 and A
     * account, without numeric fallbacks. Does not write, lock a period, detect stale sources or
     * authorize posting.
     *
     * @param from inclusive period start
     * @param to inclusive period end
     * @return the declaration report and a mutable, unnumbered voucher plan
     */
    public VatSettlementPlan declarationPlan(LocalDate from, LocalDate to) {
        VatReport report = declarationReport(from, to);
        List<SSAccount> selected = accounts();
        SSAccount receivable = declarationRole(selected, "R1");
        SSAccount payable = declarationRole(selected, "R2");
        SSAccount rounding = declarationRole(selected, "A");
        Set<Integer> taxFields = Set.of(10, 11, 12, 30, 31, 32, 48, 60, 61, 62);
        Set<String> taxCodes = new java.util.HashSet<>();
        for (VatReportBox box : report.boxes()) {
            if (taxFields.contains(box.number())) {
                taxCodes.addAll(List.of(box.vatCodes().split(",")));
            }
        }
        Map<SSAccount, BigDecimal> balances =
                SSVoucherMath.getCreditMinusDebetSum(
                        SSVoucherMath.getVouchers(vouchers(), from, to), selected);
        SSVoucher voucher = new SSVoucher(0, to);
        DateTimeFormatter dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
        voucher.setDescription(
                String.format(
                        SSBundle.getBundle().getString("vatreport2015.voucherdescription"),
                        from.format(dates),
                        to.format(dates)));
        BigDecimal exactNet = BigDecimal.ZERO;
        for (SSAccount account : selected) {
            BigDecimal balance = balances.get(account);
            if (!taxCodes.contains(account.getVATCode())
                    || balance == null
                    || balance.signum() == 0) {
                continue;
            }
            SSVoucherRow row = new SSVoucherRow();
            row.setAccount(account);
            row.setValue(balance);
            voucher.addVoucherRow(row);
            exactNet = exactNet.add(balance);
        }
        BigDecimal declared = report.vatToPayOrRefund();
        if (declared.signum() != 0) {
            SSVoucherRow control = new SSVoucherRow();
            if (declared.signum() > 0) {
                control.setAccount(payable);
                control.setCredit(declared);
            } else {
                control.setAccount(receivable);
                control.setDebet(declared.abs());
            }
            voucher.addVoucherRow(control);
        }
        BigDecimal difference = declared.subtract(exactNet);
        if (difference.signum() != 0) {
            SSVoucherRow row = new SSVoucherRow();
            row.setAccount(rounding);
            row.setValue(difference);
            voucher.addVoucherRow(row);
        }
        return new VatSettlementPlan(report, voucher);
    }

    private void requireExplicitDeclarationContext() {
        if (db != null || year == null || year.getAccountPlan() == null) {
            throw new IllegalStateException(
                    "Declaration policy requires an explicit hydrated year");
        }
    }

    private static SSAccount declarationRole(List<SSAccount> selected, String code) {
        return SSAccountMath.getAccountWithVATCode(selected, code, null)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Declaration role is missing or ambiguous: " + code));
    }

    /** Persists a planned settlement, assigning its number in the selected backend. */
    public VatSettlementResult commit(VatSettlementPlan plan) throws SQLException {
        if (plan.voucher().getRows().isEmpty()) {
            throw new IllegalArgumentException("VAT settlement has no rows");
        }
        if (db == null) {
            writer.write(plan.voucher());
        } else {
            db.addVoucher(plan.voucher(), false);
        }
        return new VatSettlementResult(plan.voucher().getNumber());
    }

    private List<SSAccount> accounts() {
        return db == null ? year.getAccountPlan().getAccounts() : db.getAccounts();
    }

    private List<SSVoucher> vouchers() {
        return db == null ? year.getVouchers() : db.getVouchers();
    }

    private SSAccount fallback(int number) {
        SSAccountPlan plan = db == null ? year.getAccountPlan() : db.getCurrentAccountPlan();
        return plan == null ? null : plan.getAccount(number);
    }

    private BigDecimal sum(
            Map<SSAccount, BigDecimal> values, boolean declaration, String... codes) {
        BigDecimal result = BigDecimal.ZERO;
        Set<String> selected = Set.of(codes);
        for (SSAccount account : accounts()) {
            if (account.getVATCode() != null && selected.contains(account.getVATCode())
                    && values.get(account) != null) {
                BigDecimal amount = values.get(account);
                result = result.add(declaration ? amount : amount.setScale(0, RoundingMode.DOWN));
            }
        }
        return declaration ? result.setScale(0, RoundingMode.DOWN) : result;
    }

    private static void add(List<VatReportBox> boxes, int number, String codes, BigDecimal amount) {
        boxes.add(new VatReportBox(number, codes, amount));
    }

    private static BigDecimal box(List<VatReportBox> boxes, int number) {
        return boxes.stream().filter(box -> box.number() == number).findFirst().orElseThrow().amount();
    }
}
