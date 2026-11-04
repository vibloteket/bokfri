package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSInpaymentListPrinter;
import se.swedsoft.bookkeeping.print.report.SSOutpaymentListPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitPaymentListPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void inpaymentTotalsSnapshotsSortingAndNestedRowsNeedNoInvoiceLookups() throws Exception {
        SSInpayment first = inpayment(1);
        SSInpayment later = inpayment(2);
        List<SSInpayment> payments = new ArrayList<>(List.of(later, first));
        Inpayments printer = new Inpayments(payments);
        SSDefaultTableModel model = printer.model();
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 3)).isEqualByComparingTo("22");
        assertThat(model.getValueAt(0, 1)).isNull();
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 4);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "invoice.number", Integer.class)).isEqualTo(99);
        assertThat(field(rows, "invoice.currency", String.class)).isEqualTo("EUR");
        assertThat((BigDecimal) field(rows, "invoice.currencyrate", BigDecimal.class)).isEqualByComparingTo("3");
        assertThat((BigDecimal) field(rows, "inpaymentrow.payed", BigDecimal.class)).isEqualByComparingTo("25");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "inpaymentrow.payed", BigDecimal.class)).isNull();
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "inpaymentrow.value", BigDecimal.class)).isNull();
        assertThat(payments.get(0)).isSameAs(later);
        assertThat(printer.getParameters()).containsEntry("company", "Explicit payment company")
                .containsEntry("lastvoucher", 9);
    }

    @Test
    void outpaymentTotalsSnapshotsSortingAndNestedRowsNeedNoInvoiceLookups() throws Exception {
        SSOutpayment first = outpayment(1);
        SSOutpayment later = outpayment(2);
        List<SSOutpayment> payments = new ArrayList<>(List.of(later, first));
        SSDefaultTableModel model = new Outpayments(payments).model();
        assertThat(model.getValueAt(0, 0)).isEqualTo(1);
        assertThat((BigDecimal) model.getValueAt(0, 3)).isEqualByComparingTo("22");
        JRDataSource rows = (JRDataSource) model.getValueAt(0, 4);
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "invoice.number", Integer.class)).isEqualTo(99);
        assertThat((BigDecimal) field(rows, "row.payed", BigDecimal.class)).isEqualByComparingTo("25");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "row.payed", BigDecimal.class)).isNull();
        assertThat(payments.get(0)).isSameAs(later);
    }

    @Test
    void emptyAndPopulatedPdfsRenderWithExplicitOuterAndNestedHeaders() throws Exception {
        for (boolean populated : List.of(false, true)) {
            for (SSPrinter printer : List.of(new Inpayments(populated ? List.of(inpayment(1)) : List.of()),
                    new Outpayments(populated ? List.of(outpayment(1)) : List.of()))) {
                ReportService reports = new ReportService();
                Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit payment company");
                    if (populated) assertThat(text).contains("EUR", "99");
                }
            }
        }
    }

    private static SSInpayment inpayment(int number) {
        SSInpayment payment = new SSInpayment();
        payment.setNumber(number);
        for (int index = 0; index < 3; index++) {
            SSInpaymentRow row = new SSInpaymentRow();
            row.setInvoiceNr(99);
            row.setInvoiceCurrency(new SSCurrency("EUR", "Euro"));
            row.setInvoiceCurrencyRate(new BigDecimal("3"));
            row.setValue(index == 0 ? new BigDecimal("12.5") : index == 1 ? new BigDecimal("-3") : null);
            row.setCurrencyRate(index == 1 ? null : new BigDecimal("2"));
            payment.getRows().add(row);
        }
        return payment;
    }

    private static SSOutpayment outpayment(int number) {
        SSOutpayment payment = new SSOutpayment();
        payment.setNumber(number);
        for (SSInpaymentRow source : inpayment(number).getRows()) {
            SSOutpaymentRow row = new SSOutpaymentRow();
            row.setInvoiceNr(source.getInvoiceNr());
            row.setInvoiceCurrency(source.getInvoiceCurrency());
            row.setInvoiceCurrencyRate(source.getInvoiceCurrencyRate());
            row.setValue(source.getValue());
            row.setCurrencyRate(source.getCurrencyRate());
            payment.getRows().add(row);
        }
        return payment;
    }

    private static Object field(JRDataSource rows, String name, Class<?> type) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName(name);
        field.setValueClass(type);
        return rows.getFieldValue(field);
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit payment company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        SSVoucher first = new SSVoucher();
        first.setNumber(9);
        SSVoucher second = new SSVoucher();
        second.setNumber(2);
        year.setVouchers(List.of(first, second));
        return year;
    }

    private static class Inpayments extends SSInpaymentListPrinter {
        Inpayments(List<SSInpayment> payments) { super(company(), year(), payments); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Outpayments extends SSOutpaymentListPrinter {
        Outpayments(List<SSOutpayment> payments) { super(company(), year(), payments); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
