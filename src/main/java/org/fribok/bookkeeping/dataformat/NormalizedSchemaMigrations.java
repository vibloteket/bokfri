package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.util.List;

/** Immutable migration catalogue for a new normalized staging catalog. */
public final class NormalizedSchemaMigrations {
    private static final List<String> RESOURCES = List.of(
            "/db/normalized/V1__create_accounting_core.sql",
            "/db/normalized/V2__create_shared_lookups.sql",
            "/db/normalized/V3__expand_company.sql",
            "/db/normalized/V4__create_accounting_dimensions.sql",
            "/db/normalized/V5__create_accounting_templates.sql",
            "/db/normalized/V6__create_customer_register.sql",
            "/db/normalized/V7__create_supplier_register.sql");

    private NormalizedSchemaMigrations() {}

    /** Loads every packaged migration in version order and computes its resource checksum. */
    public static List<SchemaMigration> load() throws IOException {
        var migrations = new java.util.ArrayList<SchemaMigration>();
        for (String resource : RESOURCES) {
            migrations.add(SchemaMigrationRunner.loadResource(
                    NormalizedSchemaMigrations.class, resource));
        }
        return List.copyOf(migrations);
    }
}
