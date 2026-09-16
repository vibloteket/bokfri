package org.fribok.bookkeeping.cli;

/** Invalid CLI input, reported with usage and exit code 2 before business execution. */
final class CliSyntaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient CliContext context;

    CliSyntaxException(CliContext context, String message) {
        super(message);
        this.context = context;
    }

    CliContext context() {
        return context;
    }
}
