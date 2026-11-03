package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSSupplierListPrinter extends SSPrinter {

    private List<SSSupplier> iSuppliers;

    /** Creates an isolated company-wide register report without an accounting-year context. */
    public SSSupplierListPrinter(SSNewCompany company, List<SSSupplier> entries) {
        super(company);
        iSuppliers = new ArrayList<>(entries);
        configureReport();
    }


    /**
     *
     */
    public SSSupplierListPrinter() {
        this(SSDB.getInstance().getSuppliers());
    }

    /**
     *
     * @param iSuppliers
     */
    public SSSupplierListPrinter(List<SSSupplier> iSuppliers) {
        // Get all orders
        this.iSuppliers = iSuppliers;

        configureReport();
    }

    private void configureReport() {
        setPageHeader("header.jrxml");
        setColumnHeader("supplierlist.jrxml");
        setDetail("supplierlist.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("supplierlistreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        SSDefaultTableModel<SSSupplier> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSAccount.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSSupplier iSupplier = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iSupplier.getNumber();
                    break;

                case 1:
                    value = iSupplier.getName();
                    break;

                case 2:
                    value = iSupplier.getPhone1();
                    break;

                case 3:
                    value = iSupplier.getOurCustomerNr();
                    break;

                case 4:
                    value = iSupplier.getYourContact();
                    break;

                }

                return value;
            }
        };

        iModel.addColumn("supplier.number");
        iModel.addColumn("supplier.name");
        iModel.addColumn("supplier.phone");
        iModel.addColumn("supplier.ourcustomernumber");
        iModel.addColumn("supplier.contact");

        Collections.sort(iSuppliers, (o1, o2) -> o1.getNumber().compareTo(o2.getNumber()));

        iModel.setObjects(iSuppliers);

        return iModel;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSSupplierListPrinter");
        sb.append("{iSuppliers=").append(iSuppliers);
        sb.append('}');
        return sb.toString();
    }
}
