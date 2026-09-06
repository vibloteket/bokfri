package org.fribok.bookkeeping.service.spreadsheet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSCustomer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void xlsxRoundTripPreservesCustomerStringsAndBothAddresses() throws Exception {
        SSCustomer customer = new SSCustomer();
        customer.setNumber("K-001"); customer.setName("Ångström & Söner AB");
        customer.setPhone1("08-123 45 67"); customer.setPhone2("070-000 00 00");
        customer.setTelefax("08-765 43 21"); customer.setEMail("ekonomi@example.se");
        customer.setYourContactPerson("Örjan"); customer.setRegistrationNumber("556000-0001");
        customer.setBankgiro("0123-4567"); customer.setPlusgiro("00 12 34-5");
        customer.getInvoiceAddress().setName("Ekonomiavdelningen");
        customer.getInvoiceAddress().setAddress1("Åvägen 1");
        customer.getInvoiceAddress().setAddress2("Plan 2");
        customer.getInvoiceAddress().setZipCode("01234");
        customer.getInvoiceAddress().setCity("Örebro");
        customer.getInvoiceAddress().setCountry("Sverige");
        customer.getDeliveryAddress().setName("Godsmottagning");
        customer.getDeliveryAddress().setAddress1("Ängsvägen 2");
        customer.getDeliveryAddress().setAddress2("Port B");
        customer.getDeliveryAddress().setZipCode("00123");
        customer.getDeliveryAddress().setCity("Västerås");
        customer.getDeliveryAddress().setCountry("Sverige");
        Path file = temporaryDirectory.resolve("kunder.xlsx");
        CustomerSpreadsheetService service = new CustomerSpreadsheetService();

        service.write(List.of(customer), file, false);
        List<SSCustomer> imported = service.read(file);

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04);
        assertThat(imported).singleElement().satisfies(actual -> {
            assertThat(actual.getNumber()).isEqualTo("K-001");
            assertThat(actual.getName()).isEqualTo("Ångström & Söner AB");
            assertThat(actual.getBankgiro()).isEqualTo("0123-4567");
            assertThat(actual.getInvoiceAddress().getZipCode()).isEqualTo("01234");
            assertThat(actual.getInvoiceAddress().getCity()).isEqualTo("Örebro");
            assertThat(actual.getDeliveryAddress().getZipCode()).isEqualTo("00123");
            assertThat(actual.getDeliveryAddress().getCity()).isEqualTo("Västerås");
        });
    }
}
