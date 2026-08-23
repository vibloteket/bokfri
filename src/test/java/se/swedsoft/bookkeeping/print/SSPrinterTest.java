package se.swedsoft.bookkeeping.print;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests deterministic report metadata used by visual regression tests. */
class SSPrinterTest {
    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @AfterEach
    void clearReportDateOverride() {
        System.clearProperty("bokfri.reportDate");
    }

    @Test
    void reportDateCanBeFixedForDeterministicRendering() {
        System.setProperty("bokfri.reportDate", "2026-03-15");

        TestPrinter printer = new TestPrinter();

        assertThat(printer.getReportDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    private static final class TestPrinter extends SSPrinter {
        @Override public String getTitle() {
            return "Test";
        }

        @Override protected SSDefaultTableModel<?> getModel() {
            return new SSDefaultTableModel<>() {
                @Override public Class<?> getType() {
                    return String.class;
                }

                @Override public Object getValueAt(int rowIndex, int columnIndex) {
                    return null;
                }
            };
        }

        LocalDate getReportDate() {
            return (LocalDate) getParameters().get("reportdate");
        }
    }
}
