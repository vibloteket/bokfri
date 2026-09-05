package org.fribok.bookkeeping.service.spreadsheet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSSupplier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void xlsRoundTripPreservesSupplierStringsAndAddress() throws Exception {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber("L-001");
        supplier.setName("Ångström & Söner AB");
        supplier.setPhone1("08-123 45 67");
        supplier.setPhone2("070-000 00 00");
        supplier.setTelefax("08-765 43 21");
        supplier.setEMail("ekonomi@example.se");
        supplier.setHomepage("https://example.se");
        supplier.setYourContact("Örjan");
        supplier.setRegistrationNumber("556000-0001");
        supplier.setOurCustomerNr("00042");
        supplier.setBankGiro("0123-4567");
        supplier.setPlusGiro("00 12 34-5");
        supplier.getAddress().setName("Godsmottagning");
        supplier.getAddress().setAddress1("Åvägen 1");
        supplier.getAddress().setAddress2("Port B");
        supplier.getAddress().setZipCode("01234");
        supplier.getAddress().setCity("Örebro");
        supplier.getAddress().setCountry("Sverige");
        Path file = temporaryDirectory.resolve("leverantorer.xls");
        SupplierSpreadsheetService service = new SupplierSpreadsheetService();

        service.write(List.of(supplier), file, false);
        List<SSSupplier> imported = service.read(file);

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0xd0, (byte) 0xcf, (byte) 0x11,
                (byte) 0xe0);
        assertThat(imported).singleElement().satisfies(actual -> {
            assertThat(actual.getNumber()).isEqualTo("L-001");
            assertThat(actual.getName()).isEqualTo("Ångström & Söner AB");
            assertThat(actual.getPhone1()).isEqualTo("08-123 45 67");
            assertThat(actual.getEMail()).isEqualTo("ekonomi@example.se");
            assertThat(actual.getOurCustomerNr()).isEqualTo("00042");
            assertThat(actual.getBankgiro()).isEqualTo("0123-4567");
            assertThat(actual.getPlusgiro()).isEqualTo("00 12 34-5");
            assertThat(actual.getAddress().getZipCode()).isEqualTo("01234");
            assertThat(actual.getAddress().getCity()).isEqualTo("Örebro");
        });
    }
}
