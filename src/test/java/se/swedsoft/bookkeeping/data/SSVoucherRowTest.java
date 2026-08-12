package se.swedsoft.bookkeeping.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SSVoucherRow}.
 * Only tests methods that do NOT depend on SSDB.
 */
class SSVoucherRowTest {

    // ---- Default constructor ----

    @Test
    void defaultConstructorCreatesEmptyRow() {
        SSVoucherRow row = new SSVoucherRow();

        assertThat(row.getDebet()).isNull();
        assertThat(row.getCredit()).isNull();
        assertThat(row.getAccountNr()).isNull();
        assertThat(row.getProjectNr()).isNull();
        assertThat(row.getResultUnitNr()).isNull();
    }

    // ---- Constructor with account, debet, credit ----

    @Test
    void threeArgConstructorSetsFields() {
        SSAccount account = new SSAccount(1000);
        BigDecimal debet = new BigDecimal("100.00");
        BigDecimal credit = new BigDecimal("50.00");

        SSVoucherRow row = new SSVoucherRow(account, debet, credit);

        assertThat(row.getAccountNr()).isEqualTo(1000);
        assertThat(row.getDebet()).isEqualByComparingTo("100.00");
        assertThat(row.getCredit()).isEqualByComparingTo("50.00");
    }

    // ---- Copy constructor ----

    @Test
    void copyConstructorCopiesAllFields() {
        SSVoucherRow original = new SSVoucherRow();
        original.setAccountNr(1000);
        original.setDebet(new BigDecimal("200.00"));
        original.setCredit(new BigDecimal("100.00"));
        original.setCrossed(true);
        original.setAdded(true);

        SSVoucherRow copy = new SSVoucherRow(original);

        assertThat(copy.getAccountNr()).isEqualTo(1000);
        assertThat(copy.getDebet()).isEqualByComparingTo("200.00");
        assertThat(copy.getCredit()).isEqualByComparingTo("100.00");
        assertThat(copy.isCrossed()).isTrue();
        assertThat(copy.isAdded()).isTrue();
    }

    // ---- Reverse copy constructor ----

    @Test
    void reverseCopyConstructorSwapsDebetAndCredit() {
        SSVoucherRow original = new SSVoucherRow();
        original.setDebet(new BigDecimal("200.00"));
        original.setCredit(new BigDecimal("100.00"));

        SSVoucherRow reversed = new SSVoucherRow(original, true);

        assertThat(reversed.getDebet()).isEqualByComparingTo("100.00");
        assertThat(reversed.getCredit()).isEqualByComparingTo("200.00");
    }

    @Test
    void nonReverseCopyConstructorKeepsDebetAndCredit() {
        SSVoucherRow original = new SSVoucherRow();
        original.setDebet(new BigDecimal("200.00"));
        original.setCredit(new BigDecimal("100.00"));

        SSVoucherRow copy = new SSVoucherRow(original, false);

        assertThat(copy.getDebet()).isEqualByComparingTo("200.00");
        assertThat(copy.getCredit()).isEqualByComparingTo("100.00");
    }

    // ---- setValue / getValue ----

    @Test
    void setValuePositiveSetsDebet() {
        SSVoucherRow row = new SSVoucherRow();
        row.setValue(new BigDecimal("100.00"));

        assertThat(row.getDebet()).isEqualByComparingTo("100.00");
        assertThat(row.getCredit()).isNull();
    }

    @Test
    void setValueNegativeSetsCredit() {
        SSVoucherRow row = new SSVoucherRow();
        row.setValue(new BigDecimal("-100.00"));

        assertThat(row.getDebet()).isNull();
        assertThat(row.getCredit()).isEqualByComparingTo("100.00");
    }

    @Test
    void setValueNullDoesNothing() {
        SSVoucherRow row = new SSVoucherRow();
        row.setDebet(new BigDecimal("50.00"));
        row.setValue(null);

        // Existing value should remain unchanged
        assertThat(row.getDebet()).isEqualByComparingTo("50.00");
    }

    @Test
    void getValueReturnsDebetAsPositive() {
        SSVoucherRow row = new SSVoucherRow();
        row.setDebet(new BigDecimal("100.00"));

        assertThat(row.getValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void getValueReturnsCreditAsNegative() {
        SSVoucherRow row = new SSVoucherRow();
        row.setCredit(new BigDecimal("100.00"));

        assertThat(row.getValue()).isEqualByComparingTo("-100.00");
    }

    @Test
    void getValueReturnsZeroWhenBothNull() {
        SSVoucherRow row = new SSVoucherRow();

        assertThat(row.getValue()).isEqualByComparingTo("0");
    }

    // ---- isEmpty ----

    @Test
    void isEmptyReturnsTrueForDefaultRow() {
        SSVoucherRow row = new SSVoucherRow();

        assertThat(row.isEmpty()).isTrue();
    }

    @Test
    void isEmptyReturnsFalseWhenAccountSet() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);

        assertThat(row.isEmpty()).isFalse();
    }

    @Test
    void isEmptyReturnsFalseWhenDebetSet() {
        SSVoucherRow row = new SSVoucherRow();
        row.setDebet(new BigDecimal("100.00"));

        assertThat(row.isEmpty()).isFalse();
    }

    @Test
    void isEmptyReturnsFalseWhenCreditSet() {
        SSVoucherRow row = new SSVoucherRow();
        row.setCredit(new BigDecimal("100.00"));

        assertThat(row.isEmpty()).isFalse();
    }

    // ---- hasAccount ----

    @Test
    void hasAccountReturnsTrueForMatchingAccount() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);

        SSAccount account = new SSAccount(1000);

        assertThat(row.hasAccount(account)).isTrue();
    }

    @Test
    void hasAccountReturnsFalseForDifferentAccount() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);

        SSAccount account = new SSAccount(2000);

        assertThat(row.hasAccount(account)).isFalse();
    }

    @Test
    void hasAccountReturnsFalseWhenAccountNrIsNull() {
        SSVoucherRow row = new SSVoucherRow();

        SSAccount account = new SSAccount(1000);

        assertThat(row.hasAccount(account)).isFalse();
    }

    // ---- isDebet ----

    @Test
    void isDebetReturnsTrueWhenDebetSet() {
        SSVoucherRow row = new SSVoucherRow();
        row.setDebet(new BigDecimal("100.00"));

        assertThat(row.isDebet()).isTrue();
    }

    @Test
    void isDebetReturnsFalseWhenDebetNotSet() {
        SSVoucherRow row = new SSVoucherRow();

        assertThat(row.isDebet()).isFalse();
    }

    // ---- setAccount ----

    @Test
    void setAccountSetsAccountAndAccountNr() {
        SSVoucherRow row = new SSVoucherRow();
        SSAccount account = new SSAccount(1500);

        row.setAccount(account);

        assertThat(row.getAccountNr()).isEqualTo(1500);
    }

    @Test
    void setAccountNullClearsAccountNr() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);

        row.setAccount(null);

        assertThat(row.getAccountNr()).isNull();
    }

    // ---- setCrossed / setAdded with signature ----

    @Test
    void setCrossedWithSignatureSetsFieldsAndDate() {
        SSVoucherRow row = new SSVoucherRow();

        row.setCrossed("admin");

        assertThat(row.isCrossed()).isTrue();
        assertThat(row.getEditedSignature()).isEqualTo("admin");
        assertThat(row.getLocalEditedDate()).isNotNull();
    }

    @Test
    void setAddedWithSignatureSetsFieldsAndDate() {
        SSVoucherRow row = new SSVoucherRow();

        row.setAdded("admin");

        assertThat(row.isAdded()).isTrue();
        assertThat(row.getEditedSignature()).isEqualTo("admin");
        assertThat(row.getLocalEditedDate()).isNotNull();
    }

    // ---- toString ----

    @Test
    void toStringContainsAccountAndValues() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);
        row.setDebet(new BigDecimal("200.00"));
        row.setCredit(new BigDecimal("100.00"));

        String result = row.toString();

        assertThat(result).contains("1000");
        assertThat(result).contains("200.00");
        assertThat(result).contains("100.00");
    }

    @Test
    void toStringPrependsDashWhenCrossed() {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(1000);
        row.setCrossed(true);

        String result = row.toString();

        assertThat(result).startsWith("-");
    }
}
