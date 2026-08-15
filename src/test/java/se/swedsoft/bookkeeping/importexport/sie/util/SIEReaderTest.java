package se.swedsoft.bookkeeping.importexport.sie.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SIEReader}.
 *
 * SIEReader is constructable from a raw {@code String} or {@code List<String>}
 * with no database or file-system dependencies.
 */
class SIEReaderTest {

    // ---- Single-line constructor token iteration ----

    @Test
    void hasNextReturnsTrueWhenTokensRemain() {
        SIEReader reader = new SIEReader("#KONTO 1000 Kassa");

        assertThat(reader.hasNext()).isTrue();
    }

    @Test
    void hasNextReturnsFalseOnEmptyLine() {
        SIEReader reader = new SIEReader("");

        assertThat(reader.hasNext()).isFalse();
    }

    @Test
    void nextStringReturnsFirstToken() {
        SIEReader reader = new SIEReader("#KONTO 1000 Kassa");

        assertThat(reader.nextString()).isEqualTo("#KONTO");
    }

    @Test
    void nextStringReturnsAllTokensInOrder() {
        SIEReader reader = new SIEReader("#KONTO 1000 Kassa");

        assertThat(reader.nextString()).isEqualTo("#KONTO");
        assertThat(reader.nextString()).isEqualTo("1000");
        assertThat(reader.nextString()).isEqualTo("Kassa");
    }

    @Test
    void quotedStringIsUnwrapped() {
        SIEReader reader = new SIEReader("#FNAMN \"Mitt Företag\"");

        reader.nextString();  // skip label
        assertThat(reader.nextString()).isEqualTo("Mitt Företag");
    }

    @Test
    void hasNextStringReturnsTrueWhenMoreTokens() {
        SIEReader reader = new SIEReader("A B");

        assertThat(reader.hasNextString()).isTrue();
    }

    // ---- Integer parsing ----

    @Test
    void hasNextIntegerReturnsTrueForIntegerToken() {
        SIEReader reader = new SIEReader("1000");

        assertThat(reader.hasNextInteger()).isTrue();
    }

    @Test
    void nextIntegerReturnsCorrectValue() {
        SIEReader reader = new SIEReader("42");

        assertThat(reader.nextInteger()).isPresent().hasValue(42);
    }

    @Test
    void nextIntegerReturnsNullForEmptyToken() {
        // Quoted empty string tokenises as empty string
        SIEReader reader = new SIEReader("\"\"");

        assertThat(reader.nextInteger()).isEmpty();
    }

    // ---- Float / Double parsing ----

    @Test
    void hasNextFloatReturnsTrueForNumericToken() {
        SIEReader reader = new SIEReader("3.14");

        assertThat(reader.hasNextFloat()).isTrue();
    }

    @Test
    void nextDoubleReturnsCorrectValue() {
        SIEReader reader = new SIEReader("1234.56");

        assertThat(reader.nextDouble()).isEqualTo(1234.56);
    }

    // ---- Boolean parsing ----

    @Test
    void hasNextBooleanReturnsTrueForTrueToken() {
        SIEReader reader = new SIEReader("true");

        assertThat(reader.hasNextBoolean()).isTrue();
    }

    @Test
    void nextBooleanReturnsTrueForTrueToken() {
        SIEReader reader = new SIEReader("true");

        assertThat(reader.nextBoolean()).isTrue();
    }

    @Test
    void nextBooleanReturnsFalseForFalseToken() {
        SIEReader reader = new SIEReader("false");

        assertThat(reader.nextBoolean()).isFalse();
    }

    @Test
    void nextBooleanReturnsFalseForNonBooleanToken() {
        SIEReader reader = new SIEReader("yes");

        assertThat(reader.nextBoolean()).isFalse();
    }

    // ---- BigInteger parsing ----

    @Test
    void nextBigIntegerReturnsCorrectValue() {
        SIEReader reader = new SIEReader("99999999999");

        assertThat(reader.nextBigInteger()).isEqualTo(new BigInteger("99999999999"));
    }

    // ---- BigDecimal parsing ----

    @Test
    void nextBigDecimalPreservesExactDecimalValue() {
        SIEReader reader = new SIEReader("0.18");

        assertThat(reader.nextBigDecimal()).isEqualByComparingTo(new BigDecimal("0.18"));
    }

    @Test
    void nextBigDecimalPreservesSourcePrecision() {
        SIEReader reader = new SIEReader("1234.567890123456789");

        assertThat(reader.nextBigDecimal().toPlainString()).isEqualTo("1234.567890123456789");
    }

    // ---- Array parsing ----

    @Test
    void hasNextArrayReturnsTrueForArrayToken() {
        SIEReader reader = new SIEReader("{1 Proj1}");

        assertThat(reader.hasNextArray()).isTrue();
    }

    @Test
    void nextArrayReturnsTokensInsideBraces() {
        SIEReader reader = new SIEReader("{1 Proj1}");

        List<String> array = reader.nextArray();
        assertThat(array).containsExactly("1", "Proj1");
    }

    @Test
    void nextArrayReturnsEmptyListForEmptyBraces() {
        SIEReader reader = new SIEReader("{}");

        List<String> array = reader.nextArray();
        assertThat(array).isEmpty();
    }

    // ---- Date parsing ----

    @Test
    void nextDateParsesEightDigitDateString() {
        SIEReader reader = new SIEReader("20060101");

        // Should return a non-null Date without throwing
        assertThat(reader.nextDate()).isNotNull();
    }

    // ---- Multi-line navigation ----

    @Test
    void hasNextLineReturnsTrueWhenMoreLinesExist() {
        SIEReader reader = new SIEReader(Arrays.asList("line1", "line2"));

        assertThat(reader.hasNextLine()).isTrue();
    }

    @Test
    void hasNextLineReturnsFalseForSingleLine() {
        SIEReader reader = new SIEReader(Arrays.asList("only line"));

        assertThat(reader.hasNextLine()).isFalse();
    }

    @Test
    void nextLineAdvancesToSecondLine() {
        SIEReader reader = new SIEReader(Arrays.asList("first", "second"));

        reader.nextLine();
        assertThat(reader.nextString()).isEqualTo("second");
    }

    @Test
    void nextLineThrowsWhenNoMoreLines() {
        SIEReader reader = new SIEReader(Arrays.asList("only"));

        assertThatThrownBy(reader::nextLine)
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void peekLineReturnsSameLineWithoutAdvancing() {
        SIEReader reader = new SIEReader(Arrays.asList("line1", "line2"));

        String peeked = reader.peekLine();
        String peekedAgain = reader.peekLine();

        assertThat(peeked).isEqualTo("line1");
        assertThat(peekedAgain).isEqualTo("line1");
    }

    // ---- peek (token-level) ----

    @Test
    void peekReturnsNextTokenWithoutConsuming() {
        SIEReader reader = new SIEReader("A B");

        String peeked = reader.peek().orElse(null);
        String consumed = reader.nextString();

        assertThat(peeked).isEqualTo("A");
        assertThat(consumed).isEqualTo("A");
    }

    // ---- remove ----

    @Test
    void removeThrowsUnsupportedOperationException() {
        SIEReader reader = new SIEReader("A");

        assertThatThrownBy(reader::remove)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- hasFields ----

    @Test
    void hasFieldsReturnsTrueForMatchingTypes() {
        SIEReader reader = new SIEReader(Arrays.asList("#KONTO 1000 Kassa"));

        boolean result = reader.hasFields(
                SIEReader.SIEDataType.STRING,
                SIEReader.SIEDataType.INT,
                SIEReader.SIEDataType.STRING);

        assertThat(result).isTrue();
    }

    @Test
    void hasFieldsReturnsFalseWhenTypeMismatch() {
        SIEReader reader = new SIEReader(Arrays.asList("#KONTO Kassa"));

        // Second token is "Kassa" which does NOT match INT
        boolean result = reader.hasFields(
                SIEReader.SIEDataType.STRING,
                SIEReader.SIEDataType.INT);

        assertThat(result).isFalse();
    }
}
