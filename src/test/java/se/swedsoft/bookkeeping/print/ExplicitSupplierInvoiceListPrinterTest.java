package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSSupplierCreditInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierInvoiceListPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitSupplierInvoiceListPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void invoiceSnapshotTotalsRowsAndSortingDoNotReadSingletonOrMutateInputLists() throws Exception {
        SSSupplierInvoice first = sale(new SSSupplierInvoice(), 1);
        SSSupplierInvoice later = sale(new SSSupplierInvoice(), 2);
        List<SSSupplierInvoice> invoices = new ArrayList<>(List.of(later, first));
        Invoices printer = new Invoices(company(true), invoices);
        verify(printer.model());
        assertThat(invoices.get(0)).isSameAs(later);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit invoice register company")
                .containsEntry("lastvoucher", 0);
    }

    @Test
    void creditSnapshotTotalsRowsAndSortingAreAlsoExplicit() throws Exception {
        SSSupplierCreditInvoice first = sale(new SSSupplierCreditInvoice(), 1);
        SSSupplierCreditInvoice later = sale(new SSSupplierCreditInvoice(), 2);
        List<SSSupplierCreditInvoice> invoices = new ArrayList<>(List.of(later, first));
        verify(new Credits(company(true), invoices).model());
        assertThat(invoices.get(0)).isSameAs(later);
    }

    private static void verify(SSDefaultTableModel model) throws Exception {
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat(model.getValueAt(0, 2)).isEqualTo("Supplier snapshot");
        assertThat(model.getValueAt(0, 3)).isNull();
        assertThat(model.getValueAt(0, 4)).isEqualTo("EUR");
        assertThat((BigDecimal) model.getValueAt(0, 5)).isEqualByComparingTo("225.125");
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("25.03");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("450.250");
        assertThat((BigDecimal) model.getValueAt(0, 9)).isEqualByComparingTo("50.06");
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 7);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "row.number", String.class)).isEqualTo("SNAPSHOT");
        assertThat(field(rows, "row.description", String.class)).isEqualTo("Stored row description");
        assertThat(field(rows, "row.unit", String.class)).isEqualTo("st");
        assertThat((BigDecimal) field(rows, "row.sum", BigDecimal.class)).isEqualByComparingTo("200.250");
    }

    @Test
    void storedSupplierRoundingAndLegacyNullRateBehaviorArePreserved() {
        SSSupplierInvoice invoice = sale(new SSSupplierInvoice(), 1);
        assertThat((BigDecimal) new Invoices(company(false), List.of(invoice)).model().getValueAt(0, 5))
                .isEqualByComparingTo("225.125");
        assertThat((BigDecimal) new Credits(company(false), List.of(sale(new SSSupplierCreditInvoice(), 1)))
                .model().getValueAt(0, 8)).isEqualByComparingTo("450.250");
        invoice.setCurrencyRate(null);
        SSDefaultTableModel model = new Invoices(company(true), List.of(invoice)).model();
        assertThat((BigDecimal) model.getValueAt(0, 5)).isEqualByComparingTo("225.125");
        // Unlike journal reports, the legacy list multiplies directly and does not default a missing rate.
        assertThatThrownBy(() -> model.getValueAt(0, 8)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyAndPopulatedRegistersRenderWithoutAccountingYearOrLegacyHeaders() throws Exception {
        for (boolean populated : List.of(false, true)) {
            for (SSPrinter printer : List.of(
                    new Invoices(company(true), populated ? List.of(sale(new SSSupplierInvoice(), 1)) : List.of()),
                    new Credits(company(true), populated ? List.of(sale(new SSSupplierCreditInvoice(), 1)) : List.of()))) {
                ReportService reports = new ReportService();
                Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit invoice register company");
                    if (populated) assertThat(text).contains("Stored row description", "Supplier snapshot");
                }
            }
        }
    }

    private static <T extends SSSupplierInvoice> T sale(T invoice, int number) {
        invoice.setNumber(number);
        invoice.setLocalDate(null);
        invoice.setSupplierNr("C1");
        invoice.setSupplierName("Supplier snapshot");
        invoice.setCurrency(new SSCurrency("EUR", "Euro"));
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxSum(new BigDecimal("25.03"));
        invoice.setRoundingSum(new BigDecimal("-0.155"));
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
        };
        row.setProductNr("SNAPSHOT");
        row.setDescription("Stored row description");
        row.setUnit(new SSUnit("st", "Styck"));
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.125"));
        invoice.getRows().add(row);
        invoice.getRows().add(new SSSupplierInvoiceRow());
        return invoice;
    }

    private static Object field(JRDataSource rows, String name, Class<?> type) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName(name);
        field.setValueClass(type);
        return rows.getFieldValue(field);
    }

    private static SSNewCompany company(boolean rounding) {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit invoice register company");
        company.setRoundingOff(rounding);
        return company;
    }

    private static class Invoices extends SSSupplierInvoiceListPrinter {
        Invoices(SSNewCompany company, List<SSSupplierInvoice> invoices) { super(company, invoices); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Credits extends SSSupplierCreditInvoiceListPrinter {
        Credits(SSNewCompany company, List<SSSupplierCreditInvoice> invoices) { super(company, invoices); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
