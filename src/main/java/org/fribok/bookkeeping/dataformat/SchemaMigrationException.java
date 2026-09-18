package org.fribok.bookkeeping.dataformat;

import java.sql.SQLException;

/** Reports invalid, changed, missing, or failed schema migrations. */
public final class SchemaMigrationException extends SQLException {
    public SchemaMigrationException(String message) {
        super(message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
