package se.swedsoft.bookkeeping.importexport.sie.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SIEWriter}.
 *
 * SIEWriter is a pure in-memory class with no database or file dependencies.
 */
class SIEWriterTest {

    private SIEWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SIEWriter();
    }

    // ---- append(String) ----

    @Test
    void appendStringWithoutSpaceNoQuotes() {
        writer.append("Hello");

        assertThat(writer.getLine()).isEqualTo("Hello");
    }

    @Test
    void appendStringWithSpaceAddsQuotes() {
        writer.append("Hello World");

        assertThat(writer.getLine()).isEqualTo("\"Hello World\"");
    }

    @Test
    void appendNullStringAppendsNullLiteral() {
        // append(String) with null: pValue.contains(" ") would throw NPE,
        // but the null guard is: if (pValue != null && pValue.contains(" "))
        // So null goes to the else branch: appends null + space.
        writer.append((String) null);

        assertThat(writer.getLine()).isEqualTo("null");
    }

    // ---- append(Object) ----

    @Test
    void appendObjectCallsToString() {
        writer.append(Integer.valueOf(42));

        assertThat(writer.getLine()).isEqualTo("42");
    }

    // ---- append(Integer) ----

    @Test
    void appendIntegerAppendsValue() {
        writer.append(Integer.valueOf(100));

        assertThat(writer.getLine()).isEqualTo("100");
    }

    // ---- append(boolean) ----

    @Test
    void appendBooleanTrueAppendsTrueString() {
        writer.append(true);

        assertThat(writer.getLine()).isEqualTo("true");
    }

    @Test
    void appendBooleanFalseAppendsFalseString() {
        writer.append(false);

        assertThat(writer.getLine()).isEqualTo("false");
    }

    // ---- append(Double) ----

    @Test
    void appendDoubleFormatsTwoDecimalPlaces() {
        writer.append(1234.5);

        assertThat(writer.getLine()).isEqualTo("1234.50");
    }

    @Test
    void appendDoubleUsesPointNotComma() {
        writer.append(9.99);

        assertThat(writer.getLine()).contains(".");
        assertThat(writer.getLine()).doesNotContain(",");
    }

    @Test
    void appendDoubleNoGroupingSeparator() {
        writer.append(1000000.0);

        assertThat(writer.getLine()).doesNotContain(",");
        assertThat(writer.getLine()).doesNotContain(" ");
    }

    // ---- append(Float) ----

    @Test
    void appendFloatDelegatesToDouble() {
        writer.append(Float.valueOf(5.5f));

        // 5.5 → "5.50"
        assertThat(writer.getLine()).isEqualTo("5.50");
    }

    @Test
    void appendNullFloatAppendsZero() {
        writer.append((Float) null);

        assertThat(writer.getLine()).isEqualTo("0");
    }

    // ---- append(BigInteger) ----

    @Test
    void appendBigIntegerAppendsValue() {
        writer.append(new BigInteger("12345678901234567890"));

        assertThat(writer.getLine()).isEqualTo("12345678901234567890");
    }

    // ---- append(BigDecimal) ----

    @Test
    void appendBigDecimalFormatsMoneyWithTwoDecimals() {
        writer.append(new BigDecimal("1234567890.125"));

        assertThat(writer.getLine()).isEqualTo("1234567890.13");
    }

    @Test
    void appendBigDecimalDoesNotIntroduceBinaryFloatingPointNoise() {
        writer.append(new BigDecimal("0.18"));

        assertThat(writer.getLine()).isEqualTo("0.18");
    }

    // ---- append(LocalDate) ----

    @Test
    void appendNullLocalDateAppendsEightZeros() {
        writer.append((LocalDate) null);

        assertThat(writer.getLine()).isEqualTo("00000000");
    }

    @Test
    void appendLocalDateFormatsAsYYYYMMDD() {
        writer.append(LocalDate.of(2006, 1, 1));

        assertThat(writer.getLine()).isEqualTo("20060101");
    }

    // ---- append(SSMonth) ----

    @Test
    void appendNullMonthAppendsSixZeros() {
        writer.append((se.swedsoft.bookkeeping.data.SSMonth) null);

        assertThat(writer.getLine()).isEqualTo("000000");
    }

    // ---- append(Object...) varargs array ----

    @Test
    void appendObjectVarargsWrapInBraces() {
        writer.append(new Object[]{"a", "b"});

        assertThat(writer.getLine()).startsWith("{").contains("a").contains("b").endsWith("}");
    }

    @Test
    void appendObjectVarargsSkipsNulls() {
        writer.append(new Object[]{"x", null, "y"});

        String line = writer.getLine();
        assertThat(line).contains("x").contains("y");
    }

    // ---- append(List<Object>) ----

    @Test
    void appendListWrapsInBraces() {
        @SuppressWarnings("unchecked")
        List<Object> list = Arrays.asList("p", "q");
        writer.append(list);

        String line = writer.getLine();
        assertThat(line).startsWith("{").contains("p").contains("q").endsWith("}");
    }

    // ---- newLine() / getLines() ----

    @Test
    void newLineFlushesBuilderToLines() {
        writer.append("A");
        writer.newLine();

        assertThat(writer.getLines()).hasSize(1);
        assertThat(writer.getLines().get(0)).isEqualTo("A ");
    }

    @Test
    void newLineResetsBuilderAfterFlush() {
        writer.append("A");
        writer.newLine();
        writer.append("B");

        assertThat(writer.getLine()).isEqualTo("B");
    }

    @Test
    void newLineWithStringAddsLiteralLine() {
        writer.newLine("#COMMENT line");

        assertThat(writer.getLines()).containsExactly("#COMMENT line");
    }

    @Test
    void newLineWithStringDoesNotIncludeBuilderContent() {
        writer.append("BEFORE");
        writer.newLine("LITERAL");

        // "BEFORE " was in the builder but newLine(String) replaces, not appends
        assertThat(writer.getLines()).containsExactly("LITERAL");
    }

    // ---- getLine returns trimmed builder content ----

    @Test
    void getLineTrimmed() {
        writer.append("Token");
        // append adds a trailing space; getLine() trims
        assertThat(writer.getLine()).isEqualTo("Token");
    }

    // ---- toString is same as getLine ----

    @Test
    void toStringEqualsGetLine() {
        writer.append("Foo");

        assertThat(writer.toString()).isEqualTo(writer.getLine());
    }

    // ---- append(SIELabel) ----

    @Test
    void appendSIELabelAppendsLabelName() {
        writer.append(SIELabel.SIE_FLAGGA);

        assertThat(writer.getLine()).isEqualTo(SIELabel.SIE_FLAGGA.getName());
    }
}
