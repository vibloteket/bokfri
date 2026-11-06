package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSStandardText;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.sales.SSReminderPrinter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitReminderPrinterTest {
    @TempDir Path temporaryDirectory;
    private static final LocalDate DATE = LocalDate.of(2024, 1, 1);

    @Test
    void explicitBalancesDriveTotalsInterestAndMaximumReminderFeeWithoutSingleton() throws Exception {
        List<SSInvoice> invoices = invoices();
        var printer = new Rows(invoices, company(), balances());
        assertThat(printer.getParameters()).containsEntry("company", "Explicit reminder company")
                .containsEntry("lastvoucher", 0).containsEntry("date", DATE.format(
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)
                                .withLocale(Locale.ENGLISH)))
                .containsEntry("text", "Explicit reminder text").containsEntry("customer.currency", "XTS")
                .containsEntry("customer.invoiceadress.name", "Current customer address");
        decimal(printer, "reminder.saldo.sum", "150");
        decimal(printer, "reminder.interest.sum", "10");
        decimal(printer, "reminder.reminderfee", "212.50");
        printer.rows();
        JRDataSource rows = (JRDataSource) printer.getParameters().get("subreport.datasource");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "invoice.number", String.class)).isEqualTo("OCR-REFERENCE");
        assertThat(field(rows, "invoice.duedays", Integer.class)).isEqualTo(365);
        assertThat(field(rows, "invoice.numreminders", Integer.class)).isEqualTo(3);
        assertThat((BigDecimal) field(rows, "invoice.saldo", BigDecimal.class)).isEqualByComparingTo("100");
        assertThat((BigDecimal) field(rows, "invoice.delayfee", BigDecimal.class)).isEqualByComparingTo("10");
        assertThat(rows.next()).isTrue();
        assertThat(field(rows, "invoice.number", String.class)).isEqualTo("2");
        assertThat(field(rows, "invoice.date", String.class)).isNull();
        assertThat(field(rows, "invoice.duedate", String.class)).isNull();
        assertThat(field(rows, "invoice.duedays", Integer.class)).isEqualTo(0);
        assertThat(field(rows, "invoice.numreminders", Integer.class)).isEqualTo(5);
        assertThat((BigDecimal) field(rows, "invoice.saldo", BigDecimal.class)).isEqualByComparingTo("50");
        assertThat((BigDecimal) field(rows, "invoice.delayfee", BigDecimal.class)).isZero();
        assertThat(invoices.get(0).getNumReminders()).isEqualTo(2);
        assertThat(invoices.get(1).getNumReminders()).isEqualTo(4);
    }

    @Test
    void missingBalanceFailsInsteadOfSilentlyUsingZeroOrGlobalCache() {
        assertThatThrownBy(() -> new Rows(invoices(), company(), Map.of(1, BigDecimal.ONE)))
                .isInstanceOf(NullPointerException.class).hasMessage("Missing invoice balance");
    }

    @Test
    void absentStandardTextAndReminderFeeKeepLegacyFallbacks() {
        SSNewCompany company = company();
        company.setReminderfee(null);
        company.getStandardTexts().clear();
        var printer = new Rows(invoices(), company, balances());
        assertThat(printer.getParameters()).containsEntry("text", "");
        decimal(printer, "reminder.reminderfee", "0");
    }

    @Test
    void nestedPdfUsesExplicitSnapshotsEvenIfCallerChangesCollections() throws Exception {
        List<SSInvoice> invoices = new ArrayList<>(invoices());
        Map<Integer, BigDecimal> balances = new HashMap<>(balances());
        var printer = new Rows(invoices, company(), balances);
        invoices.clear();
        balances.clear();
        ReportService reports = new ReportService();
        Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve("reminder.pdf"), false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("Explicit reminder company", "Current customer address", "Explicit reminder text",
                            "OCR-REFERENCE", "XTS");
        }
    }

    private static void decimal(SSPrinter printer, String name, String expected) {
        assertThat((BigDecimal) printer.getParameters().get(name)).isEqualByComparingTo(expected);
    }

    private static Object field(JRDataSource rows, String name, Class<?> type) throws Exception {
        JRDesignField field = new JRDesignField();
        field.setName(name);
        field.setValueClass(type);
        return rows.getFieldValue(field);
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit reminder company");
        company.setReminderfee(new BigDecimal("42.50"));
        company.getStandardTexts().put(SSStandardText.Reminder, "Explicit reminder text");
        return company;
    }

    private static SSCustomer customer() {
        SSCustomer customer = new SSCustomer() {
            @Override public SSCurrency getInvoiceCurrency() { throw new AssertionError("Implicit currency lookup"); }
        };
        customer.setNumber("C1");
        customer.setInvoiceCurrency(new SSCurrency("XTS", "Test currency"));
        customer.getInvoiceAddress().setName("Current customer address");
        return customer;
    }

    private static List<SSInvoice> invoices() {
        SSInvoice first = new SSInvoice();
        first.setNumber(1);
        first.setOCRNumber("OCR-REFERENCE");
        first.setLocalDate(LocalDate.of(2022, 12, 1));
        first.setLocalDueDate(LocalDate.of(2023, 1, 1));
        first.setDelayInterest(new BigDecimal("10"));
        first.setNumRemainders(2);
        SSInvoice second = new SSInvoice();
        second.setNumber(2);
        second.setLocalDate(null);
        second.setLocalDueDate(null);
        second.setDelayInterest(new BigDecimal("20"));
        second.setNumRemainders(4);
        return List.of(first, second);
    }

    private static Map<Integer, BigDecimal> balances() {
        return Map.of(1, new BigDecimal("100"), 2, new BigDecimal("50"));
    }

    private static class Rows extends SSReminderPrinter {
        Rows(List<SSInvoice> invoices, SSNewCompany company, Map<Integer, BigDecimal> balances) {
            super(invoices, customer(), Locale.ENGLISH, DATE, company, balances);
        }
        void rows() { getModel().getValueAt(0, 0); }
    }
}
