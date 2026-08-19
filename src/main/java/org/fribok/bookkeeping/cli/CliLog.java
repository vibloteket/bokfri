package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.app.Version;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/** Writes diagnostics for unexpected CLI failures without polluting structured stderr. */
final class CliLog {
    private CliLog() {}

    static Path file(Path dataDirectory) {
        return dataDirectory.resolve("logs").resolve("bokfri-cli.log").toAbsolutePath().normalize();
    }

    static String diagnostic(Path dataDirectory, String[] arguments, Throwable error) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        return "Bokfri: " + Version.APP_VERSION + "\n"
                + "Build: " + Version.APP_BUILD + "\n"
                + "Time: " + OffsetDateTime.now() + "\n"
                + "Operating system: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n"
                + "Java: " + System.getProperty("java.version") + "\n"
                + "Command: bokfri " + String.join(" ", arguments) + "\n"
                + "Data directory: " + dataDirectory + "\n"
                + "Log file: " + file(dataDirectory) + "\n\n"
                + stackTrace;
    }

    static void append(Path dataDirectory, String diagnostic) throws IOException {
        Path logFile = file(dataDirectory);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "\n=== Unexpected CLI error ===\n" + diagnostic,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
