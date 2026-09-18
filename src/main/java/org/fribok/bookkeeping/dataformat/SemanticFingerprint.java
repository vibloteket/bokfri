package org.fribok.bookkeeping.dataformat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic aggregate and per-domain hashes with actionable accounting control values. */
public record SemanticFingerprint(String sha256, Map<String, DomainFingerprint> domains) {
    public SemanticFingerprint {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(domains, "domains");
        domains = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(domains));
    }

    /** Hash, record count, and optional exact debit/credit controls for one domain. */
    public record DomainFingerprint(String sha256, long recordCount,
                                    String debitTotal, String creditTotal) {
        public DomainFingerprint {
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** Returns all differences in stable domain order rather than only a bare hash mismatch. */
    public List<String> differences(SemanticFingerprint other) {
        Objects.requireNonNull(other, "other");
        var keys = new java.util.TreeSet<String>();
        keys.addAll(domains.keySet());
        keys.addAll(other.domains.keySet());
        var differences = new java.util.ArrayList<String>();
        for (String key : keys) {
            DomainFingerprint expected = domains.get(key);
            DomainFingerprint actual = other.domains.get(key);
            if (!Objects.equals(expected, actual)) {
                differences.add(key + ": expected " + expected + ", actual " + actual);
            }
        }
        if (differences.isEmpty() && !sha256.equals(other.sha256)) {
            differences.add("aggregate: expected " + sha256 + ", actual " + other.sha256);
        }
        return List.copyOf(differences);
    }
}
