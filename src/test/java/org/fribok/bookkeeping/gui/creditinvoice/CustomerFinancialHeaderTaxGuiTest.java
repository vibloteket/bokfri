package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerFinancialHeaderTaxGuiTest {
    @Test
    void bothViewRoutesEditRatesAndExemptionWithFreshPreviewAndOneRefreshTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for(var kind:Kind.values())try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialHeaderTaxTest.target(f,kind);
            var session=new CustomerDocumentGuiSession(()->{assertFalse(SwingUtilities.isEventDispatchThread());return f.open();},f.gate,71,11,CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner=new AtomicReference<>();AtomicReference<CustomerDocumentView> ref=new AtomicReference<>();AtomicInteger refreshes=new AtomicInteger();
            try {
                edt(()->{var frame=new JFrame();owner.set(frame);var view=new CustomerDocumentView(frame,session);ref.set(view);frame.add(view);frame.setSize(1150,550);frame.setVisible(true);});
                var view=ref.get();var rows=component(view,kind==Kind.INVOICE?"normalized-invoices":"normalized-credits",JTable.class);
                waitFor(()->rows.getRowCount()==(kind==Kind.INVOICE?2:1));
                edt(()->{
                    rows.getRowSorter().toggleSortOrder(1);
                    for(int i=0;i<rows.getRowCount();i++)if(Long.valueOf(id).equals(rows.getValueAt(i,0)))rows.setRowSelectionInterval(i,i);
                    rows.getModel().addTableModelListener(event->{if(rows.getRowCount()>0){assertTrue(SwingUtilities.isEventDispatchThread());refreshes.incrementAndGet();}});
                });
                SwingUtilities.invokeLater(()->component(view,kind==Kind.INVOICE?"invoice-edit":"credit-edit",JButton.class).doClick());
                var dialog=showing();ready(dialog);
                try {
                    edt(()->{
                        assertTrue(dialog.getTitle().contains("SQL-ID "+id));
                        assertEquals(Boolean.FALSE,flag(dialog).getSelectedItem());
                        assertEquals(0,new BigDecimal("25").compareTo(new BigDecimal(rate(dialog,1).getText())));
                        rate(dialog,1).setText("30,0");button(dialog,"financial-preview").doClick();
                    });previewed(dialog);
                    edt(()->{
                        assertTrue(status(dialog).getText().contains("130"));check(dialog).doClick();assertTrue(button(dialog,"financial-save").isEnabled());
                        flag(dialog).setSelectedItem(Boolean.TRUE);assertFalse(check(dialog).isSelected());assertFalse(button(dialog,"financial-save").isEnabled());
                        button(dialog,"financial-preview").doClick();
                    });previewed(dialog);
                    edt(()->{
                        assertTrue(status(dialog).getText().contains("100"));flag(dialog).setSelectedItem(Boolean.FALSE);rate(dialog,1).setText("40");
                        assertFalse(check(dialog).isEnabled());button(dialog,"financial-preview").doClick();
                    });previewed(dialog);
                    edt(()->{assertTrue(status(dialog).getText().contains("140"));check(dialog).doClick();button(dialog,"financial-save").doClick();});
                    waitFor(()->!dialog.isDisplayable()&&refreshes.get()==1);
                    var saved=CustomerFinancialAccountingTest.editor(f,kind,id).load().data();
                    assertFalse(saved.headerTax().taxFree());assertEquals(0,new BigDecimal("40").compareTo(saved.headerTax().rate1()));
                    assertEquals(0,new BigDecimal("140").compareTo(saved.total()));
                    assertEquals(777,CustomerFinancialAccountingTest.document(f,kind,id).getStoredVoucher().getNumber());
                } finally {edt(dialog::dispose);}
            } finally {edt(()->{if(ref.get()!=null)ref.get().close();if(owner.get()!=null)owner.get().dispose();});session.close();}
        }
    }

    @Test
    void invalidOrMissingUsedRateCannotConfirmAndStaleSaveRequiresReloadTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialHeaderTaxTest.target(f,Kind.INVOICE);var editor=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id);
            AtomicInteger callbacks=new AtomicInteger();var dialog=create(editor,saved->callbacks.incrementAndGet());ready(dialog);
            try {
                for(String value:List.of("bad","1e-31")) {
                    edt(()->{rate(dialog,1).setText(value);button(dialog,"financial-preview").doClick();assertFalse(check(dialog).isEnabled());assertFalse(button(dialog,"financial-save").isEnabled());});
                }
                edt(()->{rate(dialog,1).setText("-1");button(dialog,"financial-preview").doClick();});
                waitFor(()->status(dialog).getText().contains("0..100"));
                edt(()->{assertFalse(check(dialog).isEnabled());rate(dialog,1).setText("");button(dialog,"financial-preview").doClick();});
                waitFor(()->status(dialog).getText().contains("present stored rates"));
                edt(()->{assertFalse(check(dialog).isEnabled());rate(dialog,1).setText("30");button(dialog,"financial-preview").doClick();});previewed(dialog);
                try(var sql=f.anchor.createStatement()){sql.executeUpdate("UPDATE customer_invoice SET tax_rate_1=26 WHERE id="+id);}f.anchor.commit();
                edt(()->{check(dialog).doClick();button(dialog,"financial-save").doClick();});waitFor(()->status(dialog).getText().contains("stale"));
                edt(()->{assertFalse(check(dialog).isEnabled());button(dialog,"financial-reload").doClick();});ready(dialog);
                edt(()->{assertEquals(0,new BigDecimal("26").compareTo(new BigDecimal(rate(dialog,1).getText())));assertFalse(check(dialog).isSelected());});
                assertEquals(0,callbacks.get());
            } finally {edt(dialog::dispose);}
        }
    }

    @Test
    void unknownHeaderCommitDisablesEveryNewTaxInputRegardlessOfDurabilityTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for(boolean durable:List.of(false,true))try(var f=new CustomerCreditGuiFixture();var ignored=new LegacyGuard()) {
            long id=CustomerFinancialHeaderTaxTest.target(f,Kind.INVOICE);
            var editor=new CustomerFinancialEditAdapter(()->{
                assertFalse(SwingUtilities.isEventDispatchThread());var c=f.open();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("commit")){if(durable)c.commit();throw new SQLException("Unknown header commit");}
                    try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            },f.gate,71,11,Kind.INVOICE,id,CustomerCreditGuiFixture.CLOCK);
            AtomicInteger callbacks=new AtomicInteger();var dialog=create(editor,saved->callbacks.incrementAndGet());ready(dialog);
            try {
                edt(()->{rate(dialog,1).setText("30");button(dialog,"financial-preview").doClick();});previewed(dialog);
                edt(()->{check(dialog).doClick();button(dialog,"financial-save").doClick();});
                waitFor(()->editor.isBlocked()&&button(dialog,"financial-cancel").isEnabled());
                edt(()->{
                    assertFalse(flag(dialog).isEnabled());for(int i=1;i<=3;i++)assertFalse(rate(dialog,i).isEnabled());
                    assertFalse(button(dialog,"financial-preview").isEnabled());assertFalse(button(dialog,"financial-save").isEnabled());assertFalse(button(dialog,"financial-reload").isEnabled());
                });
                assertEquals(0,callbacks.get());
                var stored=CustomerFinancialAccountingTest.editor(f,Kind.INVOICE,id).load().data().headerTax();
                assertEquals(0,new BigDecimal(durable?"30":"25").compareTo(stored.rate1()));
            } finally {edt(dialog::dispose);}
        }
    }

    private static CustomerFinancialEditDialog create(CustomerFinancialEditAdapter editor,java.util.function.Consumer<CustomerFinancialEditAdapter.Saved> saved)throws Exception{AtomicReference<CustomerFinancialEditDialog> ref=new AtomicReference<>();edt(()->ref.set(new CustomerFinancialEditDialog(null,editor,saved)));return ref.get();}
    private static JTextField rate(CustomerFinancialEditDialog d,int number){return component(d,"financial-taxrate-"+number,JTextField.class);}
    private static JComboBox<?> flag(CustomerFinancialEditDialog d){return component(d,"financial-taxfree",JComboBox.class);}
    private static JCheckBox check(CustomerFinancialEditDialog d){return component(d,"financial-confirm",JCheckBox.class);}
    private static JButton button(CustomerFinancialEditDialog d,String name){return component(d,name,JButton.class);}
    private static JTextArea status(CustomerFinancialEditDialog d){return component(d,"financial-status",JTextArea.class);}
    private static void ready(CustomerFinancialEditDialog d)throws Exception{waitFor(()->button(d,"financial-preview").isEnabled());}
    private static void previewed(CustomerFinancialEditDialog d)throws Exception{waitFor(()->check(d).isEnabled());}
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
