package se.swedsoft.bookkeeping.gui.util.table;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.gui.util.SSUiMetrics;

import javax.swing.SwingUtilities;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that application tables leave enough room for the active UI font.
 */
class SSTableSizingTest {

    @Test
    void rowHeightUsesCurrentFontMetrics() throws Exception {
        AtomicReference<SSTable> tableReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SSTable table = new SSTable();
            table.setFont(new Font(Font.DIALOG, Font.PLAIN, 24));
            table.updateUI();
            tableReference.set(table);
        });

        SSTable table = tableReference.get();
        assertThat(table.getRowHeight()).isEqualTo(SSUiMetrics.controlHeight(table, 18));
        assertThat(table.getRowHeight()).isGreaterThan(18);
    }
}
