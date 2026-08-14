package se.swedsoft.bookkeeping.calc.math;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for decimal service quantities in stock calculations. */
class SSInvoiceMathDecimalTest {

    @Test
    void stockCalculationIgnoresFractionalServiceQuantity() {
        SSProduct service = new SSProduct();
        service.setNumber("SERVICE-1");
        service.setStockProduct(false);

        SSSaleRow row = new SSSaleRow();
        row.setProduct(service);
        row.setQuantity(new BigDecimal("0.5"));
        SSInvoice invoice = new SSInvoice();
        invoice.setRows(List.of(row));

        assertThat(SSInvoiceMath.getStockInfluencing(List.of(invoice))).isEmpty();
        assertThat(SSSaleMath.getProductCount(invoice, service)).isZero();
    }
}
