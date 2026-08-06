package org.fribok.bookkeeping.service.supplierinvoice;
import se.swedsoft.bookkeeping.data.*; import java.time.LocalDate; import java.util.List;
public record SupplierInvoiceJournalPlan(int journalNumber,LocalDate from,LocalDate to,List<SSSupplierInvoice> invoices,SSVoucher voucher){public SupplierInvoiceJournalPlan{invoices=List.copyOf(invoices);}}
