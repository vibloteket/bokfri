package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSSupplierBalanceMath;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSSupplierdebtPrinter extends SSPrinter {

    private Map<SSSupplierInvoice, BigDecimal> iSaldos;

    /** Creates an isolated dated debt report from explicit company/year and invoice/payment inputs. */
    public SSSupplierdebtPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSSupplierInvoice> invoices, List<SSSupplierCreditInvoice> credits,
            List<SSOutpayment> payments, LocalDate date) {
        super(company, year);
        SSSupplierBalanceMath balances = new SSSupplierBalanceMath(payments, credits, date);
        iSaldos = new HashMap<>();
        for (SSSupplierInvoice invoice : invoices) {
            if (SSSupplierBalanceMath.included(invoice, date)) {
                iSaldos.put(invoice, balances.saldo(invoice).setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        configureReport(date);
    }


    /**
     *
     * @param iDate
     */
    public SSSupplierdebtPrinter(LocalDate iDate) {
        this(iDate, SSDB.getInstance().getSupplierInvoices());
    }

    /**
     *
     * @param iDate
     * @param iInvoices
     */
    public SSSupplierdebtPrinter(LocalDate iDate, List<SSSupplierInvoice> iInvoices) {
        iSaldos = SSSupplierInvoiceMath.getSaldo(iInvoices, iDate);

        configureReport(iDate);
    }

    private void configureReport(LocalDate iDate) {
        setPageHeader("header_period.jrxml");
        setColumnHeader("supplierdebt.jrxml");
        setDetail("supplierdebt.jrxml");
        setSummary("supplierdebt.jrxml");

        addParameter("periodTitle", iBundle.getString("supplierdebtreport.periodtitle"));
        addParameter("periodText", iDate);
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return iBundle.getString("supplierdebtreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        // Get all invoices
        List<SSSupplierInvoice> iInvoices = new LinkedList<>(
                iSaldos.keySet());

        // Sort the invoices
        Collections.sort(iInvoices, (o1, o2) -> o1.getNumber() - o2.getNumber());

        SSDefaultTableModel<SSSupplierInvoice> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSSupplierInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSSupplierInvoice iInvoice = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iInvoice.getNumber();
                    break;

                case 1:
                    value = iInvoice.getSupplierNr();
                    break;

                case 2:
                    value = iInvoice.getSupplierName();
                    break;

                case 3:
                    value = iInvoice.getLocalDate() == null
                            ? null
                            : iInvoice.getLocalDate().format(iLocalDateFormat);
                    break;

                case 4:
                    value = iInvoice.getCurrency() == null
                            ? null
                            : iInvoice.getCurrency().getName();
                    break;

                case 5:
                    value = SSSupplierInvoiceMath.getTotalSum(iInvoice);
                    break;

                case 6:
                    value = iSaldos.get(iInvoice);
                    break;

                case 7:
                    BigDecimal iSaldo = iSaldos.get(iInvoice);

                    value = SSSupplierInvoiceMath.convertToLocal(iInvoice, iSaldo);
                    break;

                }

                return value;
            }
        };

        iModel.addColumn("supplierinvoice.number");

        iModel.addColumn("supplier.number");
        iModel.addColumn("supplier.name");

        iModel.addColumn("supplierinvoice.date");
        iModel.addColumn("supplierinvoice.currency");
        iModel.addColumn("supplierinvoice.sum");
        iModel.addColumn("supplierinvoice.saldo");
        iModel.addColumn("supplierinvoice.localsaldo");

        iModel.setObjects(iInvoices);

        return iModel;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSSupplierdebtPrinter");
        sb.append("{iSaldos=").append(iSaldos);
        sb.append('}');
        return sb.toString();
    }
}
