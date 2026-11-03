package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSCustomerListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierListPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitPartyRegisterPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void customerFieldsAndSortingPreserveCallerOrderWithoutSingletonHeaders() {
        SSCustomer a = customer("A");
        a.setYourContactPerson("Customer contact");
        a.setRegistrationNumber("123456");
        a.setPhone1("0123");
        a.setTelefax("0456");
        List<SSCustomer> entries = new ArrayList<>(List.of(customer("Z"), a));
        Customers printer = new Customers(entries);
        SSDefaultTableModel model = printer.model();
        assertThat(model.getValueAt(0, 0)).isEqualTo("A");
        assertThat(model.getValueAt(0, 1)).isEqualTo("Party A");
        assertThat(model.getValueAt(0, 2)).isEqualTo("Customer contact");
        assertThat(model.getValueAt(0, 3)).isEqualTo("123456");
        assertThat(model.getValueAt(0, 4)).isEqualTo("0123");
        assertThat(model.getValueAt(0, 5)).isEqualTo("0456");
        assertThat(entries.get(0).getNumber()).isEqualTo("Z");
        assertThat(printer.getParameters()).containsEntry("company", "Explicit party company")
                .containsEntry("lastvoucher", 0);
    }

    @Test
    void supplierFieldsAndSortingPreserveCallerOrderWithoutSingletonHeaders() {
        SSSupplier a = supplier("A");
        a.setPhone1("0123");
        a.setOurCustomerNr("OURS");
        a.setYourContact("Supplier contact");
        List<SSSupplier> entries = new ArrayList<>(List.of(supplier("Z"), a));
        SSDefaultTableModel model = new Suppliers(entries).model();
        assertThat(model.getValueAt(0, 0)).isEqualTo("A");
        assertThat(model.getValueAt(0, 1)).isEqualTo("Party A");
        assertThat(model.getValueAt(0, 2)).isEqualTo("0123");
        assertThat(model.getValueAt(0, 3)).isEqualTo("OURS");
        assertThat(model.getValueAt(0, 4)).isEqualTo("Supplier contact");
        assertThat(entries.get(0).getNumber()).isEqualTo("Z");
    }

    @Test
    void emptyAndPopulatedPdfsRenderForCompaniesWithoutAccountingYears() throws Exception {
        for (boolean populated : List.of(false, true)) {
            for (SSPrinter printer : List.of(new Customers(populated ? List.of(customer("A")) : List.of()),
                    new Suppliers(populated ? List.of(supplier("A")) : List.of()))) {
                ReportService reports = new ReportService();
                Path pdf = reports.exportPdf(reports.render(() -> printer), temporaryDirectory.resolve(
                        printer.getClass().getSimpleName() + populated + ".pdf"), false);
                try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                    assertThat(text).contains("Explicit party company");
                    if (populated) assertThat(text).contains("Party A");
                }
            }
        }
    }

    private static SSCustomer customer(String number) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(number);
        customer.setName("Party " + number);
        return customer;
    }

    private static SSSupplier supplier(String number) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(number);
        supplier.setName("Party " + number);
        return supplier;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit party company");
        return company;
    }

    private static class Customers extends SSCustomerListPrinter {
        Customers(List<SSCustomer> entries) { super(company(), entries); }
        SSDefaultTableModel model() { return getModel(); }
    }

    private static class Suppliers extends SSSupplierListPrinter {
        Suppliers(List<SSSupplier> entries) { super(company(), entries); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
