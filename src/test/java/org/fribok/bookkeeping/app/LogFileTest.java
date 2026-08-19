package org.fribok.bookkeeping.app;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class LogFileTest {
    @Test
    void storesLogBelowApplicationDataDirectory() {
        assertThat(LogFile.directory()).isEqualTo(new File(Path.get(Path.USER_DATA), "logs"));
        assertThat(LogFile.file()).isEqualTo(new File(LogFile.directory(), "bokfri.log"));
    }

    @Test
    void configuresLogbackDirectory() {
        String previous = System.getProperty("bokfri.logDir");
        try {
            LogFile.configure();
            assertThat(System.getProperty("bokfri.logDir"))
                    .isEqualTo(LogFile.directory().getAbsolutePath());
        } finally {
            if (previous == null) {
                System.clearProperty("bokfri.logDir");
            } else {
                System.setProperty("bokfri.logDir", previous);
            }
        }
    }
}
