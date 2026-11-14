package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerFinancialRowRemovalGuiTest {
 @Test void bothViewRoutesRemoveSortedDraftRowsOnlyAfterExplicitKeyPreviewAndConfirmationTest()throws Exception {
  assumeFalse(GraphicsEnvironment.isHeadless());for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
   long id=CustomerFinancialRowRemovalTest.target(f,kind);var session=new CustomerDocumentGuiSession(()->{assertFalse(SwingUtilities.isEventDispatchThread());return f.open();},f.gate,71,11,CustomerCreditGuiFixture.CLOCK);AtomicReference<JFrame> owner=new AtomicReference<>();AtomicReference<CustomerDocumentView> ref=new AtomicReference<>();AtomicInteger refreshes=new AtomicInteger();
   try {
    edt(()->{var frame=new JFrame();owner.set(frame);var view=new CustomerDocumentView(frame,session);ref.set(view);frame.add(view);frame.setSize(1150,550);frame.setVisible(true);});var view=ref.get();var docs=component(view,kind==Kind.INVOICE?"normalized-invoices":"normalized-credits",JTable.class);waitFor(()->docs.getRowCount()==(kind==Kind.INVOICE?2:1));
    edt(()->{for(int row=0;row<docs.getRowCount();row++)if(Long.valueOf(id).equals(docs.getValueAt(row,0)))docs.setRowSelectionInterval(row,row);docs.getModel().addTableModelListener(event->{if(docs.getRowCount()>0){assertTrue(SwingUtilities.isEventDispatchThread());refreshes.incrementAndGet();}});});SwingUtilities.invokeLater(()->button(view,kind==Kind.INVOICE?"invoice-edit":"credit-edit").doClick());var d=showing();ready(d);
    try {
     var rows=component(d,"financial-lines",JTable.class);edt(()->button(d,"financial-preview").doClick());previewed(d);
     edt(()->{check(d).doClick();rows.getRowSorter().toggleSortOrder(0);rows.getRowSorter().toggleSortOrder(0);select(rows,7);assertTrue(button(d,"financial-remove-row").isEnabled());button(d,"financial-remove-row").doClick();assertFalse(check(d).isSelected());assertFalse(button(d,"financial-save").isEnabled());assertEquals(1,rows.getRowCount());assertEquals(19,rows.getValueAt(0,0));assertFalse(button(d,"financial-remove-row").isEnabled());});
     assertEquals(2,CustomerFinancialRowRemovalTest.editor(f,kind,id).load().data().lines().size());
     edt(()->button(d,"financial-reload").doClick());ready(d);
     edt(()->{assertEquals(2,rows.getRowCount());select(rows,7);button(d,"financial-remove-row").doClick();button(d,"financial-preview").doClick();});previewed(d);
     edt(()->{assertTrue(status(d).getText().contains("[7]"));assertTrue(status(d).getText().contains("40"));assertFalse(button(d,"financial-save").isEnabled());check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->!d.isDisplayable()&&refreshes.get()==1);
     var saved=CustomerFinancialRowRemovalTest.editor(f,kind,id).load().data();assertEquals(List.of(19),saved.lines().stream().map(l->l.rowNumber()).toList());assertEquals("P-Å",saved.lines().getFirst().project());assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
    }finally{edt(d::dispose);}
   }finally{edt(()->{if(ref.get()!=null)ref.get().close();if(owner.get()!=null)owner.get().dispose();});session.close();}
  }
 }
 @Test void staleRemovedRowRequiresReloadAndDoesNotDeleteAnythingTest()throws Exception {
  assumeFalse(GraphicsEnvironment.isHeadless());try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
   long id=CustomerFinancialRowRemovalTest.target(f,Kind.INVOICE);var editor=CustomerFinancialRowRemovalTest.editor(f,Kind.INVOICE,id);AtomicInteger callbacks=new AtomicInteger();var d=create(editor,saved->callbacks.incrementAndGet());ready(d);
   try {
    edt(()->{select(component(d,"financial-lines",JTable.class),7);button(d,"financial-remove-row").doClick();button(d,"financial-preview").doClick();});previewed(d);
    try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice_row SET description='external edit' WHERE invoice_id="+id+" AND row_number=7");}f.anchor.commit();
    edt(()->{check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->status(d).getText().contains("stale"));edt(()->{assertFalse(check(d).isEnabled());button(d,"financial-reload").doClick();});ready(d);
    edt(()->{var rows=component(d,"financial-lines",JTable.class);assertEquals(2,rows.getRowCount());for(int row=0;row<rows.getRowCount();row++)if(Integer.valueOf(7).equals(rows.getValueAt(row,0)))assertEquals("external edit",rows.getValueAt(row,1));});assertEquals(0,callbacks.get());assertEquals(2,CustomerFinancialRowRemovalTest.editor(f,Kind.INVOICE,id).load().data().lines().size());
   }finally{edt(d::dispose);}
  }
 }
 @Test void unknownRemovalCommitBlocksFurtherRemovalAndRetryRegardlessOfDurabilityTest()throws Exception {
  assumeFalse(GraphicsEnvironment.isHeadless());for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
   long id=CustomerFinancialRowRemovalTest.target(f,Kind.INVOICE);var editor=new CustomerFinancialEditAdapter(()->{var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown row removal");}try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});},f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);AtomicInteger callbacks=new AtomicInteger();var d=create(editor,saved->callbacks.incrementAndGet());ready(d);
   try {edt(()->{select(component(d,"financial-lines",JTable.class),7);button(d,"financial-remove-row").doClick();button(d,"financial-preview").doClick();});previewed(d);edt(()->{check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->editor.isBlocked()&&button(d,"financial-cancel").isEnabled());edt(()->{assertFalse(button(d,"financial-remove-row").isEnabled());assertFalse(button(d,"financial-reload").isEnabled());assertFalse(button(d,"financial-save").isEnabled());assertFalse(component(d,"financial-lines",JTable.class).isEnabled());});assertEquals(0,callbacks.get());assertEquals(durable?List.of(19):List.of(7,19),CustomerFinancialRowRemovalTest.editor(f,Kind.INVOICE,id).load().data().lines().stream().map(l->l.rowNumber()).toList());}finally{edt(d::dispose);}
  }
 }
 private static void select(JTable rows,int key){for(int row=0;row<rows.getRowCount();row++)if(Integer.valueOf(key).equals(rows.getValueAt(row,0))){rows.setRowSelectionInterval(row,row);return;}throw new AssertionError("Missing key "+key);}
 private static CustomerFinancialEditDialog create(CustomerFinancialEditAdapter editor,java.util.function.Consumer<CustomerFinancialEditAdapter.Saved> saved)throws Exception{AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();edt(()->ref.set(new CustomerFinancialEditDialog(null,editor,saved)));return ref.get();}
 private static JButton button(Container p,String name){return component(p,name,JButton.class);}
 private static JCheckBox check(CustomerFinancialEditDialog d){return component(d,"financial-confirm",JCheckBox.class);}
 private static JTextArea status(CustomerFinancialEditDialog d){return component(d,"financial-status",JTextArea.class);}
 private static void ready(CustomerFinancialEditDialog d)throws Exception{waitFor(()->button(d,"financial-preview").isEnabled());}
 private static void previewed(CustomerFinancialEditDialog d)throws Exception{waitFor(()->check(d).isEnabled());}
 private static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
 private static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{CustomerCreditDialogAdapterTest.waitFor(condition);}
 private static CustomerFinancialEditDialog showing()throws Exception{AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();waitFor(()->{for(Window w:Window.getWindows())if(w instanceof CustomerFinancialEditDialog d&&d.isShowing())ref.set(d);return ref.get()!=null;});return ref.get();}
 private static <T extends Component>T component(Container p,String name,Class<T> type){for(Component c:p.getComponents()){if(name.equals(c.getName()))return type.cast(c);if(c instanceof Container nested){T r=component(nested,name,type);if(r!=null)return r;}}return null;}
 private static final class LegacyGuard implements AutoCloseable {
  final Field field;final Object db,old;
  LegacyGuard()throws Exception{field=se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");field.setAccessible(true);db=se.swedsoft.bookkeeping.data.system.SSDB.getInstance();old=field.get(db);field.set(db,Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new AssertionError("Legacy JDBC "+m.getName());}));}
  public void close()throws Exception{field.set(db,old);}
 }
}
