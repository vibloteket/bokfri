package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticFingerprintBuilderTest {

    @Test
    void aggregatesDomainsInStableNameOrderWithCountsAndTotals() {
        SemanticFingerprint first = build(false);
        SemanticFingerprint reorderedDomains = build(true);

        assertThat(reorderedDomains).isEqualTo(first);
        assertThat(first.domains().keySet()).containsExactly("accounts", "voucher-rows");
        assertThat(first.domains().get("voucher-rows").recordCount()).isEqualTo(2);
        assertThat(first.domains().get("voucher-rows").debitTotal()).isEqualTo("125.00");
        assertThat(first.domains().get("voucher-rows").creditTotal()).isEqualTo("125.00");
    }

    @Test
    void reportsActionableDomainDifferencesInStableOrder() {
        SemanticFingerprint expected = build(false);
        SemanticFingerprintBuilder changed = new SemanticFingerprintBuilder();
        SemanticFingerprintBuilder.DomainWriter accounts = changed.domain("accounts");
        accounts.startRecord("2026/1930").writeInteger(1930).writeString("Bank changed");
        accounts.finish();
        changed.domain("voucher-rows").finish();
        SemanticFingerprint actual = changed.finish();

        assertThat(expected.differences(actual))
                .hasSize(2)
                .allSatisfy(value -> assertThat(value).contains("expected", "actual"));
    }

    @Test
    void requiresRecordBoundaryBeforeValues() {
        SemanticFingerprintBuilder.DomainWriter domain =
                new SemanticFingerprintBuilder().domain("vouchers");
        assertThatThrownBy(() -> domain.writeInteger(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Start a record");
    }

    @Test
    void requiresEachDomainToBeFinishedAndRejectsDuplicateDomains() {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        builder.domain("accounts");
        assertThatThrownBy(builder::finish).hasMessageContaining("accounts");
        assertThatThrownBy(() -> builder.domain("accounts"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SemanticFingerprint build(boolean reverseDomains) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        if (reverseDomains) {
            addRows(builder);
            addAccounts(builder);
        } else {
            addAccounts(builder);
            addRows(builder);
        }
        return builder.finish();
    }

    private static void addAccounts(SemanticFingerprintBuilder builder) {
        SemanticFingerprintBuilder.DomainWriter accounts = builder.domain("accounts");
        accounts.startRecord("2026/1930").writeInteger(1930).writeString("Bank");
        accounts.finish();
    }

    private static void addRows(SemanticFingerprintBuilder builder) {
        SemanticFingerprintBuilder.DomainWriter rows = builder.domain("voucher-rows");
        rows.startRecord("2026/1/1").writeInteger(1930)
                .writeDecimal(new BigDecimal("125.00")).writeDecimal(null);
        rows.addDebit(new BigDecimal("125.00"));
        rows.startRecord("2026/1/2").writeInteger(4010)
                .writeDecimal(null).writeDecimal(new BigDecimal("125.00"));
        rows.addCredit(new BigDecimal("125.00"));
        rows.finish();
    }
}
