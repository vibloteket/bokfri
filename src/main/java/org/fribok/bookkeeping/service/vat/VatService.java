package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.calc.util.SSVATUtil;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
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
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("VAT period is invalid");
        }
        List<SSVoucher> vouchers = SSVoucherMath.getVouchers(vouchers(), from, to);
        Map<SSAccount, BigDecimal> credit = SSVoucherMath.getCreditMinusDebetSum(vouchers);
        Map<SSAccount, BigDecimal> debit = SSVoucherMath.getDebetMinusCreditSum(vouchers);
        List<VatReportBox> boxes = new ArrayList<>();
        add(boxes, 5, "MP1,MP2,MP3,PTOG", sum(credit, "MP1", "MP2", "MP3", "PTOG"));
        add(boxes, 10, "U1,UVL", sum(credit, "U1", "UVL"));
        add(boxes, 11, "U2", sum(credit, "U2"));
        add(boxes, 12, "U3", sum(credit, "U3"));
        add(boxes, 20, "VFEU", sum(debit, "VFEU"));
        add(boxes, 21, "TFEU", sum(debit, "TFEU"));
        add(boxes, 22, "TFFU", sum(debit, "TFFU"));
        add(boxes, 30, "U1MI,UEU,UTFU", sum(credit, "U1MI", "UEU", "UTFU"));
        add(boxes, 31, "U2MI", sum(credit, "U2MI"));
        add(boxes, 32, "U3MI", sum(credit, "U3MI"));
        add(boxes, 48, "I,IVL", sum(debit, "I", "IVL"));
        add(boxes, 50, "IBU,IBU1,IBU2,IBU3", sum(credit, "IBU", "IBU1", "IBU2", "IBU3"));
        add(boxes, 60, "UI1", sum(credit, "UI1"));
        add(boxes, 61, "UI2", sum(credit, "UI2"));
        add(boxes, 62, "UI3", sum(credit, "UI3"));
        BigDecimal vat = box(boxes, 10).add(box(boxes, 11)).add(box(boxes, 12))
                .add(box(boxes, 30)).add(box(boxes, 31)).add(box(boxes, 32))
                .add(box(boxes, 60)).add(box(boxes, 61)).add(box(boxes, 62)).subtract(box(boxes, 48));
        add(boxes, 49, "R1,R2", vat);
        return new VatReport(from, to, boxes, vat);
    }

    public VatSettlementPlan plan(LocalDate from, LocalDate to) {
        VatReport report = report(from, to);
        List<SSAccount> accounts = accounts();
        SSAccount receivable = SSAccountMath.getAccountWithVATCode(accounts, "R1", fallback(1650))
                .orElseThrow(() -> new IllegalStateException("VAT receivable account is ambiguous or missing"));
        SSAccount payable = SSAccountMath.getAccountWithVATCode(accounts, "R2", fallback(2650))
                .orElseThrow(() -> new IllegalStateException("VAT payable account is ambiguous or missing"));
        SSAccount rounding = SSAccountMath.getAccountWithVATCode(accounts, "A", fallback(3740))
                .orElseThrow(() -> new IllegalStateException("VAT rounding account is ambiguous or missing"));
        SSVoucher voucher = db == null
                ? SSVATUtil.generateVATVoucher("VAT", from, to, receivable, payable, rounding, accounts, vouchers())
                : SSVATUtil.generateVATVoucher("VAT", from, to, receivable, payable, rounding);
        return new VatSettlementPlan(report, voucher);
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

    private BigDecimal sum(Map<SSAccount, BigDecimal> values, String... codes) {
        BigDecimal result = BigDecimal.ZERO;
        Set<String> selected = Set.of(codes);
        for (SSAccount account : accounts()) {
            if (account.getVATCode() != null && selected.contains(account.getVATCode())
                    && values.get(account) != null) {
                result = result.add(values.get(account).setScale(0, RoundingMode.DOWN));
            }
        }
        return result;
    }

    private static void add(List<VatReportBox> boxes, int number, String codes, BigDecimal amount) {
        boxes.add(new VatReportBox(number, codes, amount));
    }

    private static BigDecimal box(List<VatReportBox> boxes, int number) {
        return boxes.stream().filter(box -> box.number() == number).findFirst().orElseThrow().amount();
    }
}
