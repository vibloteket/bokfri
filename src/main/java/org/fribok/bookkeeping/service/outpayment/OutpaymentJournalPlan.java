package org.fribok.bookkeeping.service.outpayment;

import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.List;

/** Planned customer outpayment journal before commit. */
public record OutpaymentJournalPlan(int journalNumber, LocalDate from, LocalDate to,
                                   List<SSOutpayment> outpayments, SSVoucher voucher) {
    public OutpaymentJournalPlan { outpayments = List.copyOf(outpayments); }
}
