package se.swedsoft.bookkeeping.print.report;


import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Date: 2006-mar-02
 * Time: 16:06:12
 */
public class SSVoucherPrinter extends SSPrinter {

    private SSNewCompany explicitCompany;
    private SSNewAccountingYear explicitYear;
    private List<SSAccount> explicitAccounts;
    private Set<String> explicitProjects;
    private Set<String> explicitResultUnits;

    private String iTitle;

    private SSVoucher iVoucher;

    private List<SSAccount> iMarkedAccounts;

    private SSVoucherRowPrinter iPrinter;

    private SSDefaultJasperDataSource iDataSource;

    /**
     *
     * @param pVoucher
     * @param pTitle
     */
    public SSVoucherPrinter(SSVoucher pVoucher, String pTitle) {
        initialize(pVoucher, pTitle);
    }

    /**
     * Creates a voucher report with explicit headers and row lookups, without legacy storage access.
     *
     * @param voucher voucher snapshot
     * @param title report title
     * @param company selected company
     * @param year hydrated year with accounts and vouchers
     * @param projects company project register
     * @param resultUnits company result-unit register
     */
    public SSVoucherPrinter(SSVoucher voucher, String title, SSNewCompany company, SSNewAccountingYear year,
            List<SSNewProject> projects, List<SSNewResultUnit> resultUnits) {
        super(company, year);
        explicitCompany = company;
        explicitYear = year;
        explicitAccounts = List.copyOf(year.getAccounts());
        explicitProjects = projects.stream().map(SSNewProject::getNumber).collect(Collectors.toSet());
        explicitResultUnits = resultUnits.stream().map(SSNewResultUnit::getNumber).collect(Collectors.toSet());
        initialize(voucher, title);
    }

    private SSAccount account(SSVoucherRow row) {
        return explicitCompany == null ? row.getAccount() : explicitAccounts.stream()
                .filter(account -> Objects.equals(account.getNumber(), row.getAccountNr())).findFirst().orElse(null);
    }

    private void initialize(SSVoucher pVoucher, String pTitle) {
        iVoucher = pVoucher;
        iTitle = pTitle;
        iMarkedAccounts = Collections.emptyList();

        setPageHeader("header.jrxml");
        setDetail("voucher.jrxml");
        setColumnHeader("voucher.jrxml");
    }

    /**
     *
     * @param pVoucher
     * @param pTitle
     * @param pMarkedAccounts
     */
    public SSVoucherPrinter(SSVoucher pVoucher, String pTitle, SSAccount... pMarkedAccounts) {
        this(pVoucher, pTitle);

        iMarkedAccounts = new LinkedList<>();
        iMarkedAccounts.addAll(Arrays.asList(pMarkedAccounts));
    }

    /**
     *
     * @param pVoucher
     * @param pTitle
     * @param pMarkedAccounts
     */
    public SSVoucherPrinter(SSVoucher pVoucher, String pTitle, List<SSAccount> pMarkedAccounts) {
        this(pVoucher, pTitle);
        iMarkedAccounts = pMarkedAccounts;
    }

    /**
     * Gets the title file for this repport
     *
     * @return
     */
    @Override
    public String getTitle() {
        return iTitle;
    }

    /**
     * @return SSDefaultTableModel
     */
    @Override
    protected SSDefaultTableModel getModel() {
        iPrinter = explicitCompany == null ? new SSVoucherRowPrinter()
                : new SSVoucherRowPrinter(explicitCompany, explicitYear);
        iPrinter.generateReport();

        addParameter("Report", iPrinter.getReport());
        addParameter("Parameters", iPrinter.getParameters());

        iDataSource = new SSDefaultJasperDataSource(iPrinter.getModel());
        SSDefaultTableModel<SSVoucher> iModel = new SSDefaultTableModel<>() {

            DateTimeFormatter iLocalDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

            @Override
            public Class<?> getType() {
                return SSInvoice.class;
            }

            public Object getValueAt(int rowIndex, int columnIndex) {
                Object value = null;

                SSVoucher iVoucher = getObject(rowIndex);

                switch (columnIndex) {
                case 0:
                    value = iVoucher.getNumber();
                    break;

                case 1:
                    value = iVoucher.getLocalDate() == null
                            ? null
                            : iVoucher.getLocalDate().format(iLocalDateFormat);
                    break;

                case 2:
                    value = iVoucher.getDescription();
                    break;

                case 3:
                    value = explicitCompany == null ? SSVoucherMath.getDebetSum(iVoucher)
                            : SSVoucherMath.getDebetSum(iVoucher, explicitAccounts);
                    break;

                case 4:
                    value = explicitCompany == null ? SSVoucherMath.getCreditSum(iVoucher)
                            : SSVoucherMath.getCreditSum(iVoucher, explicitAccounts);
                    break;

                case 5:
                    iPrinter.setRows(iVoucher.getRows());

                    iDataSource.reset();

                    value = iDataSource;
                    break;
                }

                return value;
            }
        };

        iModel.addColumn("voucher.number");
        iModel.addColumn("voucher.date");
        iModel.addColumn("voucher.description");
        iModel.addColumn("voucher.debet");
        iModel.addColumn("voucher.credet");

        iModel.addColumn("voucher.rows");

        iModel.setObjects(iVoucher);

        return iModel;

        // return iModel;
    }

    private class SSVoucherRowPrinter extends SSPrinter {

        private SSDefaultTableModel<SSVoucherRow> iModel;

        /**
         *
         */
        public SSVoucherRowPrinter() {
            initialize();
        }

        private SSVoucherRowPrinter(SSNewCompany company, SSNewAccountingYear year) {
            super(company, year);
            initialize();
        }

        private void initialize() {
            setMargins(0, 0, 0, 0);

            setDetail("voucher.rows.jrxml");
            setSummary("voucher.rows.jrxml");

            iModel = new SSDefaultTableModel<>() {

                @Override
                public Class<?> getType() {
                    return SSVoucherRow.class;
                }

                public Object getValueAt(int rowIndex, int columnIndex) {
                    Object value = null;

                    SSVoucherRow iRow = getObject(rowIndex);

                    switch (columnIndex) {
                    case 0:
                        value = account(iRow) == null ? null : account(iRow).getNumber();
                        break;

                    case 1:
                        value = account(iRow) == null ? null : account(iRow).getDescription();
                        break;

                    case 2:
                        value = iRow.getDebet();
                        break;

                    case 3:
                        value = iRow.getCredit();
                        break;

                    case 4:
                        value = explicitCompany == null
                                ? (iRow.getProject() == null ? null : iRow.getProject().getNumber())
                                : (explicitProjects.contains(iRow.getProjectNr()) ? iRow.getProjectNr() : null);
                        break;

                    case 5:
                        value = explicitCompany == null
                                ? (iRow.getResultUnit() == null ? null : iRow.getResultUnit().getNumber())
                                : (explicitResultUnits.contains(iRow.getResultUnitNr()) ? iRow.getResultUnitNr() : null);
                        break;

                    case 6:
                        value = iRow.isAdded();
                        break;

                    case 7:
                        value = iRow.isCrossed();
                        break;

                    case 8:
                        value = iMarkedAccounts.contains(account(iRow));
                        break;

                    }

                    return value;
                }
            };

            iModel.addColumn("voucherrow.account");
            iModel.addColumn("voucherrow.description");
            iModel.addColumn("voucherrow.debet");
            iModel.addColumn("voucherrow.credet");
            iModel.addColumn("voucherrow.project");
            iModel.addColumn("voucherrow.resultunit");
            iModel.addColumn("voucherrow.isadded");
            iModel.addColumn("voucherrow.iscrossed");
            iModel.addColumn("voucherrow.ismarked");
        }

        /**
         * Gets the data model for this report
         *
         * @return SSDefaultTableModel
         */
        @Override
        protected SSDefaultTableModel getModel() {
            return iModel;
        }

        /**
         * Gets the title for this report
         *
         * @return The title
         */
        @Override
        public String getTitle() {
            return null;
        }

        /**
         *
         * @param iRows
         */
        public void setRows(List<SSVoucherRow> iRows) {

            iModel.setObjects(iRows);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.print.report.SSVoucherPrinter.SSVoucherRowPrinter");
            sb.append("{iModel=").append(iModel);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.report.SSVoucherPrinter");
        sb.append("{iDataSource=").append(iDataSource);
        sb.append(", iMarkedAccounts=").append(iMarkedAccounts);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iTitle='").append(iTitle).append('\'');
        sb.append(", iVoucher=").append(iVoucher);
        sb.append('}');
        return sb.toString();
    }
}
