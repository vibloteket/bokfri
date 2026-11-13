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

class CustomerFinancialExchangeTest {
    @Test
    void bothKindsPreviewStoredRateAndNarrowSavePreserveCodesReferencesAndAllOtherSqlTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);if(kind==Kind.INVOICE){CustomerDocumentDeletionAdapterTest.order(f,17,5);f.anchor.commit();}
            var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            assertEquals("EUR",loaded.data().currency());assertEquals("SEK",loaded.data().baseCurrency());
            assertEquals(0,new BigDecimal("1250").compareTo(loaded.data().accountingEquivalent()));
            var before=snapshot(f);var preserve=CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(),Set.of(),Set.of("CURRENCY_RATE"));f.anchor.rollback();
            var preview=editor.preview(loaded,patch(loaded,new BigDecimal("2")));
            assertEquals(0,new BigDecimal("125").compareTo(preview.before()));assertEquals(0,preview.before().compareTo(preview.after()));
            assertEquals(0,new BigDecimal("1250").compareTo(preview.beforeAccounting()));assertEquals(0,new BigDecimal("250").compareTo(preview.afterAccounting()));
            assertEquals(before,snapshot(f));f.anchor.rollback();
            var saved=editor.save(preview);assertSame(saved,editor.save(preview));
            assertEquals(preserve,CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(),Set.of(),Set.of("CURRENCY_RATE")));f.anchor.rollback();
            var fresh=CustomerFinancialAccountingTest.editor(f,kind,id).load();
            assertEquals("EUR",fresh.data().currency());assertEquals("SEK",fresh.data().baseCurrency());
            assertEquals(0,new BigDecimal("2").compareTo(fresh.data().exchangeRate()));assertEquals(0,new BigDecimal("250").compareTo(fresh.data().accountingEquivalent()));
            assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
        }
    }

    @Test
    void changedRatesRequirePositiveExactValuesAndPreviewDoesNotPretendToBeRoundedJournalTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            var before=snapshot(f);f.anchor.rollback();
            for(var rate:java.util.Arrays.asList(null,BigDecimal.ZERO,new BigDecimal("-1")))
                assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,rate)));
            assertThrows(IllegalArgumentException.class,()->new ExchangeRateValues(new BigDecimal("1e-31")));
            assertThrows(IllegalArgumentException.class,()->new ExchangeRateValues(new BigDecimal("1e70")));
            var preview=editor.preview(loaded,patch(loaded,new BigDecimal("1.234567")));
            assertEquals(0,new BigDecimal("154.320875").compareTo(preview.afterAccounting()));
            assertNotNull(editor.preview(loaded,patch(loaded,new BigDecimal("1e-30"))));
            assertEquals(before,snapshot(f));
        }
    }

    @Test
    void missingContextAndHistoricalRatesArePreservedWithoutDefaultsButCannotBeNewlyIntroducedTest() throws Exception {
        for(var old:java.util.Arrays.asList(null,BigDecimal.ZERO,new BigDecimal("-1")))try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);
            try(var sql=f.anchor.prepareStatement("UPDATE customer_invoice SET currency_rate=? WHERE id=?")){sql.setBigDecimal(1,old);sql.setLong(2,id);sql.executeUpdate();}
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE company SET currency_code=NULL WHERE legacy_id=71");}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            assertNull(loaded.data().accountingEquivalent());var before=snapshot(f);f.anchor.rollback();
            editor.save(editor.preview(loaded,patch(loaded,old)));assertEquals(before,snapshot(f));f.anchor.rollback();
            var current=editor.load();assertThrows(IllegalArgumentException.class,()->editor.preview(current,patch(current,new BigDecimal("2"))));
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE company SET currency_code='SEK' WHERE legacy_id=71");}f.anchor.commit();
            var fixed=editor.load();var preview=editor.preview(fixed,patch(fixed,new BigDecimal("2")));
            assertEquals(0,new BigDecimal("250").compareTo(editor.save(preview).total().multiply(new BigDecimal("2"))));
        }
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET currency_code=NULL WHERE id="+id);}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            assertNull(loaded.data().currency());assertNull(loaded.data().accountingEquivalent());
            assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,new BigDecimal("2"))));
            editor.save(editor.preview(loaded,patch(loaded,loaded.data().exchangeRate())));
            assertNull(editor.load().data().currency());
        }
    }

    @Test
    void rateAndCompanyBaseChangesAreStaleButCatalogueDefaultsAreNotUsedAndSameCurrencyRequiresOneTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);var loaded=editor.load();
            var preview=editor.preview(loaded,patch(loaded,new BigDecimal("2")));
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE currency SET exchange_rate=777 WHERE code='EUR'");}f.anchor.commit();
            assertEquals(0,new BigDecimal("125").compareTo(editor.save(preview).total()));
            var next=editor.load();var staleRate=editor.preview(next,patch(next,new BigDecimal("3")));
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET currency_rate=11 WHERE id="+id);}f.anchor.commit();
            assertThrows(IllegalArgumentException.class,()->editor.save(staleRate));
            var current=editor.load();var staleBase=editor.preview(current,patch(current,new BigDecimal("3")));
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE company SET currency_code='USD' WHERE legacy_id=71");}f.anchor.commit();
            assertThrows(IllegalArgumentException.class,()->editor.save(staleBase));
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE company SET currency_code='EUR' WHERE legacy_id=71");}f.anchor.commit();
            var same=editor.load();assertThrows(IllegalArgumentException.class,()->editor.preview(same,patch(same,new BigDecimal("2"))));
            editor.save(editor.preview(same,patch(same,BigDecimal.ONE)));
            assertEquals(0,new BigDecimal("125").compareTo(editor.load().data().accountingEquivalent()));
        }
    }

    @Test
    void lateRowFailureRollsBackExchangeTaxDatesAndEarlierRowsThenRetriesTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=CustomerFinancialAccountingTest.document(f,kind,id);
            var extra=new SSSaleRow();extra.setQuantity(1);extra.setUnitprice(BigDecimal.TEN);extra.setTaxCode(SSTaxCode.TAXRATE_0);extra.setAccountNr(3010);source.getRows().add(extra);
            if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row ADD CONSTRAINT exchange_failure CHECK(unit_price<250)");}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            var values=new FinancialValues(LocalDate.of(2026,11,13),null,List.of(new LineAmounts(0,new BigDecimal("2"),new BigDecimal("200")),new LineAmounts(1,BigDecimal.ONE,new BigDecimal("300"))),
                    List.of(new LineDetails(0,"new draft description",BigDecimal.TEN)),List.of(new LineAccounting(0,3020,"TAXRATE_1")),new TaxValues(false,new BigDecimal("30"),null,null),new ExchangeRateValues(new BigDecimal("2")));
            var preview=editor.preview(loaded,values);var before=snapshot(f);f.anchor.rollback();
            assertThrows(SQLException.class,()->editor.save(preview));assertFalse(editor.isBlocked());assertEquals(before,snapshot(f));f.anchor.rollback();
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row DROP CONSTRAINT exchange_failure");}f.anchor.commit();
            assertEquals(0,preview.after().compareTo(editor.save(preview).total()));assertEquals(0,preview.afterAccounting().compareTo(editor.load().data().accountingEquivalent()));
        }
    }

    @Test
    void localSavepointRetainsOuterWorkAndRejectsForeignOrBookedExchangeTargetsTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var loaded=CustomerFinancialAccountingTest.editor(f,kind,id).load();
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+" SET ocr_number='outer work' WHERE id="+id);}
            var before=snapshot(f);var store=new NormalizedInvoiceStore(f.anchor);
            var patch=new FinancialValues(loaded.data().values().date(),null,List.of(new LineAmounts(999,BigDecimal.ONE,BigDecimal.TEN)),List.of(),List.of(),null,new ExchangeRateValues(new BigDecimal("2")));
            assertThrows(SQLException.class,()->update(store,kind,71,id,patch));assertEquals(before,snapshot(f));
            assertThrows(SQLException.class,()->update(store,kind,999,id,patch));assertEquals(before,snapshot(f));
            if(kind==Kind.INVOICE)assertThrows(SQLException.class,()->update(store,kind,71,f.invoice,patch));f.anchor.rollback();
        }
    }

    @Test
    void unknownExchangeCommitBlocksBlindRetryBeforeAndAfterDurabilityTest() throws Exception {
        for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);
            var editor=new CustomerFinancialEditAdapter(()->{
                var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown exchange commit");}
                    try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            },f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,new BigDecimal("2")));
            assertThrows(SQLException.class,()->editor.save(preview));assertTrue(editor.isBlocked());assertThrows(SQLException.class,editor::load);assertThrows(SQLException.class,()->editor.save(preview));
            assertEquals(0,new BigDecimal(durable?"2":"10").compareTo(CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id).load().data().exchangeRate()));
        }
    }

    @Test
    void separateInvoiceAndCreditJournalsUseStoredRateNotCatalogueDefaultsTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=CustomerFinancialAccountingTest.editor(f,kind,id);var loaded=editor.load();
            editor.save(editor.preview(loaded,patch(loaded,new BigDecimal("2"))));
            assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());f.anchor.rollback();
            var journal=new CustomerJournalAdapter(f::open,f.gate,71,11,kind==Kind.INVOICE?CustomerJournalAdapter.Kind.INVOICE:CustomerJournalAdapter.Kind.CREDIT,CustomerCreditGuiFixture.CLOCK);
            var preview=journal.preview(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
            assertEquals(0,new BigDecimal("250").compareTo(preview.summary().debit()));assertEquals(0,new BigDecimal("250").compareTo(preview.summary().credit()));
            journal.post(preview);assertThrows(SQLException.class,editor::load);
        }
    }

    static long target(CustomerCreditGuiFixture f,Kind kind)throws Exception {
        long id=CustomerFinancialHeaderTaxTest.target(f,kind);
        for(String code:List.of("EUR","SEK","USD")) {
            try(var sql=f.anchor.prepareStatement("SELECT COUNT(*) FROM currency WHERE code=?")){sql.setString(1,code);try(var row=sql.executeQuery()){row.next();if(row.getInt(1)==0)try(var insert=f.anchor.prepareStatement("INSERT INTO currency(code,description,exchange_rate) VALUES(?,?,?)")){insert.setString(1,code);insert.setString(2,code);insert.setBigDecimal(3,new BigDecimal("99"));insert.executeUpdate();}}}
        }
        try(var sql=f.anchor.createStatement()) {
            sql.executeUpdate("UPDATE company SET currency_code='SEK' WHERE legacy_id=71");
            sql.executeUpdate("UPDATE "+table(kind)+" SET currency_code='EUR',currency_rate=10 WHERE id="+id);
        }
        f.anchor.commit();return id;
    }
    static FinancialValues patch(CustomerFinancialEditAdapter.Loaded loaded,BigDecimal rate){return new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),List.of(),null,new ExchangeRateValues(rate));}
    private static java.util.Map<String,List<String>> snapshot(CustomerCreditGuiFixture f)throws Exception{return CustomerFinancialEditAdapterTest.snapshot(f,null,-1,Set.of());}
    private static void update(NormalizedInvoiceStore store,Kind kind,int company,long id,FinancialValues patch)throws Exception{if(kind==Kind.INVOICE)store.updateInvoiceFinancialValues(company,id,patch);else store.updateCreditFinancialValues(company,id,patch);}
    private static String table(Kind kind){return kind==Kind.INVOICE?"customer_invoice":"customer_credit_invoice";}
}
