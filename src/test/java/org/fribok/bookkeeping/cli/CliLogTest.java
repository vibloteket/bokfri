package org.fribok.bookkeeping.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesFullUnexpectedFailureDiagnosticBelowEffectiveDataDirectory() throws Exception {
        RuntimeException error = new RuntimeException("outer", new IllegalStateException("inner"));

        String diagnostic = CliLog.diagnostic(temporaryDirectory,
                new String[] {"voucher", "show", "3"}, error);
        CliLog.append(temporaryDirectory, diagnostic);

        Path logFile = temporaryDirectory.resolve("logs/bokfri-cli.log");
        assertThat(CliLog.file(temporaryDirectory)).isEqualTo(logFile.toAbsolutePath().normalize());
        assertThat(Files.readString(logFile))
                .contains("Command: bokfri voucher show 3")
                .contains("RuntimeException: outer")
                .contains("Caused by: java.lang.IllegalStateException: inner");
    }
}
