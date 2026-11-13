package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.*;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CustomerFinancialHeaderTaxTest {
    @Test
    void bothKindsPreviewAndNarrowSaveUseExplicitHeaderValuesPreservingAllOtherSqlTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            assertEquals(Boolean.FALSE,loaded.data().headerTax().taxFree());
            assertEquals(0,new BigDecimal("25").compareTo(loaded.data().headerTax().rate1()));
            var before=snapshot(f);var preserve=CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(),Set.of(),taxColumns());f.anchor.rollback();
            var tax=new TaxValues(false,new BigDecimal("30"),new BigDecimal("15"),new BigDecimal("7"));
            var preview=editor.preview(loaded,patch(loaded,tax));
            assertEquals(0,new BigDecimal("130").compareTo(preview.after()));assertEquals(before,snapshot(f));f.anchor.rollback();
            var saved=editor.save(preview);assertSame(saved,editor.save(preview));
            assertEquals(preserve,CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(),Set.of(),taxColumns()));f.anchor.rollback();
            var reopened=CustomerFinancialAccountingTest.editor(f,kind,id).load();
            assertEquals(0,new BigDecimal("30").compareTo(reopened.data().headerTax().rate1()));
            assertEquals(0,new BigDecimal("130").compareTo(reopened.data().total()));
            assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
            var current=editor.load();
            var exempt=editor.preview(current,patch(current,new TaxValues(true,tax.rate1(),tax.rate2(),tax.rate3())));
            assertEquals(0,new BigDecimal("100").compareTo(exempt.after()));editor.save(exempt);
            assertTrue(editor.load().data().taxFree());
        }
    }

    @Test
    void changedRatesRejectBoundsAndPrecisionWithoutWritesTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            var before=snapshot(f);f.anchor.rollback();
            for(var tax:List.of(new TaxValues(false,new BigDecimal("-0.01"),null,null),
                    new TaxValues(false,new BigDecimal("100.0001"),null,null)))
                assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,tax)));
            assertThrows(IllegalArgumentException.class,()->new TaxValues(false,new BigDecimal("1e-31"),null,null));
            assertThrows(IllegalArgumentException.class,()->new TaxValues(false,new BigDecimal("1e70"),null,null));
            for(var valid:List.of(BigDecimal.ZERO,new BigDecimal("100")))
                assertNotNull(editor.preview(loaded,patch(loaded,new TaxValues(false,valid,null,null))));
            assertEquals(before,snapshot(f));
        }
    }

    @Test
    void rawNullsAndUnchangedHistoricalValuesSurviveWithoutDefaultRepairTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+" SET tax_rate_1=NULL,tax_rate_2=-1,tax_rate_3=101 WHERE id="+id);}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            assertFalse(loaded.data().headerTax().taxFree());assertNull(loaded.data().headerTax().rate1());
            var before=snapshot(f);f.anchor.rollback();
            editor.save(editor.preview(loaded,patch(loaded,loaded.data().headerTax())));
            assertEquals(before,snapshot(f));f.anchor.rollback();
            var fresh=editor.load();var old=fresh.data().headerTax();
            var exempt=editor.preview(fresh,patch(fresh,new TaxValues(true,old.rate1(),old.rate2(),old.rate3())));
            editor.save(exempt);
            var next=editor.load();assertNull(next.data().headerTax().rate1());
            assertEquals(0,new BigDecimal("-1").compareTo(next.data().headerTax().rate2()));
            assertThrows(IllegalArgumentException.class,()->editor.preview(next,patch(next,new TaxValues(false,null,old.rate2(),old.rate3()))));
            editor.save(editor.preview(next,patch(next,new TaxValues(false,new BigDecimal("12"),old.rate2(),old.rate3()))));
            assertEquals(0,new BigDecimal("112").compareTo(editor.load().data().total()));
        }
    }

    @Test
    void simultaneousCodeAndHeaderChangesUseProposedRatesAndRefuseMissingUsedRatesTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=CustomerFinancialAccountingTest.target(f,Kind.INVOICE);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET tax_rate_1=NULL WHERE id="+id);}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            var old=loaded.data().headerTax();
            var coupled=new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),
                    List.of(new LineAccounting(19,3020,"TAXRATE_1")),new TaxValues(false,new BigDecimal("20"),old.rate2(),old.rate3()));
            assertEquals(0,new BigDecimal("120").compareTo(editor.save(editor.preview(loaded,coupled)).total()));
            var fresh=editor.load();
            assertThrows(IllegalArgumentException.class,()->editor.preview(fresh,patch(fresh,new TaxValues(false,null,null,null))));
            // An unused slot may be cleared without synthesizing a rate or changing the used one.
            editor.save(editor.preview(fresh,patch(fresh,new TaxValues(false,new BigDecimal("20"),null,null))));
            var cleared=editor.load();assertNull(cleared.data().headerTax().rate2());
            editor.save(editor.preview(cleared,patch(cleared,new TaxValues(true,null,null,null))));
            var exempt=editor.load();assertEquals(0,new BigDecimal("100").compareTo(exempt.data().total()));
            assertThrows(IllegalArgumentException.class,()->editor.preview(exempt,patch(exempt,new TaxValues(false,null,null,null))));
        }
    }

    @Test
    void changedStoredTaxSourceRejectsPreviewAndSaveUntilReloadTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            var values=patch(loaded,new TaxValues(false,new BigDecimal("30"),null,null));var preview=editor.preview(loaded,values);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET tax_rate_1=26 WHERE id="+id);}f.anchor.commit();
            assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,values));
            assertThrows(IllegalArgumentException.class,()->editor.save(preview));assertFalse(editor.isBlocked());
            assertEquals(0,new BigDecimal("26").compareTo(editor.load().data().headerTax().rate1()));
        }
    }

    @Test
    void lateRowFailureRestoresHeaderTaxDatesAndEarlierRowsThenRetriesTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=CustomerFinancialAccountingTest.document(f,kind,id);
            var extra=new SSSaleRow();extra.setQuantity(1);extra.setUnitprice(BigDecimal.TEN);extra.setTaxCode(SSTaxCode.TAXRATE_0);extra.setAccountNr(3010);source.getRows().add(extra);
            if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row ADD CONSTRAINT tax_failure CHECK(unit_price<250)");}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            var values=new FinancialValues(LocalDate.of(2026,11,13),null,List.of(new LineAmounts(0,new BigDecimal("2"),new BigDecimal("200")),new LineAmounts(1,BigDecimal.ONE,new BigDecimal("300"))),
                    List.of(new LineDetails(0,"new draft description",BigDecimal.TEN)),List.of(new LineAccounting(0,3020,"TAXRATE_1")),new TaxValues(false,new BigDecimal("30"),null,null));
            var preview=editor.preview(loaded,values);var before=snapshot(f);f.anchor.rollback();
            assertThrows(SQLException.class,()->editor.save(preview));assertFalse(editor.isBlocked());assertEquals(before,snapshot(f));f.anchor.rollback();
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row DROP CONSTRAINT tax_failure");}f.anchor.commit();
            assertEquals(0,preview.after().compareTo(editor.save(preview).total()));
        }
    }

    @Test
    void localSavepointRetainsOuterWorkAndGuardsBookedOrForeignHeaderTargetsTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var loaded=CustomerFinancialAccountingTest.editor(f,kind,id).load();
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+" SET ocr_number='outer work' WHERE id="+id);}
            var before=snapshot(f);var store=new NormalizedInvoiceStore(f.anchor);
            var patch=new FinancialValues(loaded.data().values().date(),null,List.of(new LineAmounts(999,BigDecimal.ONE,BigDecimal.TEN)),List.of(),List.of(),new TaxValues(true,null,null,null));
            assertThrows(SQLException.class,()->update(store,kind,71,id,patch));assertEquals(before,snapshot(f));
            assertThrows(SQLException.class,()->update(store,kind,999,id,patch));assertEquals(before,snapshot(f));
            if(kind==Kind.INVOICE)assertThrows(SQLException.class,()->update(store,kind,71,f.invoice,patch));
            f.anchor.rollback();
        }
    }

    @Test
    void unknownHeaderCommitBlocksBlindRetryRegardlessOfDurabilityTest() throws Exception {
        for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);
            var editor=new CustomerFinancialEditAdapter(()->{
                var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown header commit");}
                    try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            },f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,new TaxValues(false,new BigDecimal("30"),null,null)));
            assertThrows(SQLException.class,()->editor.save(preview));assertTrue(editor.isBlocked());assertThrows(SQLException.class,editor::load);assertThrows(SQLException.class,()->editor.save(preview));
            var reopened=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id).load();
            assertEquals(0,new BigDecimal(durable?"30":"25").compareTo(reopened.data().headerTax().rate1()));
        }
    }

    @Test
    void separateInvoiceAndCreditJournalsUseEditedHeaderRatesWithoutRewritingOldVoucherTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            editor.save(editor.preview(loaded,patch(loaded,new TaxValues(false,new BigDecimal("30"),null,null))));
            assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());f.anchor.rollback();
            var journal=new CustomerJournalAdapter(f::open,f.gate,71,11,kind==Kind.INVOICE?CustomerJournalAdapter.Kind.INVOICE:CustomerJournalAdapter.Kind.CREDIT,CustomerCreditGuiFixture.CLOCK);
            var preview=journal.preview(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
            assertEquals(0,new BigDecimal("130").compareTo(preview.summary().debit()));assertEquals(0,new BigDecimal("130").compareTo(preview.summary().credit()));
            assertTrue(preview.summary().rows().stream().anyMatch(r->Integer.valueOf(2611).equals(r.account())
                    &&((r.debit()!=null&&r.debit().compareTo(new BigDecimal("30"))==0)||(r.credit()!=null&&r.credit().compareTo(new BigDecimal("30"))==0))));
            journal.post(preview);assertThrows(SQLException.class,editor::load);
        }
    }

    static long target(CustomerCreditGuiFixture f,Kind kind)throws Exception {
        long id=CustomerFinancialAccountingTest.target(f,kind);
        try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+"_row SET tax_code='TAXRATE_1' WHERE "+owner(kind)+"="+id);}f.anchor.commit();return id;
    }
    static FinancialValues patch(CustomerFinancialEditAdapter.Loaded loaded,TaxValues tax){return new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),List.of(),tax);}
    private static Set<String> taxColumns(){return Set.of("TAX_FREE","TAX_RATE_1","TAX_RATE_2","TAX_RATE_3");}
    private static java.util.Map<String,List<String>> snapshot(CustomerCreditGuiFixture f)throws Exception{return CustomerFinancialEditAdapterTest.snapshot(f,null,-1,Set.of());}
    private static void update(NormalizedInvoiceStore store,Kind kind,int company,long id,FinancialValues patch)throws Exception{if(kind==Kind.INVOICE)store.updateInvoiceFinancialValues(company,id,patch);else store.updateCreditFinancialValues(company,id,patch);}
    private static String table(Kind kind){return kind==Kind.INVOICE?"customer_invoice":"customer_credit_invoice";}
    private static String owner(Kind kind){return kind==Kind.INVOICE?"invoice_id":"credit_invoice_id";}
}
