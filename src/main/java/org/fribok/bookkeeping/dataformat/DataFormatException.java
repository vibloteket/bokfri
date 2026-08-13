package org.fribok.bookkeeping.dataformat;

import java.sql.SQLException;

/** Thrown when a database uses a data format that this Bokfri version cannot open safely. */
public final class DataFormatException extends SQLException {
    private final int foundVersion;
    private final int supportedVersion;

    public DataFormatException(int foundVersion, int supportedVersion) {
        super("Database data format " + foundVersion + " is newer than supported format "
                + supportedVersion + ". Install a newer version of Bokfri.");
        this.foundVersion = foundVersion;
        this.supportedVersion = supportedVersion;
    }

    public int getFoundVersion() {
        return foundVersion;
    }

    public int getSupportedVersion() {
        return supportedVersion;
    }
}
