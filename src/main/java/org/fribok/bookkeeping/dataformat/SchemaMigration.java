package org.fribok.bookkeeping.dataformat;

import java.util.Objects;

/** Immutable, checksummed SQL schema migration resource. */
public record SchemaMigration(int version, String description, String scriptName,
                              String scriptSha256, String sql) {
    public SchemaMigration {
        if (version < 1) {
            throw new IllegalArgumentException("Migration version must be positive");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(scriptSha256, "scriptSha256");
        Objects.requireNonNull(sql, "sql");
    }
}
