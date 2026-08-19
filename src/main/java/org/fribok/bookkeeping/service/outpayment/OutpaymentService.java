package org.fribok.bookkeeping.service.outpayment;

import se.swedsoft.bookkeeping.calc.math.SSOutpaymentMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Supplier outpayment use cases shared by Swing and CLI. */
public final class OutpaymentService {
    private final SSDB database;

    public OutpaymentService(SSDB database) { this.database = database; }

    public List<SSOutpayment> list() {
        return database.getOutpayments().stream()
                .sorted(Comparator.comparing(SSOutpayment::getNumber,
                        Comparator.nullsLast(Integer::compareTo))).toList();
    }

    public Optional<SSOutpayment> find(int number) {
        return list().stream().filter(item -> item.getNumber() != null
                && item.getNumber() == number).findFirst();
    }

    public int nextNumber() {
        return list().stream().map(SSOutpayment::getNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(database.getCurrentCompany().getAutoIncrement().getNumber("outpayment")) + 1;
    }

    public OutpaymentValidationResult validate(SSOutpayment outpayment) {
        return OutpaymentValidator.validate(outpayment, database.getSupplierInvoices(),
                invoice -> se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getSaldo(invoice));
    }

    public SSOutpayment create(SSOutpayment outpayment) {
        requireValid(outpayment);
        database.addOutpayment(outpayment);
        return outpayment;
    }

    public SSOutpayment update(SSOutpayment outpayment) {
        requireValid(outpayment);
        database.updateOutpayment(outpayment);
        return outpayment;
    }

    private void requireValid(SSOutpayment outpayment) {
        OutpaymentValidationResult validation = validate(outpayment);
        if (!validation.valid()) {
            throw new OutpaymentValidationException(validation);
        }
    }

    public OutpaymentJournalPlan planJournal(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        List<SSOutpayment> outpayments = list().stream()
                .filter(item -> !item.isEntered() && SSOutpaymentMath.inPeriod(item, from, to))
                .toList();
        int number = database.getCurrentCompany().getAutoIncrement()
                .getNumber("outpaymentjournal") + 1;
        SSVoucher combined = new SSVoucher(0);
        combined.setDescription("Inbetalningsjournal nr " + number);
        combined.setLocalDate(to);
        for (SSOutpayment outpayment : outpayments) {
            for (SSVoucherRow row : outpayment.generateVoucher().getRows()) {
                combined.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return new OutpaymentJournalPlan(number, from, to, outpayments,
                SSVoucherMath.compress(combined));
    }

    public OutpaymentJournalResult commitJournal(OutpaymentJournalPlan plan) {
        if (plan.outpayments().isEmpty()) {
            throw new IllegalArgumentException("Outpayment journal has no outpayments");
        }
        for (SSOutpayment outpayment : plan.outpayments()) {
            if (outpayment.isEntered()) {
                throw new IllegalStateException("Outpayment " + outpayment.getNumber() + " is already entered");
            }
        }
        for (SSOutpayment outpayment : plan.outpayments()) {
            outpayment.setEntered();
            database.updateOutpayment(outpayment);
        }
        SSNewCompany company = database.getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement("outpaymentjournal");
        database.updateCompany(company);
        database.addVoucher(plan.voucher(), false);
        return new OutpaymentJournalResult(plan.journalNumber(), plan.voucher().getNumber(),
                plan.outpayments().size());
    }
}
