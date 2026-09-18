package org.fribok.bookkeeping.dataformat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds a deterministic aggregate from already canonically ordered domain records. */
public final class SemanticFingerprintBuilder {
    private final Map<String, DomainWriter> domains = new TreeMap<>();
    private boolean finished;

    /** Creates a domain writer. Domain names are unique and aggregate in lexical order. */
    public DomainWriter domain(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || domains.containsKey(name)) {
            throw new IllegalArgumentException("Invalid or duplicate fingerprint domain: " + name);
        }
        DomainWriter writer = new DomainWriter(name);
        domains.put(name, writer);
        return writer;
    }

    public SemanticFingerprint finish() {
        ensureOpen();
        CanonicalFingerprintWriter aggregate = new CanonicalFingerprintWriter();
        Map<String, SemanticFingerprint.DomainFingerprint> results = new LinkedHashMap<>();
        for (Map.Entry<String, DomainWriter> entry : domains.entrySet()) {
            DomainWriter writer = entry.getValue();
            if (!writer.finished) {
                throw new IllegalStateException("Fingerprint domain is not finished: " + entry.getKey());
            }
            SemanticFingerprint.DomainFingerprint domain = writer.result;
            results.put(entry.getKey(), domain);
            aggregate.writeBoundary(entry.getKey())
                    .writeString(domain.sha256())
                    .writeLong(domain.recordCount())
                    .writeString(domain.debitTotal())
                    .writeString(domain.creditTotal());
        }
        finished = true;
        return new SemanticFingerprint(aggregate.finish(), results);
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("Semantic fingerprint builder is already finished");
        }
    }

    /** Mutable writer for one domain; callers must supply records in canonical business-key order. */
    public static final class DomainWriter {
        private final String name;
        private final CanonicalFingerprintWriter writer = new CanonicalFingerprintWriter();
        private long records;
        private BigDecimal debit = BigDecimal.ZERO;
        private BigDecimal credit = BigDecimal.ZERO;
        private boolean recordOpen;
        private boolean finished;
        private SemanticFingerprint.DomainFingerprint result;

        private DomainWriter(String name) {
            this.name = name;
            writer.writeBoundary(name);
        }

        /** Begins the next record. Ordering is defined by each domain extractor, not string sorting. */
        public DomainWriter startRecord(String businessKey) {
            ensureOpen();
            Objects.requireNonNull(businessKey, "businessKey");
            records++;
            recordOpen = true;
            writer.writeBoundary("record").writeString(businessKey);
            return this;
        }

        public DomainWriter writeString(String value) {
            ensureRecord();
            writer.writeString(value);
            return this;
        }

        public DomainWriter writeInteger(Integer value) {
            ensureRecord();
            writer.writeInteger(value);
            return this;
        }

        public DomainWriter writeLong(Long value) {
            ensureRecord();
            writer.writeLong(value);
            return this;
        }

        public DomainWriter writeDecimal(BigDecimal value) {
            ensureRecord();
            writer.writeDecimal(value);
            return this;
        }

        public DomainWriter writeBoolean(Boolean value) {
            ensureRecord();
            writer.writeBoolean(value);
            return this;
        }

        public DomainWriter writeDate(LocalDate value) {
            ensureRecord();
            writer.writeDate(value);
            return this;
        }

        public DomainWriter writeInstant(Instant value) {
            ensureRecord();
            writer.writeInstant(value);
            return this;
        }

        public DomainWriter addDebit(BigDecimal value) {
            ensureOpen();
            if (value != null) {
                debit = debit.add(value);
            }
            return this;
        }

        public DomainWriter addCredit(BigDecimal value) {
            ensureOpen();
            if (value != null) {
                credit = credit.add(value);
            }
            return this;
        }

        public SemanticFingerprint.DomainFingerprint finish() {
            ensureOpen();
            finished = true;
            result = new SemanticFingerprint.DomainFingerprint(writer.finish(), records,
                    total(debit), total(credit));
            return result;
        }

        private void ensureRecord() {
            ensureOpen();
            if (!recordOpen) {
                throw new IllegalStateException("Start a record before writing values in " + name);
            }
        }

        private void ensureOpen() {
            if (finished) {
                throw new IllegalStateException("Fingerprint domain is already finished: " + name);
            }
        }

        private static String total(BigDecimal value) {
            return value.signum() == 0 ? "0" : value.toPlainString();
        }
    }
}
