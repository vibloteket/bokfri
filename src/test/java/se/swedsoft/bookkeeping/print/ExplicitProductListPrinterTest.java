package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSStock;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.report.SSProductListPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitProductListPrinterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void stockSnapshotAndParcelLookupsAreExplicitWithoutMutatingInputs() throws Exception {
        SSProduct component = product("A");
        component.setUnit(new SSUnit("st", "Styck"));
        SSProduct parcel = product("P");
        SSProductRow row = new SSProductRow() {
            @Override public SSProduct getProduct(List<SSProduct> products) {
                throw new AssertionError("Implicit parcel lookup or cache mutation");
            }
        };
        row.setProduct("A");
        row.setQuantity(2);
        parcel.setParcelRows(List.of(row));
        List<SSProduct> products = new ArrayList<>(List.of(parcel, component));
        Map<String, Integer> counts = new HashMap<>(Map.of("A", 12));
        SSStock stock = new SSStock(products, counts, Map.of("A", 3), Map.of("A", 5));
        counts.clear();
        assertThat(stock.getQuantity(component)).isEqualTo(12);
        assertThat(stock.getAvaiable(component)).isEqualTo(9);
        assertThat(stock.getOrdered(component)).isEqualTo(5);
        SSDefaultTableModel model = new Probe(products, stock).model();
        assertThat(model.getValueAt(0, 1)).isEqualTo("A");
        assertThat(model.getValueAt(0, 6)).isEqualTo(12);
        JRDataSource rows = (JRDataSource) model.getValueAt(1, 7);
        assertThat(rows.next()).isTrue();
        JRDesignField description = new JRDesignField();
        description.setName("product.description");
        description.setValueClass(String.class);
        assertThat(rows.getFieldValue(description)).isEqualTo("Product A");
        JRDesignField unit = new JRDesignField();
        unit.setName("product.unit");
        unit.setValueClass(String.class);
        assertThat(rows.getFieldValue(unit)).isEqualTo("st");
        assertThat(products.get(0)).isSameAs(parcel);
        assertThat(row.getDescription()).isNull();
    }

    @Test
    void emptyAndPopulatedPdfsRenderWithoutAccountingYearOrSingleton() throws Exception {
        for (List<SSProduct> products : List.of(List.<SSProduct>of(), List.of(product("A")))) {
            SSStock stock = new SSStock(products, Map.of("A", -2), Map.of(), Map.of());
            Probe printer = new Probe(products, stock);
            ReportService reports = new ReportService();
            Path pdf = reports.exportPdf(reports.render(() -> printer),
                    temporaryDirectory.resolve("products-" + products.size() + ".pdf"), false);
            try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                assertThat(text).contains("Explicit products company");
                if (!products.isEmpty()) assertThat(text).contains("Product A", "-2");
            }
        }
    }

    private static SSProduct product(String number) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setDescription("Product " + number);
        product.setStockProduct(true);
        return product;
    }

    private static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName("Explicit products company");
        return company;
    }

    private static class Probe extends SSProductListPrinter {
        Probe(List<SSProduct> products, SSStock stock) { super(company(), products, stock); }
        SSDefaultTableModel model() { return getModel(); }
    }
}
