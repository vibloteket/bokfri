package se.swedsoft.bookkeeping.print.report;


import org.fribok.bookkeeping.service.SaleValuesData;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSMonth;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;

import java.time.LocalDate;
import java.util.List;


/**
 * Date: 2006-mar-03
 * Time: 15:32:42
 */
public class SSSalevaluesPrinter extends SSPrinter {

    private LocalDate iFrom;
    private LocalDate iTo;

    private List<SSMonth> iMonths;
    private SaleValuesData explicitValues;

    /** Creates a report from explicit company/year and immutable sale-row projections. */
    public SSSalevaluesPrinter(SSNewCompany company, SSNewAccountingYear year,
            SaleValuesData values, LocalDate from, LocalDate to) {
        super(company, year);
        explicitValues = java.util.Objects.requireNonNull(values);
        iFrom = from;
        iTo = to;
        iMonths = SSMonth.splitYearIntoMonths(from, to);
        setPageHeader("header_period.jrxml");
        setColumnHeader("salevalues.jrxml");
        setDetail("salevalues.jrxml");
        setSummary("salevalues.jrxml");
    }

    /**
     *
     * @param iFrom
     * @param iTo

     */
    public SSSalevaluesPrinter(LocalDate iFrom, LocalDate iTo) {
        this.iFrom = iFrom;
        this.iTo = iTo;

        iMonths = SSMonth.splitYearIntoMonths(iFrom, iTo);

        setPageHeader("header_period.jrxml");
        setColumnHeader("salevalues.jrxml");
        setDetail("salevalues.jrxml");
        setSummary("salevalues.jrxml");
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return SSBundle.getBundle().getString("salevalues.title");
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        addParameter("dateFrom", iFrom);
        addParameter("dateTo", iTo);

        SSDefaultTableModel<SSMonth> iModel = new SSDefaultTableModel<>() {

            @Override
            public Class<?> getType() {
                return SSProduct.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSMonth iMonth = getObject(rowIndex);
                SSNewCompany iCurrentCompany = explicitValues == null
                        ? SSDB.getInstance().getCurrentCompany() : null;

                switch (columnIndex) {
                case 0:
                    value = iMonth.toString();
                    break;

                case 1:
                    value = explicitValues == null ? iCurrentCompany.getTenderValueForMonth(iMonth)
                            : SaleValuesData.total(explicitValues.tenders(), iMonth);
                    break;

                case 2:
                    value = explicitValues == null ? iCurrentCompany.getOrderValueForMonth(iMonth)
                            : SaleValuesData.total(explicitValues.orders(), iMonth);
                    break;

                case 3:
                    value = explicitValues == null ? iCurrentCompany.getInvoiceValueForMonth(iMonth)
                            : SaleValuesData.total(explicitValues.invoices(), iMonth)
                                    - SaleValuesData.total(explicitValues.credits(), iMonth);
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("month.name");
        iModel.addColumn("month.tender");
        iModel.addColumn("month.order");
        iModel.addColumn("month.invoice");

        iModel.setObjects(iMonths);

        return iModel;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSSalevaluesPrinter");
        sb.append("{iFrom=").append(iFrom);
        sb.append(", iMonths=").append(iMonths);
        sb.append(", iTo=").append(iTo);
        sb.append('}');
        return sb.toString();
    }
}
