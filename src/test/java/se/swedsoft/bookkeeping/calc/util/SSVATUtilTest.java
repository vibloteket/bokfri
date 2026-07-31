package se.swedsoft.bookkeeping.calc.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SSVATUtilTest {

    @Test
    void roundsAfterSummingVatAccountBalances() {
        BigDecimal rounded = SSVATUtil.getRoundedSettlementTotal(List.of(
                new BigDecimal("5434.25"),
                new BigDecimal("29.75")));

        assertThat(rounded).isEqualByComparingTo("5464");
    }

    @Test
    void normalizesLegacyFloatingPointNoiseBeforeDroppingOre() {
        BigDecimal rounded = SSVATUtil.getRoundedSettlementTotal(List.of(
                new BigDecimal("5434.25"),
                new BigDecimal("29.749999999999996")));

        assertThat(rounded).isEqualByComparingTo("5464");
    }

    @Test
    void dropsOreFromTheCombinedDeclarationTotal() {
        BigDecimal rounded = SSVATUtil.getRoundedSettlementTotal(List.of(
                new BigDecimal("100.75"),
                new BigDecimal("50.50")));

        assertThat(rounded).isEqualByComparingTo("151");
    }
}
