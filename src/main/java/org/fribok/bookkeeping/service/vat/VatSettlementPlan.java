package org.fribok.bookkeeping.service.vat;
import se.swedsoft.bookkeeping.data.SSVoucher;
/** VAT settlement preview. */
public record VatSettlementPlan(VatReport report, SSVoucher voucher) {}
