package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerFinancialAccountingGuiTest {
    @Test
    void bothViewRoutesUseRealAccountAndVatEditorsStoredRatesConfirmationAndOneRefreshTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialAccountingTest.target(f,kind);
            var session=new CustomerDocumentGuiSession(()->{
                assertFalse(SwingUtilities.isEventDispatchThread());return f.open();
            },f.gate,71,11,CustomerCreditGuiFixture.CLOCK);
            AtomicReference<CustomerDocumentView> ref=new AtomicReference<>();
            AtomicReference<JFrame> owner=new AtomicReference<>();
            AtomicInteger refreshes=new AtomicInteger();
            try {
                edt(()->{
                    var frame=new JFrame();owner.set(frame);
                    ref.set(new CustomerDocumentView(frame, session));frame.add(ref.get());
                    frame.setSize(1150,550);frame.setVisible(true);
                });
                var view=ref.get();var table=component(view,kind==Kind.INVOICE?"normalized-invoices":"normalized-credits",JTable.class);
                waitFor(()->table.getRowCount()==(kind==Kind.INVOICE?2:1));
                edt(()->{
                    table.getRowSorter().toggleSortOrder(1);
                    for(int row=0;row<table.getRowCount();row++)if(Long.valueOf(id).equals(table.getValueAt(row,0)))table.setRowSelectionInterval(row,row);
                    table.getModel().addTableModelListener(event->{if(table.getRowCount()>0){assertTrue(SwingUtilities.isEventDispatchThread());refreshes.incrementAndGet();}});
                });
                SwingUtilities.invokeLater(()->component(view,kind==Kind.INVOICE?"invoice-edit":"credit-edit",JButton.class).doClick());
                var dialog=showing();ready(dialog);
                try {
                    edt(()->{
                        assertTrue(dialog.getTitle().contains("SQL-ID "+id));
                        String context=component(dialog,"financial-accounting-context",JTextArea.class).getText();
                        assertTrue(context.contains("3020"));assertTrue(context.contains("TAXRATE_1 / 25 %"));
                        var rows=component(dialog,"financial-lines",JTable.class);
                        assertEquals(19,rows.getValueAt(0,0));assertFalse(rows.isCellEditable(0,0));
                        assertTrue(rows.editCellAt(0,2));((JTextField)rows.getEditorComponent()).setText("3020.5");
                        component(dialog,"financial-preview",JButton.class).doClick();assertTrue(rows.isEditing());
                        assertFalse(component(dialog,"financial-confirm",JCheckBox.class).isEnabled());
                        ((JTextField)rows.getEditorComponent()).setText("3020");
                        assertTrue(rows.getCellEditor().stopCellEditing());
                        assertTrue(rows.editCellAt(0,6));
                        var codes=(JComboBox<?>)rows.getEditorComponent();
                        @SuppressWarnings("unchecked") var renderer=(ListCellRenderer<Object>)codes.getRenderer();
                        var rendered=(JLabel)renderer.getListCellRendererComponent(new JList<>(),"TAXRATE_1",1,false,false);
                        assertTrue(rendered.getText().contains("25 %"));
                        codes.setSelectedItem("TAXRATE_1");component(dialog,"financial-preview",JButton.class).doClick();
                    });
                    previewed(dialog);
                    edt(()->{
                        assertFalse(component(dialog,"financial-save",JButton.class).isEnabled());
                        component(dialog,"financial-confirm",JCheckBox.class).doClick();
                        var rows=component(dialog,"financial-lines",JTable.class);assertTrue(rows.editCellAt(0,6));
                        assertFalse(component(dialog,"financial-confirm",JCheckBox.class).isSelected());
                        assertFalse(component(dialog,"financial-save",JButton.class).isEnabled());
                        ((JComboBox<?>)rows.getEditorComponent()).setSelectedItem("TAXRATE_2");
                        component(dialog,"financial-preview",JButton.class).doClick();
                    });
                    previewed(dialog);
                    edt(()->{component(dialog,"financial-confirm",JCheckBox.class).doClick();component(dialog,"financial-save",JButton.class).doClick();});
                    waitFor(()->!dialog.isDisplayable()&&refreshes.get()==1);
                    var saved=CustomerFinancialAccountingTest.editor(f,kind,id).load().data();
                    assertEquals(3020,saved.lines().getFirst().account());assertEquals("TAXRATE_2",saved.lines().getFirst().taxCode());
                    assertEquals(0,new BigDecimal("112").compareTo(saved.total()));
                    assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
                } finally {edt(dialog::dispose);}
            } finally {edt(()->{if(ref.get()!=null)ref.get().close();if(owner.get()!=null)owner.get().dispose();});session.close();}
        }
    }

    @Test
    void invalidAccountingInputsAndChangedPlanRequireNewPreviewOrReloadTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialAccountingTest.target(f,Kind.INVOICE);
            try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET tax_rate_3=NULL WHERE id="+id);}f.anchor.commit();
            var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);
            AtomicInteger callbacks=new AtomicInteger();AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();
            edt(()->ref.set(new CustomerFinancialEditDialog(null,editor,saved->callbacks.incrementAndGet())));
            var dialog=ref.get();ready(dialog);
            try {
                var rows=component(dialog,"financial-lines",JTable.class);
                edt(()->{rows.setValueAt(3999,0,2);component(dialog,"financial-preview",JButton.class).doClick();});
                waitFor(()->component(dialog,"financial-status",JTextArea.class).getText().contains("Sales account"));
                edt(()->{
                    assertFalse(component(dialog,"financial-save",JButton.class).isEnabled());
                    assertFalse(component(dialog,"financial-confirm",JCheckBox.class).isEnabled());
                    assertTrue(component(dialog,"financial-accounting-context",JTextArea.class).getText().contains("TAXRATE_3 / "));
                    rows.setValueAt(3020,0,2);rows.setValueAt("TAXRATE_3",0,6);component(dialog,"financial-preview",JButton.class).doClick();
                });
                waitFor(()->component(dialog,"financial-status",JTextArea.class).getText().contains("stored document rate"));
                edt(()->{assertFalse(component(dialog,"financial-confirm",JCheckBox.class).isEnabled());rows.setValueAt("TAXRATE_1",0,6);component(dialog,"financial-preview",JButton.class).doClick();});
                previewed(dialog);
                try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE account SET description='New account label' WHERE number=3020");}f.anchor.commit();
                edt(()->{component(dialog,"financial-confirm",JCheckBox.class).doClick();component(dialog,"financial-save",JButton.class).doClick();});
                waitFor(()->component(dialog,"financial-status",JTextArea.class).getText().contains("stale"));
                edt(()->{assertFalse(component(dialog,"financial-save",JButton.class).isEnabled());component(dialog,"financial-reload",JButton.class).doClick();});
                ready(dialog);
                edt(()->{
                    assertEquals(3010,rows.getValueAt(0,2));assertEquals("TAXRATE_0",rows.getValueAt(0,6));
                    assertTrue(component(dialog,"financial-accounting-context",JTextArea.class).getText().contains("New account label"));
                });
                assertEquals(0,callbacks.get());
            } finally {edt(dialog::dispose);}
        }
    }

    private static void ready(CustomerFinancialEditDialog d)throws Exception{waitFor(()->component(d,"financial-preview",JButton.class).isEnabled());}
    private static void previewed(CustomerFinancialEditDialog d)throws Exception{waitFor(()->component(d,"financial-confirm",JCheckBox.class).isEnabled());}
    private static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    private static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{CustomerCreditDialogAdapterTest.waitFor(condition);}
    private static CustomerFinancialEditDialog showing()throws Exception{AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();waitFor(()->{for(Window w:Window.getWindows())if(w instanceof CustomerFinancialEditDialog d&&d.isShowing())ref.set(d);return ref.get()!=null;});return ref.get();}
    private static <T extends Component>T component(Container parent,String name,Class<T> type){for(Component child:parent.getComponents()){if(name.equals(child.getName()))return type.cast(child);if(child instanceof Container nested){T result=component(nested,name,type);if(result!=null)return result;}}return null;}
    private static final class LegacyGuard implements AutoCloseable {
        final java.lang.reflect.Field field;final Object db,old;
        LegacyGuard()throws Exception{field=se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");field.setAccessible(true);db=se.swedsoft.bookkeeping.data.system.SSDB.getInstance();old=field.get(db);field.set(db,Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new AssertionError("Legacy JDBC "+m.getName());}));}
        public void close()throws Exception{field.set(db,old);}
    }
}
