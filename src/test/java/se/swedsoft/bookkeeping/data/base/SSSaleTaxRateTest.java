package se.swedsoft.bookkeeping.data.base;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for default tax rates on sales documents. */
class SSSaleTaxRateTest {

    @Test
    void missingTaxRatesUseSwedishDefaults() {
        SSInvoice invoice = new SSInvoice();

        assertThat(invoice.getTaxRate1()).isEqualByComparingTo("25");
        assertThat(invoice.getTaxRate2()).isEqualByComparingTo("12");
        assertThat(invoice.getTaxRate3()).isEqualByComparingTo("6");
    }
}
