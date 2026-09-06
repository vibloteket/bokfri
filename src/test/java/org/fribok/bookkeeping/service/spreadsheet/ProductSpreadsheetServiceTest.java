package org.fribok.bookkeeping.service.spreadsheet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSStock;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @Test
    void xlsxRoundTripPreservesProductFieldsAndDecimals() throws Exception {
        SSProduct product = new SSProduct();
        product.setNumber("P-001");
        product.setDescription("Räksmörgås åäö");
        product.setSellingPrice(new BigDecimal("1234.56"));
        product.setPurchasePrice(new BigDecimal("789.12"));
        product.setUnitFreight(new BigDecimal("3.45"));
        product.setTaxCode(SSTaxCode.TAXRATE_2);
        product.setUnit(new SSUnit("st", "Styck"));
        product.setWeight(new BigDecimal("1.25"));
        product.setVolume(new BigDecimal("0.125"));
        product.setSupplierNr("L-001");
        product.setSupplierProductNr("00042");
        product.setOrderpoint(7);
        product.setWarehouseLocation("A-01");
        product.setStockPrice(new BigDecimal("700.25"));
        Path file = temporaryDirectory.resolve("produkter.xlsx");
        ProductSpreadsheetService service = new ProductSpreadsheetService();

        service.write(List.of(product), new SSStock(), SSDB.getInstance().getCurrentCompany(),
                file, false);
        List<SSProduct> imported = service.read(file, SSDB.getInstance().getCurrentCompany(),
                List.of(new SSUnit("st", "Styck")));

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04);
        assertThat(imported).singleElement().satisfies(actual -> {
            assertThat(actual.getNumber()).isEqualTo("P-001");
            assertThat(actual.getDescription()).isEqualTo("Räksmörgås åäö");
            assertThat(actual.getSellingPrice()).isEqualByComparingTo("1234.56");
            assertThat(actual.getPurchasePrice()).isEqualByComparingTo("789.12");
            assertThat(actual.getUnitFreight()).isEqualByComparingTo("3.45");
            assertThat(actual.getTaxCode()).isEqualTo(SSTaxCode.TAXRATE_2);
            assertThat(actual.getUnit().getName()).isEqualTo("st");
            assertThat(actual.getWeight()).isEqualByComparingTo("1.25");
            assertThat(actual.getVolume()).isEqualByComparingTo("0.125");
            assertThat(actual.getSupplierProductNr()).isEqualTo("00042");
            assertThat(actual.getOrderpoint()).isEqualTo(7);
            assertThat(actual.getWarehouseLocation()).isEqualTo("A-01");
            assertThat(actual.getStockPrice()).isEqualByComparingTo("700.25");
        });
    }
}
