package org.fribok.bookkeeping.service.inpayment;

import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.List;

/** Planned customer inpayment journal before commit. */
public record InpaymentJournalPlan(int journalNumber, LocalDate from, LocalDate to,
                                   List<SSInpayment> inpayments, SSVoucher voucher) {
    public InpaymentJournalPlan { inpayments = List.copyOf(inpayments); }
}
