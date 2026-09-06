package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoucherSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void xlsxRoundTripPreservesDatesDecimalsAndDimensions() throws Exception {
        SSVoucher voucher = new SSVoucher(17);
        voucher.setDescription("Inköp åäö");
        voucher.setLocalDate(LocalDate.of(2026, 9, 5));
        voucher.addVoucherRow(row(4010, "1234.56", null, "P-1", "R-1"));
        voucher.addVoucherRow(row(1930, null, "1234.56", null, null));
        Path file = temporaryDirectory.resolve("verifikationer.xlsx");
        VoucherSpreadsheetService service = new VoucherSpreadsheetService();

        service.write(List.of(voucher), file, false);
        List<SSVoucher> imported = service.read(file);

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04);
        assertThat(imported).singleElement().satisfies(actual -> {
            assertThat(actual.getNumber()).isEqualTo(17);
            assertThat(actual.getDescription()).isEqualTo("Inköp åäö");
            assertThat(actual.getLocalDate()).isEqualTo(LocalDate.of(2026, 9, 5));
            assertThat(actual.getRows()).hasSize(2);
            assertThat(actual.getRows().get(0).getDebet()).isEqualByComparingTo("1234.56");
            assertThat(actual.getRows().get(0).getProjectNr()).isEqualTo("P-1");
            assertThat(actual.getRows().get(0).getResultUnitNr()).isEqualTo("R-1");
            assertThat(actual.getRows().get(1).getCredit()).isEqualByComparingTo("1234.56");
        });
    }

    @Test
    void invalidDateIsRejectedInsteadOfBecomingToday() throws Exception {
        Path file = temporaryDirectory.resolve("invalid-date.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Verifikationer");
            var headings = sheet.createRow(0);
            String[] names = {"Nummer", "Beskrivning", "Datum", "Konto", "Debet", "Kredit",
                    "Projekt", "Resultatenhet"};
            for (int index = 0; index < names.length; index++) {
                headings.createCell(index).setCellValue(names[index]);
            }
            var voucher = sheet.createRow(1);
            voucher.createCell(0).setCellValue(1);
            voucher.createCell(1).setCellValue("Fel datum");
            voucher.createCell(2).setCellValue("inte-ett-datum");
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        assertThatThrownBy(() -> new VoucherSpreadsheetService().read(file))
                .isInstanceOf(SSImportException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    private static SSVoucherRow row(int account, String debit, String credit,
                                    String project, String resultUnit) {
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(account);
        row.setDebet(debit == null ? null : new BigDecimal(debit));
        row.setCredit(credit == null ? null : new BigDecimal(credit));
        row.setProjectNr(project);
        row.setResultUnitNr(resultUnit);
        return row;
    }
}
