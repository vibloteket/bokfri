package se.swedsoft.bookkeeping.gui.util.dialogs;

import org.fribok.bookkeeping.app.LogFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SSUnexpectedErrorDialogTest {
    @Test
    void diagnosticTextContainsEnvironmentLogPathAndFullCauseChain() {
        IllegalStateException cause = new IllegalStateException("inner failure");
        RuntimeException error = new RuntimeException("outer failure", cause);

        String diagnostic = SSUnexpectedErrorDialog.diagnosticText("Kunde inte spara", error);

        assertThat(diagnostic)
                .contains("Bokfri:", "Build:", "Operating system:", "Java:")
                .contains("Log file: " + LogFile.file().getAbsolutePath())
                .contains("Message: Kunde inte spara")
                .contains("RuntimeException: outer failure")
                .contains("Caused by: java.lang.IllegalStateException: inner failure");
    }
}
