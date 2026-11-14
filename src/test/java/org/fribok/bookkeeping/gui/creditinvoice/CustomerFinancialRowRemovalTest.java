package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.*;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.*;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerFinancialRowRemovalTest {
 @Test void bothKindsPreviewRemovalWithoutWritesAndPreserveEveryOtherSqlValueAndKeyTest()throws Exception {
  for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,kind);var editor=editor(f,kind,id);var loaded=editor.load();assertEquals(List.of(7,19),loaded.data().lines().stream().map(l->l.rowNumber()).toList());
   var before=snapshot(f);var expected=CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(),Set.of(),Set.of(),Set.of(7));f.anchor.rollback();
   var values=patch(loaded,List.of(7));assertThrows(UnsupportedOperationException.class,()->values.removedRows().clear());var preview=editor.preview(loaded,values);
   assertEquals(0,new BigDecimal("165").compareTo(preview.before()));assertEquals(0,new BigDecimal("40").compareTo(preview.after()));assertEquals(before,snapshot(f));f.anchor.rollback();
   var saved=editor.save(preview);assertSame(saved,editor.save(preview));assertEquals(expected,snapshot(f));f.anchor.rollback();
   var fresh=editor(f,kind,id).load();assertEquals(List.of(19),fresh.data().lines().stream().map(l->l.rowNumber()).toList());assertEquals("P-Å",fresh.data().lines().getFirst().project());assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
  }
 }
 @Test void duplicateForeignFinalRowAndConflictingAmountSetsAreRejectedWithoutWritesTest()throws Exception {
  try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,Kind.INVOICE);var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();var before=snapshot(f);f.anchor.rollback();
   for(var keys:List.of(List.of(7,7),List.of(999),List.of(7,19)))assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,keys)));
   var bad=new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),List.of(),null,null,List.of(),List.of(7));
   assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,bad));assertEquals(before,snapshot(f));
  }
 }
 @Test void metadataOrSourceRowChangesInvalidateRemovalUntilReloadTest()throws Exception {
  try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,Kind.INVOICE);var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,List.of(7)));
   try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET ocr_number='external' WHERE id="+id);}f.anchor.commit();assertThrows(IllegalArgumentException.class,()->editor.save(preview));
   var current=editor.load();var next=editor.preview(current,patch(current,List.of(7)));
   try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice_row SET description='external row' WHERE invoice_id="+id+" AND row_number=7");}f.anchor.commit();assertThrows(IllegalArgumentException.class,()->editor.save(next));assertFalse(editor.isBlocked());assertEquals(2,editor.load().data().lines().size());
  }
 }
 @Test void lateSecondDeleteFailureRestoresFirstRowAndEarlierEditsThenRetriesForBothKindsTest()throws Exception {
  for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=CustomerFinancialAccountingTest.document(f,kind,id);source.getRows().add(extra("Third",new BigDecimal("15")));if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
   try(var sql=f.anchor.createStatement()) {
    sql.executeUpdate("UPDATE "+table(kind)+"_row SET row_number=CASE row_number WHEN 0 THEN 7 WHEN 1 THEN 19 ELSE 31 END WHERE "+owner(kind)+"="+id);
    sql.executeUpdate("CREATE TABLE row_removal_guard(document_id BIGINT,row_number INTEGER,FOREIGN KEY(document_id,row_number) REFERENCES "+table(kind)+"_row("+owner(kind)+",row_number))");
    sql.executeUpdate("INSERT INTO row_removal_guard VALUES("+id+",19)");
   }f.anchor.commit();var editor=editor(f,kind,id);var loaded=editor.load();
   var values=new FinancialValues(LocalDate.of(2026,11,13),null,List.of(new LineAmounts(31,BigDecimal.ONE,new BigDecimal("25"))),List.of(),List.of(),new TaxValues(false,new BigDecimal("30"),null,null),null,List.of(),List.of(7,19));
   var preview=editor.preview(loaded,values);var before=snapshot(f);f.anchor.rollback();assertThrows(SQLException.class,()->editor.save(preview));assertEquals(before,snapshot(f));assertFalse(editor.isBlocked());f.anchor.rollback();
   try(var sql=f.anchor.createStatement()){sql.executeUpdate("DROP TABLE row_removal_guard");}f.anchor.commit();assertEquals(0,new BigDecimal("25").compareTo(editor.save(preview).total()));assertEquals(List.of(31),editor.load().data().lines().stream().map(l->l.rowNumber()).toList());
  }
 }
 @Test void localSavepointPreservesOuterWorkOnDuplicateMissingBookedOrForeignRemovalTest()throws Exception {
  for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,kind);var loaded=editor(f,kind,id).load();try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+" SET ocr_number='outer work' WHERE id="+id);}var before=snapshot(f);var store=new NormalizedInvoiceStore(f.anchor);
   for(var keys:List.of(List.of(7,7),List.of(7,999))) {
    var values=new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),List.of(),List.of(),List.of(),null,null,List.of(),keys);
    if(keys.get(1)==7)assertThrows(IllegalArgumentException.class,()->update(store,kind,71,id,values));else assertThrows(SQLException.class,()->update(store,kind,71,id,values));assertEquals(before,snapshot(f));
    assertThrows(SQLException.class,()->update(store,kind,999,id,values));if(kind==Kind.INVOICE)assertThrows(SQLException.class,()->update(store,kind,71,f.invoice,values));assertEquals(before,snapshot(f));
   }f.anchor.rollback();
  }
 }
 @Test void unknownRemovalCommitBlocksRetryBeforeAndAfterDurabilityTest()throws Exception {
  for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,Kind.INVOICE);var editor=new CustomerFinancialEditAdapter(()->{var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown removal commit");}try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});},f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
   var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,List.of(7)));assertThrows(SQLException.class,()->editor.save(preview));assertTrue(editor.isBlocked());assertThrows(SQLException.class,editor::load);assertThrows(SQLException.class,()->editor.save(preview));assertEquals(durable?List.of(19):List.of(7,19),editor(f,Kind.INVOICE,id).load().data().lines().stream().map(l->l.rowNumber()).toList());
  }
 }
 @Test void separateInvoiceAndCreditPostingUsesOnlySurvivingRowsAndDimensionsTest()throws Exception {
  for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
   long id=target(f,kind);var editor=editor(f,kind,id);var loaded=editor.load();editor.save(editor.preview(loaded,patch(loaded,List.of(7))));assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());f.anchor.rollback();
   var journal=new CustomerJournalAdapter(f::open,f.gate,71,11,kind==Kind.INVOICE?CustomerJournalAdapter.Kind.INVOICE:CustomerJournalAdapter.Kind.CREDIT,CustomerCreditGuiFixture.CLOCK);var preview=journal.preview(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));assertEquals(0,new BigDecimal("40").compareTo(preview.summary().debit()));assertEquals(0,new BigDecimal("40").compareTo(preview.summary().credit()));assertTrue(preview.summary().rows().stream().anyMatch(r->Integer.valueOf(3022).equals(r.account())&&"P-Å".equals(r.project())&&"U-1".equals(r.resultUnit())));journal.post(preview);assertThrows(SQLException.class,editor::load);
  }
 }
 static long target(CustomerCreditGuiFixture f,Kind kind)throws Exception {
  long id=CustomerFinancialDimensionsTest.target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=CustomerFinancialAccountingTest.document(f,kind,id);var keep=extra("Keep Å",new BigDecimal("40"));keep.setAccountNr(3022);keep.setProjectNr("P-Å");keep.setResultUnitNr("U-1");source.getRows().add(keep);if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
  try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+"_row SET row_number=CASE row_number WHEN 0 THEN 7 ELSE 19 END WHERE "+owner(kind)+"="+id);}f.anchor.commit();return id;
 }
 private static SSSaleRow extra(String text,BigDecimal price){var row=new SSSaleRow();row.setDescription(text);row.setQuantity(BigDecimal.ONE);row.setUnitprice(price);row.setAccountNr(3010);row.setTaxCode(SSTaxCode.TAXRATE_0);return row;}
 static CustomerFinancialEditAdapter editor(CustomerCreditGuiFixture f,Kind kind,long id){return CustomerFinancialAccountingTest.editor(f,kind,id);}
 static FinancialValues patch(CustomerFinancialEditAdapter.Loaded loaded,List<Integer> removed){return new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows().stream().filter(r->!removed.contains(r.rowNumber())).toList(),List.of(),List.of(),null,null,List.of(),removed);}
 private static Map<String,List<String>> snapshot(CustomerCreditGuiFixture f)throws Exception{return CustomerFinancialEditAdapterTest.snapshot(f,null,-1,Set.of());}
 private static void update(NormalizedInvoiceStore store,Kind kind,int company,long id,FinancialValues values)throws Exception{if(kind==Kind.INVOICE)store.updateInvoiceFinancialValues(company,id,values);else store.updateCreditFinancialValues(company,id,values);}
 private static String table(Kind kind){return kind==Kind.INVOICE?"customer_invoice":"customer_credit_invoice";}
 private static String owner(Kind kind){return kind==Kind.INVOICE?"invoice_id":"credit_invoice_id";}
}
