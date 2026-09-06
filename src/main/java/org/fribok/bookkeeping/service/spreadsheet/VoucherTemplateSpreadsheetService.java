package org.fribok.bookkeeping.service.spreadsheet;

import static se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri voucher-template workbooks without GUI or database side effects. */
public final class VoucherTemplateSpreadsheetService {
  static final String DESCRIPTION = "Beskrivning",
      ACCOUNT = "Konto",
      DEBIT = "Debet",
      CREDIT = "Kredit";
  private static final List<String> HEADINGS = List.of(DESCRIPTION, ACCOUNT, DEBIT, CREDIT);

  public List<SSVoucherTemplate> read(Path input) throws IOException {
    List<Row> rows = FastExcelSupport.readFirstSheet(input);
    if (rows.isEmpty())
      throw new SSImportException("Excelbladet innehåller inga konteringsmallar.");
    Map<String, Integer> c = columns(rows.get(0));
    List<SSVoucherTemplate> out = new ArrayList<>();
    SSVoucherTemplate current = null;
    for (Row row : rows.subList(1, rows.size())) {
      if (FastExcelSupport.empty(row)) continue;
      String description = text(row, c.get(DESCRIPTION));
      if (!description.isBlank()) {
        current = new SSVoucherTemplate();
        current.setDescription(description);
        out.add(current);
      }
      if (current == null || text(row, c.get(ACCOUNT)).isBlank()) continue;
      SSVoucherTemplateRow tr = new SSVoucherTemplateRow();
      tr.setAccountNr(integer(row, c.get(ACCOUNT)));
      if (!text(row, c.get(DEBIT)).isBlank()) tr.setDebet(BigDecimal.ZERO);
      else if (!text(row, c.get(CREDIT)).isBlank()) tr.setCredit(BigDecimal.ZERO);
      current.getRows().add(tr);
    }
    if (out.isEmpty()) throw new SSImportException("Excelbladet innehåller inga konteringsmallar.");
    return out;
  }

  public Path write(List<SSVoucherTemplate> templates, Path output, boolean overwrite)
      throws IOException {
    return FastExcelSupport.write(
        output,
        overwrite,
        "Konteringmallar",
        sheet -> {
          FastExcelSupport.header(sheet, HEADINGS);
          int row = 2;
          for (SSVoucherTemplate t : templates) {
            FastExcelSupport.string(sheet, row++, 0, t.getDescription());
            for (SSVoucherTemplateRow r : t.getRows()) {
              FastExcelSupport.number(sheet, row, 1, r.getAccountNr());
              FastExcelSupport.number(sheet, row, 2, r.getDebet());
              FastExcelSupport.number(sheet, row, 3, r.getCredit());
              row++;
            }
          }
        });
  }

  private static Map<String, Integer> columns(Row row) {
    Map<String, Integer> r = new HashMap<>();
    for (int c = 0; c < row.getCellCount(); c++) {
      String h = FastExcelSupport.text(row, c);
      if (!h.isBlank()) {
        if (!HEADINGS.contains(h))
          throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + h);
        r.put(h, c);
      }
    }
    for (String h : HEADINGS)
      if (!r.containsKey(h)) throw new SSImportException("Importfilen saknar kolumnen: " + h);
    return r;
  }

  private static String text(Row row, Integer column) {
    return column == null ? "" : FastExcelSupport.text(row, column);
  }

  private static int integer(Row row, int column) {
    BigDecimal n = FastExcelSupport.number(row, column);
    if (n != null) return n.intValue();
    try {
      return Integer.parseInt(text(row, column));
    } catch (NumberFormatException e) {
      throw new SSImportException("Ogiltigt kontonummer på rad " + row.getRowNum());
    }
  }
}
