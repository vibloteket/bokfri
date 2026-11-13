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

class CustomerFinancialDimensionsTest {
    @Test void bothKindsAllowRequiredAccountWithExplicitDimensionsAndPreserveAllOtherSqlTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=editor(f,kind,id);var loaded=editor.load();
            assertTrue(loaded.data().projects().stream().anyMatch(p->p.number().equals("P-Å")&&!p.concluded()));
            assertThrows(UnsupportedOperationException.class,()->loaded.data().projects().clear());
            var before=snapshot(f);var preserve=CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(19),Set.of("ACCOUNT_NUMBER","PROJECT_NUMBER","RESULT_UNIT_NUMBER"),Set.of());f.anchor.rollback();
            var preview=editor.preview(loaded,patch(loaded,3022,List.of(new LineDimensions(19,"P-Å","U-1"))));
            assertEquals(0,new BigDecimal("125").compareTo(preview.after()));assertEquals(before,snapshot(f));f.anchor.rollback();
            var saved=editor.save(preview);assertSame(saved,editor.save(preview));
            assertEquals(preserve,CustomerFinancialEditAdapterTest.snapshot(f,kind,id,Set.of(19),Set.of("ACCOUNT_NUMBER","PROJECT_NUMBER","RESULT_UNIT_NUMBER"),Set.of()));f.anchor.rollback();
            var fresh=editor(f,kind,id).load().data().lines().getFirst();assertEquals(3022,fresh.account());assertEquals("P-Å",fresh.project());assertEquals("U-1",fresh.resultUnit());
            assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
        }
    }
    @Test void foreignClosedUnknownBlankAndDuplicateDimensionAssignmentsFailWithoutWritesTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var company=new SSNewCompany();company.setId(72);company.setName("Foreign");new NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK).addCompany(f.anchor,company);
            seed(f,72,"P-X","U-X",false);f.anchor.commit();var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();var before=snapshot(f);f.anchor.rollback();
            assertFalse(loaded.data().projects().stream().anyMatch(p->p.number().equals("P-X")));
            for(var dims:List.of(List.of(new LineDimensions(19,"P-X","U-1")),List.of(new LineDimensions(19,"P-closed","U-1")),List.of(new LineDimensions(19,"missing","U-1")),List.of(new LineDimensions(19,"","U-1")),List.of(new LineDimensions(19,"P-Å","U-X")),List.of(new LineDimensions(999,"P-Å","U-1")),List.of(new LineDimensions(19,"P-Å","U-1"),new LineDimensions(19,null,null))))
                assertThrows(IllegalArgumentException.class,()->editor.preview(loaded,patch(loaded,null,dims)));
            assertEquals(before,snapshot(f));
        }
    }
    @Test void requiredDimensionsCannotBeClearedUnlessAccountIsChangedInTheSamePreviewTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();editor.save(editor.preview(loaded,patch(loaded,3022,List.of(new LineDimensions(19,"P-Å","U-1")))));
            var fresh=editor.load();
            for(var dims:List.of(new LineDimensions(19,null,"U-1"),new LineDimensions(19,"P-Å",null)))assertThrows(IllegalArgumentException.class,()->editor.preview(fresh,patch(fresh,null,List.of(dims))));
            editor.save(editor.preview(fresh,patch(fresh,3010,List.of(new LineDimensions(19,null,null)))));
            var clear=editor.load().data().lines().getFirst();assertEquals(3010,clear.account());assertNull(clear.project());assertNull(clear.resultUnit());
        }
    }
    @Test void unchangedNullEmptyClosedAndLegacyDimensionsSurviveWithoutRepairTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);seed(f,71,"","old-unit",false);f.anchor.commit();
            for(var values:List.of(new LineDimensions(19,"",null),new LineDimensions(19,"P-closed","old-unit"))) {
                try(var sql=f.anchor.prepareStatement("UPDATE customer_invoice_row SET project_number=?,result_unit_number=? WHERE invoice_id=?")){sql.setString(1,values.project());sql.setString(2,values.resultUnit());sql.setLong(3,id);sql.executeUpdate();}f.anchor.commit();
                var editor=editor(f,Kind.INVOICE,id);var loaded=editor.load();var before=snapshot(f);f.anchor.rollback();editor.save(editor.preview(loaded,patch(loaded,null,List.of(values))));assertEquals(before,snapshot(f));f.anchor.rollback();
                var row=editor.load().data().lines().getFirst();assertEquals(values.project(),row.project());assertEquals(values.resultUnit(),row.resultUnit());
            }
        }
    }
    @Test void changedDimensionCataloguesAndAccountRequirementsInvalidatePreviewsTest() throws Exception {
        try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=editor(f,Kind.INVOICE,id);
            for(String change:List.of("UPDATE project SET name='changed' WHERE number='P-Å'","UPDATE project SET concluded=TRUE WHERE number='P-Å'","UPDATE result_unit SET description='changed' WHERE number='U-1'","UPDATE account SET project_required=TRUE WHERE number=3010")) {
                try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE project SET concluded=FALSE WHERE number='P-Å'");}f.anchor.commit();
                var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,3022,List.of(new LineDimensions(19,"P-Å","U-1"))));
                try(var sql=f.anchor.createStatement()){sql.executeUpdate(change);}f.anchor.commit();assertThrows(IllegalArgumentException.class,()->editor.save(preview));assertFalse(editor.isBlocked());
            }
        }
    }
    @Test void lateDimensionFailureRollsBackAllEarlierWritesAndRetriesForBothKindsTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var store=new NormalizedInvoiceStore(f.anchor);var source=CustomerFinancialAccountingTest.document(f,kind,id);var extra=new SSSaleRow();extra.setQuantity(1);extra.setUnitprice(BigDecimal.TEN);extra.setAccountNr(3010);extra.setTaxCode(SSTaxCode.TAXRATE_0);source.getRows().add(extra);
            if(kind==Kind.INVOICE)store.updateInvoice(71,id,source);else store.updateCreditInvoice(71,id,(SSCreditInvoice)source);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row ADD CONSTRAINT dimension_failure CHECK(project_number IS NULL OR project_number<>'P-B')");}f.anchor.commit();
            var editor=editor(f,kind,id);var loaded=editor.load();var patch=new FinancialValues(LocalDate.of(2026,11,13),null,List.of(new LineAmounts(0,new BigDecimal("2"),new BigDecimal("50")),new LineAmounts(1,BigDecimal.ONE,BigDecimal.TEN)),List.of(new LineDetails(0,"changed",BigDecimal.TEN)),List.of(new LineAccounting(0,3022,"TAXRATE_1")),new TaxValues(false,new BigDecimal("30"),null,null),null,List.of(new LineDimensions(0,"P-Å","U-1"),new LineDimensions(1,"P-B","U-1")));
            var preview=editor.preview(loaded,patch);var before=snapshot(f);f.anchor.rollback();assertThrows(SQLException.class,()->editor.save(preview));assertEquals(before,snapshot(f));f.anchor.rollback();
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("ALTER TABLE "+table(kind)+"_row DROP CONSTRAINT dimension_failure");}f.anchor.commit();assertEquals(0,preview.after().compareTo(editor.save(preview).total()));
        }
    }
    @Test void localSavepointKeepsOuterWorkOnForeignOrDuplicateDimensionKeysTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var loaded=editor(f,kind,id).load();try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE "+table(kind)+" SET ocr_number='outer work' WHERE id="+id);}var before=snapshot(f);var store=new NormalizedInvoiceStore(f.anchor);
            for(var dims:List.of(List.of(new LineDimensions(19,"P-Å","U-1"),new LineDimensions(19,null,null)),List.of(new LineDimensions(19,"P-Å","U-1"),new LineDimensions(999,null,null)))) {
                var patch=patch(loaded,null,dims);
                if(dims.get(1).rowNumber()==19)assertThrows(IllegalArgumentException.class,()->update(store,kind,id,patch));else assertThrows(SQLException.class,()->update(store,kind,id,patch));assertEquals(before,snapshot(f));
            }f.anchor.rollback();
        }
    }
    @Test void unknownDimensionCommitBlocksBlindRetryRegardlessOfDurabilityTest() throws Exception {
        for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,Kind.INVOICE);var editor=new CustomerFinancialEditAdapter(()->{var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown dimension commit");}try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});},f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            var loaded=editor.load();var preview=editor.preview(loaded,patch(loaded,3022,List.of(new LineDimensions(19,"P-Å","U-1"))));assertThrows(SQLException.class,()->editor.save(preview));assertTrue(editor.isBlocked());assertThrows(SQLException.class,editor::load);assertThrows(SQLException.class,()->editor.save(preview));assertEquals(durable?"P-Å":null,editor(f,Kind.INVOICE,id).load().data().lines().getFirst().project());
        }
    }
    @Test void separateInvoiceAndCreditJournalsKeepRequiredDimensionsOnNewAccountTest() throws Exception {
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture()) {
            long id=target(f,kind);var editor=editor(f,kind,id);var loaded=editor.load();editor.save(editor.preview(loaded,patch(loaded,3022,List.of(new LineDimensions(19,"P-Å","U-1")))));assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());f.anchor.rollback();
            var journal=new CustomerJournalAdapter(f::open,f.gate,71,11,kind==Kind.INVOICE?CustomerJournalAdapter.Kind.INVOICE:CustomerJournalAdapter.Kind.CREDIT,CustomerCreditGuiFixture.CLOCK);var preview=journal.preview(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));assertEquals(0,new BigDecimal("125").compareTo(preview.summary().debit()));assertEquals(0,new BigDecimal("125").compareTo(preview.summary().credit()));assertTrue(preview.summary().rows().stream().anyMatch(r->Integer.valueOf(3022).equals(r.account())&&"P-Å".equals(r.project())&&"U-1".equals(r.resultUnit())));journal.post(preview);assertThrows(SQLException.class,editor::load);
        }
    }
    static long target(CustomerCreditGuiFixture f,Kind kind)throws Exception {
        long id=CustomerFinancialHeaderTaxTest.target(f,kind);CustomerFinancialAccountingTest.addAccount(f,11,3022,true,true,true);seed(f,71,"P-Å","U-1",false);seed(f,71,"P-B",null,false);seed(f,71,"P-closed",null,true);f.anchor.commit();return id;
    }
    private static void seed(CustomerCreditGuiFixture f,int company,String project,String unit,boolean closed)throws Exception {
        try(var sql=f.anchor.prepareStatement("INSERT INTO project(company_id,number,name,description,concluded) SELECT id,?,?,?,? FROM company WHERE legacy_id=?")){sql.setString(1,project);sql.setString(2,project+" name");sql.setString(3,"description");sql.setBoolean(4,closed);sql.setInt(5,company);sql.executeUpdate();}
        if(unit!=null)try(var sql=f.anchor.prepareStatement("INSERT INTO result_unit(company_id,number,name) SELECT id,?,? FROM company WHERE legacy_id=?")){sql.setString(1,unit);sql.setString(2,unit+" name");sql.setInt(3,company);sql.executeUpdate();}
    }
    static CustomerFinancialEditAdapter editor(CustomerCreditGuiFixture f,Kind kind,long id){return CustomerFinancialAccountingTest.editor(f,kind,id);}
    static FinancialValues patch(CustomerFinancialEditAdapter.Loaded loaded,Integer account,List<LineDimensions> dimensions){return new FinancialValues(loaded.data().values().date(),loaded.data().values().dueDate(),loaded.data().values().rows(),List.of(),account==null?List.of():List.of(new LineAccounting(loaded.data().lines().getFirst().rowNumber(),account,loaded.data().lines().getFirst().taxCode())),null,null,dimensions);}
    private static Map<String,List<String>> snapshot(CustomerCreditGuiFixture f)throws Exception{return CustomerFinancialEditAdapterTest.snapshot(f,null,-1,Set.of());}
    private static String table(Kind kind){return kind==Kind.INVOICE?"customer_invoice":"customer_credit_invoice";}
    private static void update(NormalizedInvoiceStore store,Kind kind,long id,FinancialValues values)throws Exception{if(kind==Kind.INVOICE)store.updateInvoiceFinancialValues(71,id,values);else store.updateCreditFinancialValues(71,id,values);}
}
