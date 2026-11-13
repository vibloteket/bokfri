package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAccounting;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineDetails;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
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

class CustomerFinancialAccountingTest {
    @Test
    void bothKindsUseStoredRatesAndNarrowAccountingUpdatesWithReadOnlyPreviewTest() throws Exception {
        for (var kind : Kind.values()) try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, kind);
            var editor = editor(f, kind, id);
            var loaded = editor.load();
            assertEquals(11, loaded.data().yearId());
            assertTrue(loaded.data().accounts().stream().anyMatch(a -> a.number() == 3020));
            assertEquals(new BigDecimal("25.000000000000000000000000000000"), loaded.data().taxes().get(1).rate());
            assertThrows(UnsupportedOperationException.class, () -> loaded.data().accounts().clear());
            assertThrows(UnsupportedOperationException.class, () -> loaded.data().taxes().clear());
            var before = snapshot(f);
            var preserve = CustomerFinancialEditAdapterTest.snapshot(f, kind, id, Set.of(19), Set.of("ACCOUNT_NUMBER", "TAX_CODE"));
            f.anchor.rollback();
            var patch = patch(loaded, List.of(new LineAccounting(19, 3020, "TAXRATE_1")));
            var preview = editor.preview(loaded, patch);
            assertEquals(0, new BigDecimal("125").compareTo(preview.after()));
            assertEquals(before, snapshot(f)); f.anchor.rollback();
            var saved = editor.save(preview);
            assertSame(saved, editor.save(preview));
            assertEquals(preserve, CustomerFinancialEditAdapterTest.snapshot(f, kind, id, Set.of(19), Set.of("ACCOUNT_NUMBER", "TAX_CODE")));
            f.anchor.rollback();
            var reopened = editor(f, kind, id).load();
            assertEquals(loaded.data().values(), reopened.data().values());
            assertEquals(3020, reopened.data().lines().getFirst().account());
            assertEquals("TAXRATE_1", reopened.data().lines().getFirst().taxCode());
            assertEquals(0, new BigDecimal("125").compareTo(reopened.data().total()));
            assertEquals(777, document(f, kind, id).getStoredVoucher().getNumber());
        }
    }

    @Test
    void invalidForeignInactiveRequiredDimensionAndDuplicateInputsFailWithoutWritesTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            addAccount(f, 11, 3021, false, false, false);
            addAccount(f, 11, 3022, true, true, false);
            addAccount(f, 11, 3023, true, false, true);
            var year = new SSNewAccountingYear(); year.setId(12);
            year.setLocalFrom(LocalDate.of(2027,1,1)); year.setLocalTo(LocalDate.of(2027,12,31));
            var plan = new SSAccountPlan(); plan.setAccounts(new java.util.ArrayList<>(List.of(new SSAccount(3030))));
            year.setAccountPlan(plan);
            new NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK).addAccountingYear(f.anchor, 71, year);
            f.anchor.commit();
            var editor = editor(f, Kind.INVOICE, id); var loaded = editor.load();
            assertFalse(loaded.data().accounts().stream().anyMatch(a -> a.number() == 3030));
            var before = snapshot(f); f.anchor.rollback();
            for (int bad : List.of(3999, 3030, 1510, 3021, 3022, 3023))
                assertThrows(IllegalArgumentException.class, () -> editor.preview(loaded, patch(loaded, List.of(new LineAccounting(19,bad,"TAXRATE_1")))));
            assertThrows(IllegalArgumentException.class, () -> new LineAccounting(19,3020,"25 %"));
            assertThrows(IllegalArgumentException.class, () -> editor.preview(loaded, patch(loaded, List.of(new LineAccounting(999,3020,"TAXRATE_1")))));
            assertThrows(IllegalArgumentException.class, () -> editor.preview(loaded, patch(loaded, List.of(new LineAccounting(19,3020,null),new LineAccounting(19,3010,null)))));
            assertThrows(SQLException.class, () -> new CustomerFinancialEditAdapter(f::open,f.gate,71,999,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK).load());
            assertEquals(before,snapshot(f));
        }
    }

    @Test
    void absentRatesAreNotInventedAndHistoricalValuesCanBeRetainedOrExplicitlyClearedTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f,Kind.INVOICE);
            try(var sql=f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE customer_invoice SET tax_rate_1=NULL,tax_rate_2=-1,tax_rate_3=101 WHERE id="+id);
                sql.executeUpdate("UPDATE customer_invoice_row SET account_number=9999,tax_code=NULL WHERE invoice_id="+id);
            }
            f.anchor.commit();
            var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();
            assertNull(loaded.data().taxes().get(1).rate());
            for(String code:List.of("TAXRATE_1","TAXRATE_2","TAXRATE_3"))
                assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,List.of(new LineAccounting(19,9999,code)))));
            editor.save(editor.preview(loaded,patch(loaded,List.of(new LineAccounting(19,9999,null)))));
            var fresh=editor.load();
            editor.save(editor.preview(fresh,patch(fresh,List.of(new LineAccounting(19,null,"TAXRATE_0")))));
            var cleared=editor.load();assertNull(cleared.data().lines().getFirst().account());
            assertEquals("TAXRATE_0",cleared.data().lines().getFirst().taxCode());
            assertNull(cleared.data().taxes().get(1).rate());
            assertEquals(0,new BigDecimal("100").compareTo(cleared.data().total()));
        }
    }

    @Test
    void changedAccountPlanFlagsAndStoredRatesInvalidatePreviouslyConfirmedInputsTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=editor(f,Kind.INVOICE,id);
            for(String change:List.of(
                    "UPDATE account SET description='changed' WHERE number=3020",
                    "UPDATE account SET active=FALSE WHERE number=3020",
                    "UPDATE account SET project_required=TRUE WHERE number=3020",
                    "UPDATE customer_invoice SET tax_rate_1=26 WHERE id="+id)) {
                // Reset account flags before the next independent stale-preview case.
                try(var sql=f.anchor.createStatement()) {sql.executeUpdate("UPDATE account SET active=TRUE,project_required=FALSE WHERE number=3020");}
                f.anchor.commit();
                var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,List.of(new LineAccounting(19,3020,"TAXRATE_1"))));
                try(var sql=f.anchor.createStatement()) {sql.executeUpdate(change);}f.anchor.commit();
                assertThrows(IllegalArgumentException.class,()->editor.save(preview));assertFalse(editor.isBlocked());
            }
        }
    }

    @Test
    void lateAccountingFailureRollsBackEarlierDatesAmountsDetailsAndFirstRowThenRetriesTest() throws Exception {
        for(var kind:Kind.values()) try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=document(f,kind,id);
            var extra=new SSSaleRow();extra.setQuantity(1);extra.setUnitprice(BigDecimal.TEN);extra.setAccountNr(3010);extra.setTaxCode(SSTaxCode.TAXRATE_0);
            source.getRows().add(extra);
            if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
            try(var sql=f.anchor.createStatement()) {sql.executeUpdate("ALTER TABLE "+table(kind)+"_row ADD CONSTRAINT accounting_failure CHECK(tax_code IS NULL OR tax_code<>'TAXRATE_2')");}
            f.anchor.commit();
            var editor=editor(f,kind,id);var loaded=editor.load();
            var values=new FinancialValues(LocalDate.of(2026,11,13),null,
                    List.of(new LineAmounts(0,new BigDecimal("2"),new BigDecimal("50")),new LineAmounts(1,BigDecimal.ONE,BigDecimal.TEN)),
                    List.of(new LineDetails(0,"changed description",BigDecimal.TEN)),
                    List.of(new LineAccounting(0,3020,"TAXRATE_1"),new LineAccounting(1,3020,"TAXRATE_2")));
            var preview=editor.preview(loaded,values);var before=snapshot(f);f.anchor.rollback();
            assertThrows(SQLException.class,()->editor.save(preview));assertFalse(editor.isBlocked());
            assertEquals(before,snapshot(f));f.anchor.rollback();
            try(var sql=f.anchor.createStatement()) {sql.executeUpdate("ALTER TABLE "+table(kind)+"_row DROP CONSTRAINT accounting_failure");}f.anchor.commit();
            assertEquals(0,preview.after().compareTo(editor.save(preview).total()));
        }
    }

    @Test
    void localStoreSavepointPreservesOuterWorkOnDuplicateOrForeignAccountingRowsTest() throws Exception {
        for(var kind:Kind.values()) try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var loaded=editor(f,kind,id).load();
            try(var sql=f.anchor.createStatement()) {sql.executeUpdate("UPDATE "+table(kind)+" SET ocr_number='outer work' WHERE id="+id);}
            var before=snapshot(f);var store=new NormalizedInvoiceStore(f.anchor);
            for(var list:List.of(List.of(new LineAccounting(19,3020,"TAXRATE_1"),new LineAccounting(19,null,null)),
                    List.of(new LineAccounting(19,3020,"TAXRATE_1"),new LineAccounting(999,null,null)))) {
                var values=patch(loaded,list);
                if(list.get(1).rowNumber()==19)
                    assertThrows(IllegalArgumentException.class,()->update(store,kind,id,values));
                else assertThrows(SQLException.class,()->update(store,kind,id,values));
                assertEquals(before,snapshot(f));
            }
            f.anchor.rollback();
        }
    }

    @Test
    void unknownAccountingCommitBlocksBlindRetryBeforeAndAfterDurabilityTest() throws Exception {
        for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);
            var editor=new CustomerFinancialEditAdapter(()->{
                var c=f.open();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown accounting commit");}
                    try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            },f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,List.of(new LineAccounting(19,3020,"TAXRATE_1"))));
            assertThrows(SQLException.class,()->editor.save(preview));assertTrue(editor.isBlocked());
            assertThrows(SQLException.class,()->editor.save(preview));assertThrows(SQLException.class,editor::load);
            var reopened=editor(f,Kind.INVOICE,id).load();
            assertEquals(durable?3020:3010,reopened.data().lines().getFirst().account());
            assertEquals(durable?"TAXRATE_1":"TAXRATE_0",reopened.data().lines().getFirst().taxCode());
        }
    }

    @Test
    void separateInvoiceAndCreditJournalsUseNewAccountAndStoredDocumentVatTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=editor(f,kind,id);var loaded=editor.load();
            editor.save(editor.preview(loaded,patch(loaded,List.of(new LineAccounting(19,3020,"TAXRATE_1")))));
            assertEquals(777,document(f,kind,id).getStoredVoucher().getNumber());f.anchor.rollback();
            var journal=new CustomerJournalAdapter(f::open,f.gate,71,11,kind==Kind.INVOICE?CustomerJournalAdapter.Kind.INVOICE:CustomerJournalAdapter.Kind.CREDIT,CustomerCreditGuiFixture.CLOCK);
            var preview=journal.preview(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
            assertEquals(0,new BigDecimal("125").compareTo(preview.summary().debit()));
            assertEquals(0,new BigDecimal("125").compareTo(preview.summary().credit()));
            assertTrue(preview.summary().rows().stream().anyMatch(r->Integer.valueOf(3020).equals(r.account())));
            assertTrue(preview.summary().rows().stream().anyMatch(r->Integer.valueOf(2611).equals(r.account())));
            journal.post(preview);assertThrows(SQLException.class,editor::load);
        }
    }

    static long target(CustomerCreditGuiFixture f,Kind kind)throws Exception {
        long id=CustomerFinancialEditAdapterTest.target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=document(f,kind,id);
        source.setTaxFree(false);source.setStoredTaxRates(new BigDecimal("25"),new BigDecimal("12"),new BigDecimal("6"));
        source.getDefaultAccounts().put(SSDefaultAccount.Tax1,2611);source.getDefaultAccounts().put(SSDefaultAccount.Tax2,2621);source.getDefaultAccounts().put(SSDefaultAccount.Tax3,2631);
        source.setVoucher(new SSVoucher(777,source.getLocalDate()));
        var row=source.getRows().getFirst();row.setQuantity(BigDecimal.ONE);row.setUnitprice(new BigDecimal("100"));row.setDiscount(null);row.setAccountNr(3010);row.setTaxCode(SSTaxCode.TAXRATE_0);
        if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
        try(var sql=f.anchor.createStatement()) {
            sql.executeUpdate("UPDATE "+table(kind)+"_row SET row_number=19 WHERE "+owner(kind)+"="+id);
            // Deliberately different company defaults must not replace the document's stored rates.
            sql.executeUpdate("UPDATE company SET tax_rate_1=99,tax_rate_2=98,tax_rate_3=97 WHERE legacy_id=71");
        }
        for(int account:List.of(3020,2611,2621,2631))addAccount(f,11,account,true,false,false);
        f.anchor.commit();return id;
    }
    static void addAccount(CustomerCreditGuiFixture f,int year,int number,boolean active,boolean project,boolean unit)throws Exception {
        long generated;
        try(var sql=f.anchor.createStatement();var row=sql.executeQuery("SELECT id FROM accounting_year WHERE legacy_id="+year)){row.next();generated=row.getLong(1);}
        try(var sql=f.anchor.prepareStatement("SELECT COUNT(*) FROM account WHERE accounting_year_id=? AND number=?")){
            sql.setLong(1,generated);sql.setInt(2,number);try(var row=sql.executeQuery()){row.next();if(row.getInt(1)>0)return;}
        }
        var account=new SSAccount(number);account.setDescription("Account "+number);account.setActive(active);account.setProjectRequired(project);account.setResultUnitRequired(unit);
        new NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK).addAccount(f.anchor,generated,account);
    }
    static CustomerFinancialEditAdapter editor(CustomerCreditGuiFixture f,Kind kind,long id){return new CustomerFinancialEditAdapter(f::open,f.gate,71,11,kind,id,CustomerCreditGuiFixture.CLOCK);}
    static FinancialValues patch(CustomerFinancialEditAdapter.Loaded loaded,List<LineAccounting> accounting){return new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),accounting);}
    static SSInvoice document(CustomerCreditGuiFixture f,Kind kind,long id)throws Exception {var store=new NormalizedInvoiceStore(f.anchor);return kind==Kind.INVOICE?store.findInvoice(71,id).orElseThrow().document():store.findCreditInvoice(71,id).orElseThrow().document();}
    private static String table(Kind kind){return kind==Kind.INVOICE?"customer_invoice":"customer_credit_invoice";}
    private static String owner(Kind kind){return kind==Kind.INVOICE?"invoice_id":"credit_invoice_id";}
    private static java.util.Map<String,List<String>> snapshot(CustomerCreditGuiFixture f)throws Exception{return CustomerFinancialEditAdapterTest.snapshot(f,null,-1,Set.of());}
    private static void update(NormalizedInvoiceStore store,Kind kind,long id,FinancialValues patch)throws Exception{if(kind==Kind.INVOICE)store.updateInvoiceFinancialValues(71,id,patch);else store.updateCreditFinancialValues(71,id,patch);}
}
