package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSCustomer;
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
public class SSCustomerListPrinter extends SSPrinter {

    private List<SSCustomer> iCustomers;

    /** Creates an isolated company-wide register report without an accounting-year context. */
    public SSCustomerListPrinter(SSNewCompany company, List<SSCustomer> entries) {
        super(company);
        iCustomers = new ArrayList<>(entries);
        configureReport();
    }


    /**
     *
     */
    public SSCustomerListPrinter() {
        this(SSDB.getInstance().getCustomers());
    }

    /**
     *
     * @param iCustomers
     */
    public SSCustomerListPrinter(List<SSCustomer> iCustomers) {
        // Get all orders
        this.iCustomers = iCustomers;

        configureReport();
    }

    private void configureReport() {
        setPageHeader("header.jrxml");
        setColumnHeader("customerlist.jrxml");
        setDetail("customerlist.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("customerlistreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {

        SSDefaultTableModel<SSCustomer> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSAccount.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSCustomer iCustomer = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iCustomer.getNumber();
                    break;

                case 1:
                    value = iCustomer.getName();
                    break;

                case 2:
                    value = iCustomer.getYourContactPerson();
                    break;

                case 3:
                    value = iCustomer.getRegistrationNumber();
                    break;

                case 4:
                    value = iCustomer.getPhone1();
                    break;

                case 5:
                    value = iCustomer.getTelefax();
                    break;

                }

                return value;
            }
        };

        iModel.addColumn("customer.number");
        iModel.addColumn("customer.name");
        iModel.addColumn("customer.contact");
        iModel.addColumn("customer.registrationnumber");
        iModel.addColumn("customer.phone");
        iModel.addColumn("customer.telefax");

        Collections.sort(iCustomers, (o1, o2) -> o1.getNumber().compareTo(o2.getNumber()));

        iModel.setObjects(iCustomers);

        return iModel;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSCustomerListPrinter");
        sb.append("{iCustomers=").append(iCustomers);
        sb.append('}');
        return sb.toString();
    }
}
