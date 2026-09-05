package se.swedsoft.bookkeeping.print;

import java.io.IOException;

/** Indicates that a rendered report could not be exported. */
public final class ReportExportException extends IOException {

    /**
     * Creates an export failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying export failure
     */
    public ReportExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
