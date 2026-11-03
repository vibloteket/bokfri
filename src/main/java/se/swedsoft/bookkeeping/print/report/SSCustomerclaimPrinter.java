package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSCustomerBalanceMath;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
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
public class SSCustomerclaimPrinter extends SSPrinter {

    private Map<SSInvoice, BigDecimal> iSaldos;
    private SSCustomerBalanceMath explicitBalances;

    /** Creates an isolated dated claims report with explicit company rounding and sale/payment inputs. */
    public SSCustomerclaimPrinter(SSNewCompany company, SSNewAccountingYear year,
            List<SSInvoice> invoices, List<SSCreditInvoice> credits, List<SSInpayment> payments,
            LocalDate date) {
        super(company, year);
        explicitBalances = new SSCustomerBalanceMath(company.isRoundingOff(), payments, credits, date);
        iSaldos = new HashMap<>();
        for (SSInvoice invoice : invoices) {
            if (SSCustomerBalanceMath.included(invoice, date)) {
                iSaldos.put(invoice, explicitBalances.saldo(invoice).setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        configureReport(date);
    }


    /**
     *
     * @param iDate
     */
    public SSCustomerclaimPrinter(LocalDate iDate) {
        this(iDate, SSDB.getInstance().getInvoices());
    }

    /**
     *
     * @param iDate
     * @param iInvoices
     */
    public SSCustomerclaimPrinter(LocalDate iDate, List<SSInvoice> iInvoices) {
        iSaldos = SSInvoiceMath.getSaldo(iInvoices, iDate);

        configureReport(iDate);
    }

    private void configureReport(LocalDate iDate) {
        setPageHeader("header_period.jrxml");
        setColumnHeader("customerclaim.jrxml");
        setDetail("customerclaim.jrxml");
        setSummary("customerclaim.jrxml");

        addParameter("periodTitle", iBundle.getString("customerclaimreport.periodtitle"));
        addParameter("periodText", iDate);
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return iBundle.getString("customerclaimreport.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        // Get all invoices
        List<SSInvoice> iInvoices = new LinkedList<>(iSaldos.keySet());

        // Sort the invoices
        Collections.sort(iInvoices, (o1, o2) -> o1.getNumber() - o2.getNumber());

        SSDefaultTableModel<SSInvoice> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSInvoice iInvoice = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iInvoice.getNumber();
                    break;

                case 1:
                    value = iInvoice.getCustomerNr();
                    break;

                case 2:
                    value = iInvoice.getCustomerName();
                    break;

                case 3:
                    value = iInvoice.getLocalDate() == null
                            ? null
                            : iInvoice.getLocalDate().format(iFormat);
                    break;

                case 4:
                    value = iInvoice.getCurrency() == null
                            ? null
                            : iInvoice.getCurrency().getName();
                    break;

                case 5:
                    value = explicitBalances == null ? SSInvoiceMath.getTotalSum(iInvoice)
                            : explicitBalances.total(iInvoice);
                    break;

                case 6:
                    value = iSaldos.get(iInvoice);
                    break;

                case 7:
                    BigDecimal iSaldo = iSaldos.get(iInvoice);

                    value = SSInvoiceMath.convertToLocal(iInvoice, iSaldo);
                    break;

                }

                return value;
            }
        };

        iModel.addColumn("invoice.number");

        iModel.addColumn("customer.number");
        iModel.addColumn("customer.name");

        iModel.addColumn("invoice.date");
        iModel.addColumn("invoice.currency");
        iModel.addColumn("invoice.sum");
        iModel.addColumn("invoice.saldo");
        iModel.addColumn("invoice.localsaldo");

        iModel.setObjects(iInvoices);

        return iModel;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSCustomerclaimPrinter");
        sb.append("{iSaldos=").append(iSaldos);
        sb.append('}');
        return sb.toString();
    }
}
