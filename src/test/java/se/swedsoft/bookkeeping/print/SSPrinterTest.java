package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests deterministic report metadata used by visual regression tests. */
class SSPrinterTest {
    @AfterEach
    void clearReportDateOverride() {
        System.clearProperty("bokfri.reportDate");
    }

    @Test
    void reportDateCanBeFixedForDeterministicRendering() {
        System.setProperty("bokfri.reportDate", "2026-03-15");

        assertThat(SSPrinter.reportDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }
}
