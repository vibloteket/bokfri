package org.fribok.bookkeeping.service.outpayment;
import org.junit.jupiter.api.Test; import se.swedsoft.bookkeeping.data.*; import java.math.BigDecimal; import java.time.LocalDate; import java.util.List; import static org.assertj.core.api.Assertions.assertThat;
class OutpaymentValidatorTest {
 @Test void acceptsBookedInvoice(){SSSupplierInvoice i=new SSSupplierInvoice();i.setNumber(1);i.setEntered(true);SSOutpayment p=new SSOutpayment();p.setLocalDate(LocalDate.now());p.setText("Payment");SSOutpaymentRow r=new SSOutpaymentRow();r.setInvoiceNr(1);r.setValue(BigDecimal.ONE);r.setCurrencyRate(BigDecimal.ONE);p.setRows(List.of(r));assertThat(OutpaymentValidator.validate(p,List.of(i)).valid()).isTrue();}
}
