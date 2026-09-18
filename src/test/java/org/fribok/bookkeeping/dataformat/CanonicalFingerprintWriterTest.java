package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalFingerprintWriterTest {

    @Test
    void isDeterministicAndDistinguishesTypesNullEmptyScaleAndBoundaries() {
        String first = fullFingerprint();
        assertThat(fullFingerprint()).isEqualTo(first);
        assertThat(hash(writer -> writer.writeString(null)))
                .isNotEqualTo(hash(writer -> writer.writeString("")));
        assertThat(hash(writer -> writer.writeString("1")))
                .isNotEqualTo(hash(writer -> writer.writeInteger(1)));
        assertThat(hash(writer -> writer.writeDecimal(new BigDecimal("1.0"))))
                .isNotEqualTo(hash(writer -> writer.writeDecimal(new BigDecimal("1.00"))));
        assertThat(hash(writer -> writer.writeString("ab").writeString("c")))
                .isNotEqualTo(hash(writer -> writer.writeString("a").writeString("bc")));
        assertThat(hash(writer -> writer.writeBoundary("voucher")))
                .isNotEqualTo(hash(writer -> writer.writeString("voucher")));
    }

    @Test
    void cannotBeUsedAfterFinish() {
        CanonicalFingerprintWriter writer = new CanonicalFingerprintWriter();
        writer.writeString("value").finish();
        assertThatThrownBy(() -> writer.writeString("later"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class);
    }

    private static String fullFingerprint() {
        return new CanonicalFingerprintWriter()
                .writeBoundary("voucher-row")
                .writeString("åäö")
                .writeInteger(42)
                .writeLong(9_000_000_000L)
                .writeDecimal(new BigDecimal("123.4500"))
                .writeBoolean(true)
                .writeDate(LocalDate.of(2026, 9, 18))
                .writeInstant(Instant.parse("2026-09-18T17:30:00.123456Z"))
                .finish();
    }

    private static String hash(java.util.function.Consumer<CanonicalFingerprintWriter> values) {
        CanonicalFingerprintWriter writer = new CanonicalFingerprintWriter();
        values.accept(writer);
        return writer.finish();
    }
}
