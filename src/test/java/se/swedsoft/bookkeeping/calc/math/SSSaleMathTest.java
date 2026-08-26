package se.swedsoft.bookkeeping.calc.math;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests monetary precision in sales calculations. */
class SSSaleMathTest {
    @Test
    void taxSumsAreRoundedToWholeOre() {
        SSInvoice invoice = new SSInvoice();
        invoice.setTaxRate1(new BigDecimal("25"));
        invoice.setTaxRate2(new BigDecimal("12"));
        invoice.setTaxRate3(new BigDecimal("6"));
        invoice.getRows().add(row("281.25", SSTaxCode.TAXRATE_1));
        invoice.getRows().add(row("40.01", SSTaxCode.TAXRATE_1));
        invoice.getRows().add(row("99.98", SSTaxCode.TAXRATE_2));
        invoice.getRows().add(row("22.48", SSTaxCode.TAXRATE_3));

        var tax = SSSaleMath.getTaxSum(invoice);

        assertThat(tax.get(SSTaxCode.TAXRATE_1)).isEqualByComparingTo("80.32");
        assertThat(tax.get(SSTaxCode.TAXRATE_2)).isEqualByComparingTo("12.00");
        assertThat(tax.get(SSTaxCode.TAXRATE_3)).isEqualByComparingTo("1.35");
        assertThat(SSSaleMath.getTotalTaxSum(invoice)).isEqualByComparingTo("93.67");
    }

    private static SSSaleRow row(String amount, SSTaxCode taxCode) {
        SSSaleRow row = new SSSaleRow();
        row.setQuantity(BigDecimal.ONE);
        row.setUnitprice(new BigDecimal(amount));
        row.setTaxCode(taxCode);
        return row;
    }
}
