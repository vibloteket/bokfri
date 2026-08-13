package se.swedsoft.bookkeeping.data.base;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.gui.invoice.util.SSInvoiceRowTableModel;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for decimal quantities on sales rows. */
class SSSaleRowDecimalTest {

    @Test
    void multipliesFractionalQuantityExactly() {
        SSSaleRow row = new SSSaleRow();
        row.setQuantity(new BigDecimal("0.5"));
        row.setUnitprice(new BigDecimal("1000"));

        assertThat(row.getQuantity()).isEqualByComparingTo("0.5");
        assertThat(row.getSum()).isPresent();
        assertThat(row.getSum().orElseThrow()).isEqualByComparingTo("500");
    }

    @Test
    void invoiceQuantityColumnUsesDecimalEditing() {
        assertThat(SSInvoiceRowTableModel.COLUMN_QUANTITY.getColumnClass())
                .isEqualTo(BigDecimal.class);
        assertThat(SSInvoiceRowTableModel.COLUMN_QUANTITY.getCellEditor()).isNotNull();
    }

    @Test
    void normalizesInsignificantTrailingZeros() {
        SSSaleRow row = new SSSaleRow();

        row.setQuantity(new BigDecimal("1.250000"));

        assertThat(row.getQuantity()).isEqualTo(new BigDecimal("1.25"));
    }
}
