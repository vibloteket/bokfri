package org.fribok.bookkeeping.service.supplierinvoice;
import org.junit.jupiter.api.Test; import se.swedsoft.bookkeeping.data.*; import java.math.BigDecimal; import java.time.LocalDate; import java.util.List; import static org.assertj.core.api.Assertions.assertThat;
class SupplierInvoiceValidatorTest {
 @Test void acceptsMinimalInvoice(){SSSupplierInvoice i=new SSSupplierInvoice();i.setSupplierNr("S1");i.setSupplierName("Supplier");i.setLocalDate(LocalDate.now());i.setLocalDueDate(LocalDate.now());SSSupplierInvoiceRow r=new SSSupplierInvoiceRow();r.setDescription("Cost");r.setQuantity(1);r.setUnitprice(BigDecimal.ONE);r.setAccountNr(4000);i.setRows(List.of(r));assertThat(SupplierInvoiceValidator.validate(i).valid()).isTrue();}
 @Test void rejectsMissingFields(){SSSupplierInvoice i=new SSSupplierInvoice();i.setRows(List.of(new SSSupplierInvoiceRow()));assertThat(SupplierInvoiceValidator.validate(i).issues()).isNotEmpty();}
}
