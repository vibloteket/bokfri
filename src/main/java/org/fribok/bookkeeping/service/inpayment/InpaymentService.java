package org.fribok.bookkeeping.service.inpayment;

import se.swedsoft.bookkeeping.calc.math.SSInpaymentMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Customer inpayment use cases shared by Swing and CLI. */
public final class InpaymentService {
    private final SSDB database;

    public InpaymentService(SSDB database) { this.database = database; }

    public List<SSInpayment> list() {
        return database.getInpayments().stream()
                .sorted(Comparator.comparing(SSInpayment::getNumber,
                        Comparator.nullsLast(Integer::compareTo))).toList();
    }

    public Optional<SSInpayment> find(int number) {
        return list().stream().filter(item -> item.getNumber() != null
                && item.getNumber() == number).findFirst();
    }

    public int nextNumber() {
        return list().stream().map(SSInpayment::getNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(database.getCurrentCompany().getAutoIncrement().getNumber("inpayment")) + 1;
    }

    public InpaymentValidationResult validate(SSInpayment inpayment) {
        return InpaymentValidator.validate(inpayment, database.getInvoices(),
                invoice -> se.swedsoft.bookkeeping.calc.math.SSInvoiceMath.getSaldo(invoice));
    }

    public SSInpayment create(SSInpayment inpayment) {
        InpaymentValidationResult validation = validate(inpayment);
        if (!validation.valid()) {
            throw new InpaymentValidationException(validation);
        }
        database.addInpayment(inpayment);
        return inpayment;
    }

    public InpaymentJournalPlan planJournal(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        List<SSInpayment> inpayments = list().stream()
                .filter(item -> !item.isEntered() && SSInpaymentMath.inPeriod(item, from, to))
                .toList();
        int number = database.getCurrentCompany().getAutoIncrement()
                .getNumber("inpaymentjournal") + 1;
        SSVoucher combined = new SSVoucher(0);
        combined.setDescription("Inbetalningsjournal nr " + number);
        combined.setLocalDate(to);
        for (SSInpayment inpayment : inpayments) {
            for (SSVoucherRow row : inpayment.generateVoucher().getRows()) {
                combined.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return new InpaymentJournalPlan(number, from, to, inpayments,
                SSVoucherMath.compress(combined));
    }

    public InpaymentJournalResult commitJournal(InpaymentJournalPlan plan) {
        if (plan.inpayments().isEmpty()) {
            throw new IllegalArgumentException("Inpayment journal has no inpayments");
        }
        for (SSInpayment inpayment : plan.inpayments()) {
            if (inpayment.isEntered()) {
                throw new IllegalStateException("Inpayment " + inpayment.getNumber() + " is already entered");
            }
        }
        for (SSInpayment inpayment : plan.inpayments()) {
            inpayment.setEntered();
            database.updateInpayment(inpayment);
        }
        SSNewCompany company = database.getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement("inpaymentjournal");
        database.updateCompany(company);
        database.addVoucher(plan.voucher(), false);
        return new InpaymentJournalResult(plan.journalNumber(), plan.voucher().getNumber(),
                plan.inpayments().size());
    }
}
