package org.fribok.bookkeeping.service.supplierinvoice;
import se.swedsoft.bookkeeping.calc.math.*; import se.swedsoft.bookkeeping.data.*; import se.swedsoft.bookkeeping.data.system.SSDB;
import java.time.LocalDate; import java.util.*;
/** Supplier invoice use cases shared by Swing and CLI. */
public final class SupplierInvoiceService {
 private final SSDB db; public SupplierInvoiceService(SSDB db){this.db=db;}
 public List<SSSupplierInvoice> list(){return db.getSupplierInvoices().stream().sorted(Comparator.comparing(SSSupplierInvoice::getNumber,Comparator.nullsLast(Integer::compareTo))).toList();}
 public Optional<SSSupplierInvoice> find(int n){return list().stream().filter(i->i.getNumber()!=null&&i.getNumber()==n).findFirst();}
 public int nextNumber(){return list().stream().map(SSSupplierInvoice::getNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(db.getCurrentCompany().getAutoIncrement().getNumber("supplierinvoice"))+1;}
 public SupplierInvoiceValidationResult validate(SSSupplierInvoice i){return SupplierInvoiceValidator.validate(i);}
 public SSSupplierInvoice create(SSSupplierInvoice i){var v=validate(i);if(!v.valid())throw new SupplierInvoiceValidationException(v);db.addSupplierInvoice(i);return i;}
 public SupplierInvoiceJournalPlan planJournal(LocalDate f,LocalDate t){if(f==null||t==null||t.isBefore(f))throw new IllegalArgumentException("Journal period is invalid");
  List<SSSupplierInvoice> invoices=list().stream().filter(i->!i.isEntered()&&SSSupplierInvoiceMath.inPeriod(i,f,t)).toList();
  int n=db.getCurrentCompany().getAutoIncrement().getNumber("supplierinvoicejournal")+1; SSVoucher v=new SSVoucher(0);v.setDescription("Leverantörsfakturajournal nr "+n);v.setLocalDate(t);
  for(var i:invoices)for(var r:i.generateVoucher().getRows())v.addVoucherRow(new SSVoucherRow(r));return new SupplierInvoiceJournalPlan(n,f,t,invoices,SSVoucherMath.compress(v));}
 public SupplierInvoiceJournalResult commitJournal(SupplierInvoiceJournalPlan p){if(p.invoices().isEmpty())throw new IllegalArgumentException("Supplier invoice journal has no invoices");
  for(var i:p.invoices()){i.setEntered();db.updateSupplierInvoice(i);} SSNewCompany c=db.getCurrentCompany();c.getAutoIncrement().doAutoIncrement("supplierinvoicejournal");db.updateCompany(c);db.addVoucher(p.voucher(),false);return new SupplierInvoiceJournalResult(p.journalNumber(),p.voucher().getNumber(),p.invoices().size());}
}
