package se.swedsoft.bookkeeping.gui.accountingyear;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SSAccountingYearFrameTest {
    @Test
    void sortsAccountingYearsNewestFirst() {
        SSNewAccountingYear oldest = year(1, "2024-01-01", "2024-12-31");
        SSNewAccountingYear newest = year(3, "2026-01-01", "2026-12-31");
        SSNewAccountingYear middle = year(2, "2025-01-01", "2025-12-31");

        assertThat(SSAccountingYearFrame.sortNewestFirst(List.of(oldest, newest, middle)))
                .containsExactly(newest, middle, oldest);
    }

    private static SSNewAccountingYear year(int id, String from, String to) {
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setId(id);
        year.setLocalFrom(LocalDate.parse(from));
        year.setLocalTo(LocalDate.parse(to));
        return year;
    }
}
