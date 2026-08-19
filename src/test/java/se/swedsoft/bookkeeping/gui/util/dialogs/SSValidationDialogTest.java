package se.swedsoft.bookkeeping.gui.util.dialogs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests validation message presentation without opening Swing dialogs. */
class SSValidationDialogTest {
    @Test
    void formatsEveryProblemAsABullet() {
        String formatted = SSValidationDialog.formatMessages(
                List.of("Giltig kund saknas.", "Förfallodatum saknas."));

        assertThat(formatted).isEqualTo("• Giltig kund saknas."
                + System.lineSeparator() + "• Förfallodatum saknas.");
    }

    @Test
    void addsRowContextWhenAvailable() {
        assertThat(SSValidationDialog.formatIssue(2, "Konto saknas."))
                .isEqualTo("Rad 2: Konto saknas.");
        assertThat(SSValidationDialog.formatIssue(null, "Datum saknas."))
                .isEqualTo("Datum saknas.");
    }

    @Test
    void formatsAnEmptyListAsAnEmptyMessage() {
        assertThat(SSValidationDialog.formatMessages(List.of())).isEmpty();
    }
}
