package org.fribok.bookkeeping.dataformat;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/** Writes typed, length-prefixed values into a SHA-256 semantic fingerprint. */
public final class CanonicalFingerprintWriter {
    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte INTEGER = 2;
    private static final byte DECIMAL = 3;
    private static final byte BOOLEAN = 4;
    private static final byte DATE = 5;
    private static final byte INSTANT = 6;
    private static final byte BOUNDARY = 7;

    private final MessageDigest digest;
    private boolean finished;

    public CanonicalFingerprintWriter() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public CanonicalFingerprintWriter writeString(String value) {
        return writeNullable(STRING, value);
    }

    public CanonicalFingerprintWriter writeInteger(Integer value) {
        return writeNullable(INTEGER, value == null ? null : value.toString());
    }

    public CanonicalFingerprintWriter writeLong(Long value) {
        return writeNullable(INTEGER, value == null ? null : value.toString());
    }

    /** Preserves the exact BigDecimal value and scale through {@code toPlainString()}. */
    public CanonicalFingerprintWriter writeDecimal(BigDecimal value) {
        return writeNullable(DECIMAL, value == null ? null : value.toPlainString());
    }

    public CanonicalFingerprintWriter writeBoolean(Boolean value) {
        return writeNullable(BOOLEAN, value == null ? null : value.toString());
    }

    public CanonicalFingerprintWriter writeDate(LocalDate value) {
        return writeNullable(DATE, value == null ? null : value.toString());
    }

    public CanonicalFingerprintWriter writeInstant(Instant value) {
        return writeNullable(INSTANT, value == null ? null : value.toString());
    }

    /** Adds a domain/record boundary, preventing equal field sequences from changing grouping. */
    public CanonicalFingerprintWriter writeBoundary(String boundary) {
        Objects.requireNonNull(boundary, "boundary");
        return writeNullable(BOUNDARY, boundary);
    }

    /** Completes this writer. No values may be added after this call. */
    public String finish() {
        ensureOpen();
        finished = true;
        return HexFormat.of().formatHex(digest.digest());
    }

    private CanonicalFingerprintWriter writeNullable(byte type, String value) {
        ensureOpen();
        if (value == null) {
            digest.update(NULL);
            digest.update(type);
            return this;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(type);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
        return this;
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("Fingerprint writer is already finished");
        }
    }
}
