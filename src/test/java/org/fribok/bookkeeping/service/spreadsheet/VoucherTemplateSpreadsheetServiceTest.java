package org.fribok.bookkeeping.service.spreadsheet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow;

class VoucherTemplateSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void xlsxRoundTripPreservesSwedishTextAccountsAndSides() throws Exception {
        SSVoucherTemplate source = new SSVoucherTemplate();
        source.setDescription("Löner åäö");
        source.getRows().add(row(7010, true));
        source.getRows().add(row(1930, false));
        Path file = temporaryDirectory.resolve("konteringsmallar.xlsx");
        VoucherTemplateSpreadsheetService service = new VoucherTemplateSpreadsheetService();

        service.write(List.of(source), file, false);
        List<SSVoucherTemplate> imported = service.read(file);

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04);
        assertThat(imported).singleElement().satisfies(template -> {
            assertThat(template.getDescription()).isEqualTo("Löner åäö");
            assertThat(template.getRows()).hasSize(2);
            assertThat(template.getRows().get(0).getAccountNr()).isEqualTo(7010);
            assertThat(template.getRows().get(0).getDebet()).isNotNull();
            assertThat(template.getRows().get(0).getCredit()).isNull();
            assertThat(template.getRows().get(1).getAccountNr()).isEqualTo(1930);
            assertThat(template.getRows().get(1).getDebet()).isNull();
            assertThat(template.getRows().get(1).getCredit()).isNotNull();
        });
    }

    private static SSVoucherTemplateRow row(int account, boolean debit) {
        SSVoucherTemplateRow row = new SSVoucherTemplateRow();
        row.setAccountNr(account);
        if (debit) {
            row.setDebet(BigDecimal.ZERO);
        } else {
            row.setCredit(BigDecimal.ZERO);
        }
        return row;
    }
}
