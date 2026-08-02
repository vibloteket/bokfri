package org.fribok.bookkeeping.cli;

/** Expected command-line failure with a stable machine-readable code. */
public class CliException extends RuntimeException {
    private final String code;

    public CliException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CliException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
