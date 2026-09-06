package org.fribok.bookkeeping.service.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

class VoucherSpreadsheetServiceTest {
  @TempDir Path temporaryDirectory;

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

    assertThat(Files.readAllBytes(file))
        .startsWith((byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04);
    assertThat(imported)
        .singleElement()
        .satisfies(
            actual -> {
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
    try (var output = Files.newOutputStream(file);
        var workbook = new Workbook(output, "Bokfri test", "1.0")) {
      Worksheet sheet = workbook.newWorksheet("Verifikationer");
      String[] names = {
        "Nummer", "Beskrivning", "Datum", "Konto", "Debet", "Kredit", "Projekt", "Resultatenhet"
      };
      for (int index = 0; index < names.length; index++) {
        sheet.value(0, index, names[index]);
      }
      sheet.value(1, 0, 1);
      sheet.value(1, 1, "Fel datum");
      sheet.value(1, 2, "inte-ett-datum");
    }

    assertThatThrownBy(() -> new VoucherSpreadsheetService().read(file))
        .isInstanceOf(SSImportException.class)
        .hasMessageContaining("yyyy-MM-dd");
  }

  private static SSVoucherRow row(
      int account, String debit, String credit, String project, String resultUnit) {
    SSVoucherRow row = new SSVoucherRow();
    row.setAccountNr(account);
    row.setDebet(debit == null ? null : new BigDecimal(debit));
    row.setCredit(credit == null ? null : new BigDecimal(credit));
    row.setProjectNr(project);
    row.setResultUnitNr(resultUnit);
    return row;
  }
}
