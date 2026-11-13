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
class CustomerFinancialDimensionsGuiTest {
    @Test void bothViewRoutesEditRequiredDimensionsWithRealCellsAndFreshConfirmationTest()throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialDimensionsTest.target(f,kind);var session=new CustomerDocumentGuiSession(()->{assertFalse(SwingUtilities.isEventDispatchThread());return f.open();},f.gate,71,11,CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner=new AtomicReference<>();AtomicReference<CustomerDocumentView> ref=new AtomicReference<>();AtomicInteger refreshes=new AtomicInteger();
            try {
                edt(()->{var frame=new JFrame();owner.set(frame);var view=new CustomerDocumentView(frame,session);ref.set(view);frame.add(view);frame.setSize(1150,550);frame.setVisible(true);});
                var view=ref.get();var table=component(view,kind==Kind.INVOICE?"normalized-invoices":"normalized-credits",JTable.class);waitFor(()->table.getRowCount()==(kind==Kind.INVOICE?2:1));
                edt(()->{table.getRowSorter().toggleSortOrder(1);for(int row=0;row<table.getRowCount();row++)if(Long.valueOf(id).equals(table.getValueAt(row,0)))table.setRowSelectionInterval(row,row);table.getModel().addTableModelListener(event->{if(table.getRowCount()>0){assertTrue(SwingUtilities.isEventDispatchThread());refreshes.incrementAndGet();}});});
                SwingUtilities.invokeLater(()->button(view,kind==Kind.INVOICE?"invoice-edit":"credit-edit").doClick());var d=showing();ready(d);
                try {
                    var rows=component(d,"financial-lines",JTable.class);
                    edt(()->{assertEquals(19,rows.getValueAt(0,0));assertTrue(component(d,"financial-accounting-context",JTextArea.class).getText().contains("P-Å"));rows.setValueAt(3022,0,2);button(d,"financial-preview").doClick();});
                    waitFor(()->status(d).getText().contains("requires valid company"));
                    edt(()->{assertFalse(check(d).isEnabled());assertTrue(rows.editCellAt(0,7));((JTextField)rows.getEditorComponent()).setText("P-Å");assertTrue(rows.getCellEditor().stopCellEditing());assertTrue(rows.editCellAt(0,8));((JTextField)rows.getEditorComponent()).setText("U-1");button(d,"financial-preview").doClick();});previewed(d);
                    edt(()->{check(d).doClick();assertTrue(rows.editCellAt(0,7));assertFalse(check(d).isSelected());assertFalse(button(d,"financial-save").isEnabled());((JTextField)rows.getEditorComponent()).setText("P-B");button(d,"financial-preview").doClick();});previewed(d);
                    edt(()->{check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->!d.isDisplayable()&&refreshes.get()==1);
                    var row=CustomerFinancialDimensionsTest.editor(f,kind,id).load().data().lines().getFirst();assertEquals(3022,row.account());assertEquals("P-B",row.project());assertEquals("U-1",row.resultUnit());assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
                }finally{edt(d::dispose);}
            }finally{edt(()->{if(ref.get()!=null)ref.get().close();if(owner.get()!=null)owner.get().dispose();});session.close();}
        }
    }
    @Test void closedOrMissingDimensionsCannotConfirmAndChangedCatalogueRequiresReloadTest()throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialDimensionsTest.target(f,Kind.INVOICE);var editor=CustomerFinancialDimensionsTest.editor(f,Kind.INVOICE,id);AtomicInteger callbacks=new AtomicInteger();var d=create(editor,saved->callbacks.incrementAndGet());ready(d);
            try {
                var rows=component(d,"financial-lines",JTable.class);
                for(String project:List.of("P-closed","missing")) {edt(()->{rows.setValueAt(project,0,7);button(d,"financial-preview").doClick();});waitFor(()->status(d).getText().contains("open project"));edt(()->assertFalse(check(d).isEnabled()));}
                edt(()->{rows.setValueAt(3022,0,2);rows.setValueAt("P-Å",0,7);rows.setValueAt("U-1",0,8);button(d,"financial-preview").doClick();});previewed(d);
                try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE project SET concluded=TRUE WHERE number='P-Å'");}f.anchor.commit();
                edt(()->{check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->status(d).getText().contains("stale"));edt(()->button(d,"financial-reload").doClick());ready(d);
                edt(()->{assertNull(rows.getValueAt(0,7));assertNull(rows.getValueAt(0,8));assertEquals(3010,rows.getValueAt(0,2));assertFalse(component(d,"financial-accounting-context",JTextArea.class).getText().contains("P-Å"));});assertEquals(0,callbacks.get());
            }finally{edt(d::dispose);}
        }
    }
    @Test void unknownDimensionCommitBlocksNewCellsRegardlessOfDurabilityTest()throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialDimensionsTest.target(f,Kind.INVOICE);var editor=new CustomerFinancialEditAdapter(()->{var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown dimensions");}try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});},f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            AtomicInteger callbacks=new AtomicInteger();var d=create(editor,saved->callbacks.incrementAndGet());ready(d);
            try {edt(()->{var rows=component(d,"financial-lines",JTable.class);rows.setValueAt(3022,0,2);rows.setValueAt("P-Å",0,7);rows.setValueAt("U-1",0,8);button(d,"financial-preview").doClick();});previewed(d);edt(()->{check(d).doClick();button(d,"financial-save").doClick();});waitFor(()->editor.isBlocked()&&button(d,"financial-cancel").isEnabled());edt(()->{var rows=component(d,"financial-lines",JTable.class);assertFalse(rows.isEnabled());assertFalse(rows.isCellEditable(0,7));assertFalse(rows.isCellEditable(0,8));assertFalse(button(d,"financial-reload").isEnabled());});assertEquals(0,callbacks.get());assertEquals(durable?"P-Å":null,CustomerFinancialDimensionsTest.editor(f,Kind.INVOICE,id).load().data().lines().getFirst().project());}finally{edt(d::dispose);}
        }
    }
    private static CustomerFinancialEditDialog create(CustomerFinancialEditAdapter editor,java.util.function.Consumer<CustomerFinancialEditAdapter.Saved> saved)throws Exception{AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();edt(()->ref.set(new CustomerFinancialEditDialog(null,editor,saved)));return ref.get();}
    private static JButton button(Container d,String name){return component(d,name,JButton.class);}
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
