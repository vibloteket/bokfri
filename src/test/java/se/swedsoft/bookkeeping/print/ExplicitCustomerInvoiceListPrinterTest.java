package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSCreditInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSInvoiceListPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitCustomerInvoiceListPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void invoiceSnapshotTotalsRowsAndSortingDoNotReadSingletonOrMutateInputLists() throws Exception {
        SSInvoice first = sale(new SSInvoice(), 1);
        SSInvoice later = sale(new SSInvoice(), 2);
        List<SSInvoice> invoices = new ArrayList<>(List.of(later, first));
        Invoices printer = new Invoices(company(true), invoices);
        verify(printer.model());
        assertThat(invoices.get(0)).isSameAs(later);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit invoice register company")
                .containsEntry("lastvoucher", 0);
    }

    @Test
    void creditSnapshotTotalsRowsAndSortingAreAlsoExplicit() throws Exception {
        SSCreditInvoice first = sale(new SSCreditInvoice(), 1);
        SSCreditInvoice later = sale(new SSCreditInvoice(), 2);
        List<SSCreditInvoice> invoices = new ArrayList<>(List.of(later, first));
        verify(new Credits(company(true), invoices).model());
        assertThat(invoices.get(0)).isSameAs(later);
    }

    private static void verify(SSDefaultTableModel model) throws Exception {
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat(model.getValueAt(0, 2)).isEqualTo("Customer snapshot");
        assertThat(model.getValueAt(0, 3)).isNull();
        assertThat(model.getValueAt(0, 5)).isEqualTo("EUR");
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("225.285");
        assertThat((BigDecimal) model.getValueAt(0, 7)).isEqualByComparingTo("45.06");
        assertThat((BigDecimal) model.getValueAt(0, 8)).isEqualByComparingTo("450.570");
        assertThat((BigDecimal) model.getValueAt(0, 9)).isEqualByComparingTo("90.12");
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 4);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "row.number", String.class)).isEqualTo("SNAPSHOT");
        assertThat(field(rows, "row.description", String.class)).isEqualTo("Stored row description");
        assertThat(field(rows, "row.unit", String.class)).isEqualTo("st");
        assertThat((BigDecimal) field(rows, "row.discount", BigDecimal.class)).isEqualByComparingTo("10");
        assertThat((BigDecimal) field(rows, "row.sum", BigDecimal.class)).isEqualByComparingTo("180.225");
    }

    @Test
    void explicitCompanyRoundingAndLegacyNullRateBehaviorArePreserved() {
        SSInvoice invoice = sale(new SSInvoice(), 1);
        assertThat((BigDecimal) new Invoices(company(false), List.of(invoice)).model().getValueAt(0, 6))
                .isEqualByComparingTo("225");
        assertThat((BigDecimal) new Credits(company(false), List.of(sale(new SSCreditInvoice(), 1)))
                .model().getValueAt(0, 8)).isEqualByComparingTo("450");
        invoice.setCurrencyRate(null);
        SSDefaultTableModel model = new Invoices(company(true), List.of(invoice)).model();
        assertThat((BigDecimal) model.getValueAt(0, 6)).isEqualByComparingTo("225.285");
        // Unlike journal reports, the legacy list multiplies directly and does not default a missing rate.
        assertThatThrownBy(() -> model.getValueAt(0, 8)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyAndPopulatedRegistersRenderWithoutAccountingYearOrLegacyHeaders() throws Exception {
        for (boolean populated : List.of(false, true)) {
            for (SSPrinter printer : List.of(
                    new Invoices(company(true), populated ? List.of(sale(new SSInvoice(), 1)) : List.of()),
                    new Credits(company(true), populated ? List.of(sale(new SSCreditInvoice(), 1)) : List.of()))) {
                ReportService reports = new ReportService();
                Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit invoice register company");
                    if (populated) assertThat(text).contains("Stored row description", "Customer snapshot");
                }
            }
        }
    }

    private static <T extends SSInvoice> T sale(T invoice, int number) {
        invoice.setNumber(number);
        invoice.setLocalDate(null);
        invoice.setCustomerNr("C1");
        invoice.setCustomerName("Customer snapshot");
        invoice.setCurrency(new SSCurrency("EUR", "Euro"));
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.setTaxFree(false);
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(BigDecimal.ZERO);
        invoice.setTaxRate3(BigDecimal.ZERO);
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() { throw new AssertionError("Implicit product lookup"); }
        };
        row.setProductNr("SNAPSHOT");
        row.setDescription("Stored row description");
        row.setUnit(new SSUnit("st", "Styck"));
        row.setQuantity(2);
        row.setUnitprice(new BigDecimal("100.125"));
        row.setDiscount(new BigDecimal("10"));
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.getRows().add(row);
        invoice.getRows().add(new SSSaleRow());
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

    private static class Invoices extends SSInvoiceListPrinter {
        Invoices(SSNewCompany company, List<SSInvoice> invoices) { super(company, invoices); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Credits extends SSCreditInvoiceListPrinter {
        Credits(SSNewCompany company, List<SSCreditInvoice> invoices) { super(company, invoices); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
