package org.fribok.bookkeeping.cli;

/** Expected command-line failure with a stable machine-readable code. */
public class CliException extends RuntimeException {
    private final String code;
    private final Object details;

    public CliException(String code, String message) {
        this(code, message, null, null);
    }

    public CliException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public CliException(String code, String message, Object details) {
        this(code, message, details, null);
    }

    private CliException(String code, String message, Object details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
