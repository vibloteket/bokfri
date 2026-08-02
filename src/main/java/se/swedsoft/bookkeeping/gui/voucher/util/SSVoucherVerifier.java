package se.swedsoft.bookkeeping.gui.voucher.util;


import org.fribok.bookkeeping.service.voucher.VoucherValidationResult;
import org.fribok.bookkeeping.service.voucher.VoucherValidator;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


/**
 * Tests if a voucher is isValid
 * Date: 2006-feb-06
 * Time: 09:27:58
 */
public class SSVoucherVerifier  implements PropertyChangeListener, TableModelListener, ActionListener {

    public interface OnUpdate {
        void update(boolean valid, String Error);
    }

    private SSVoucher iVoucher;

    private List<JComponent> iComponents;

    private boolean iValid;

    private String iError;

    private OnUpdate iOnUpdate;

    /**
     *
     * @param pComponents
     */
    public SSVoucherVerifier(JComponent... pComponents) {
        iVoucher = null;
        iComponents = new LinkedList<>();
        iError = null;
        iOnUpdate = null;
        iValid = false;

        iComponents.addAll(Arrays.asList(pComponents));
    }

    /**
     *
     * @param pVoucher
     * @param pComponents
     */
    public SSVoucherVerifier(SSVoucher pVoucher, JComponent... pComponents) {
        iVoucher = pVoucher;
        iComponents = new LinkedList<>();
        iError = null;
        iOnUpdate = null;

        iComponents.addAll(Arrays.asList(pComponents));
    }

    /**
     *
     * @param pVoucher
     */
    public void setVoucher(SSVoucher pVoucher) {
        iVoucher = pVoucher;
    }

    /**
     *
     * @param e
     */
    public void tableChanged(TableModelEvent e) {
        update();
    }

    /**
     *
     * @param evt
     */
    public void propertyChange(PropertyChangeEvent evt) {
        update();
    }

    /**
     *
     * @param e
     */
    public void actionPerformed(ActionEvent e) {
        update();
    }

    /**
     *
     * @return If the voucher is isValid
     */
    private boolean validate() {
        VoucherValidationResult result = VoucherValidator.validate(iVoucher,
                SSDB.getInstance().getCurrentYear());
        iError = result.valid() ? null : result.issues().get(0).message();
        return result.valid();
    }

    /**
     *
     */
    public void update() {
        if (iVoucher == null) {
            return;
        }

        iValid = validate();

        if (iOnUpdate != null) {
            iOnUpdate.update(iValid, iError);
        }

        setComponentsEnabled(iValid);
    }

    /**
     *
     * @param enabled
     */
    private void setComponentsEnabled(boolean enabled) {
        for (JComponent iComponent: iComponents) {
            iComponent.setEnabled(enabled);
        }

    }

    /**
     *
     * @return
     */
    public boolean isValid() {
        return iValid;
    }

    /**
     *
     * @return
     */
    public String getError() {
        return iError;
    }

    /**
     *
     * @param pOnUpdate
     */
    public void setOnUpdate(OnUpdate pOnUpdate) {
        iOnUpdate = pOnUpdate;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.voucher.util.SSVoucherVerifier");
        sb.append("{iComponents=").append(iComponents);
        sb.append(", iError='").append(iError).append('\'');
        sb.append(", iOnUpdate=").append(iOnUpdate);
        sb.append(", iValid=").append(iValid);
        sb.append(", iVoucher=").append(iVoucher);
        sb.append('}');
        return sb.toString();
    }
}
