package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSSaleReportPrinter;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit sales reports must not require an active singleton database. */
public class ExplicitSalesReportPrinterTest {
    private static final LocalDate FROM = LocalDate.of(2024, 2, 10);
    private static final LocalDate TO = LocalDate.of(2024, 2, 20);

    @Test
    public void preservesHistoricalCurrencyCreditAndCostSemantics() {
        SSProduct product = product("A", "100", "12");
        SSProduct unused = product("B", "20", "4");
        SSInvoice invoice = new SSInvoice();
        invoice.setLocalDate(FROM);
        invoice.setCurrencyRate(new BigDecimal("2"));
        invoice.getRows().add(row("A", "3", "50"));
        SSCreditInvoice credit = new SSCreditInvoice();
        credit.setLocalDate(TO);
        credit.setCurrencyRate(new BigDecimal("3"));
        credit.getRows().add(row("A", "1", "40"));
        SSInvoice outside = new SSInvoice();
        outside.setLocalDate(TO.plusDays(1));
        outside.getRows().add(row("A", "100", "999"));
        SSSupplierInvoice old = supplier(FROM.minusDays(1), "6", "1");
        SSSupplierInvoice latest = supplier(TO, "10", "2");
        SSSupplierInvoice future = supplier(TO.plusDays(1), "900", "0");
        List<SSProduct> products = new ArrayList<>(List.of(unused, product));
        List<SSSupplierInvoice> suppliers = new ArrayList<>(List.of(old, future, latest));
        Probe printer = new Probe(products, List.of(invoice, outside), List.of(credit), suppliers,
                SSSaleReportPrinter.SortingMode.Product, true);
        SSDefaultTableModel model = printer.model();
        assertEquals("A", model.getValueAt(0, 0));
        decimal("2", model.getValueAt(0, 2));
        decimal("24", model.getValueAt(0, 6)); // latest purchase incl. freight, local currency
        decimal("76", model.getValueAt(0, 7));
        decimal("76", model.getValueAt(0, 8));
        decimal("152", model.getValueAt(0, 9));
        // Historical report subtracts credit row totals without currency conversion.
        decimal("130", model.getValueAt(0, 10));
        decimal("0", model.getValueAt(1, 2));
        decimal("4", model.getValueAt(1, 6));
        decimal("20", model.getValueAt(1, 10));
        assertSame(unused, products.get(0));
        assertSame(old, suppliers.get(0));
    }

    @Test
    public void retainsSortingModesAndHistoricalGrossProfitSort() {
        SSProduct a = product("A", "10", "1");
        SSProduct b = product("B", "20", "10");
        SSInvoice invoice = new SSInvoice();
        invoice.setLocalDate(FROM);
        invoice.getRows().add(row("A", "5", "10"));
        invoice.getRows().add(row("B", "1", "20"));
        for (SSSaleReportPrinter.SortingMode mode : SSSaleReportPrinter.SortingMode.values()) {
            for (boolean ascending : List.of(true, false)) {
                SSDefaultTableModel model = new Probe(List.of(b, a), List.of(invoice), List.of(),
                        List.of(), mode, ascending).model();
                // AverageSellingPrice is historically named but sorts unit contribution.
                String firstAscending = mode == SSSaleReportPrinter.SortingMode.Period
                        || mode == SSSaleReportPrinter.SortingMode.ContributionRate ? "B" : "A";
                assertEquals(ascending ? firstAscending
                        : firstAscending.equals("A") ? "B" : "A", model.getValueAt(0, 0), mode.name());
            }
        }
    }

    @Test
    public void parcelCostsAndZeroQuantityFallbackDoNotResolveSingletonProducts() {
        SSProduct component = product("A", "10", "4");
        SSProduct parcel = product("B", "30", "999");
        SSProductRow componentRow = new SSProductRow() {
            @Override public SSProduct getProduct() {
                throw new AssertionError("No parcel singleton lookup allowed");
            }
        };
        componentRow.setProduct("A");
        componentRow.setQuantity(3);
        parcel.setParcelRows(List.of(componentRow));
        SSInvoice invoice = new SSInvoice();
        invoice.setLocalDate(FROM);
        invoice.getRows().add(row("A", "0", "10"));
        invoice.getRows().add(row("UNKNOWN", "99", "10"));
        SSSaleRow missingQuantity = new SSSaleRow();
        missingQuantity.setProductNr("A");
        invoice.getRows().add(missingQuantity);
        // The legacy calculation builds all purchase costs before parcel costs.
        SSDefaultTableModel model = new Probe(List.of(parcel, component), List.of(invoice),
                List.of(), List.of(supplier(TO, "5", "1")),
                SSSaleReportPrinter.SortingMode.Product, true).model();
        decimal("0", model.getValueAt(0, 2));
        decimal("10", model.getValueAt(0, 10));
        decimal("36", model.getValueAt(1, 6));
        decimal("-6", model.getValueAt(1, 7));
        assertEquals(2, model.getRowCount());
    }

    @Test
    public void rendersPdfWithExplicitHeader() throws Exception {
        Probe printer = new Probe(List.of(product("EXPLICIT", "10", "3")), List.of(),
                List.of(), List.of(), SSSaleReportPrinter.SortingMode.Product, true);
        Path file = Files.createTempFile("explicit-sales-", ".pdf");
        try {
            ReportService reports = new ReportService();
            reports.exportPdf(reports.render(() -> printer), file, true);
            try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
                assertTrue(text.contains("Explicit sales company"), text);
                assertTrue(text.contains("EXPLICIT"), text);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static SSProduct product(String number, String price, String cost) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setSellingPrice(new BigDecimal(price));
        product.setStockPrice(new BigDecimal(cost));
        return product;
    }

    private static SSSaleRow row(String number, String quantity, String price) {
        SSSaleRow row = new SSSaleRow() {
            @Override public SSProduct getProduct() {
                throw new AssertionError("No singleton-backed product lookup allowed");
            }
        };
        row.setProductNr(number);
        row.setQuantity(new BigDecimal(quantity));
        row.setUnitprice(new BigDecimal(price));
        return row;
    }

    private static SSSupplierInvoice supplier(LocalDate date, String price, String freight) {
        SSSupplierInvoice invoice = new SSSupplierInvoice();
        invoice.setLocalDate(date);
        invoice.setCurrencyRate(new BigDecimal("2"));
        SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
        row.setProductNr("A");
        row.setUnitprice(new BigDecimal(price));
        row.setUnitFreight(new BigDecimal(freight));
        invoice.getRows().add(row);
        return invoice;
    }

    private static void decimal(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit sales company");
        return company;
    }

    private static SSNewAccountingYear year() {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setVouchers(List.of());
        return year;
    }

    private static class Probe extends SSSaleReportPrinter {
        Probe(List<SSProduct> products, List<SSInvoice> invoices, List<SSCreditInvoice> credits,
                List<SSSupplierInvoice> suppliers, SortingMode mode, boolean ascending) {
            super(company(), year(), products, invoices, credits, suppliers,
                    FROM, TO, mode, ascending);
        }

        SSDefaultTableModel model() { return getModel(); }
    }
}
