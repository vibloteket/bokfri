package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri voucher workbooks without GUI or database side effects. */
public final class VoucherSpreadsheetService {
  static final String NUMBER = "Nummer",
      DESCRIPTION = "Beskrivning",
      DATE = "Datum",
      ACCOUNT = "Konto",
      DEBIT = "Debet",
      CREDIT = "Kredit",
      PROJECT = "Projekt",
      RESULT_UNIT = "Resultatenhet";
  private static final List<String> HEADINGS =
      List.of(NUMBER, DESCRIPTION, DATE, ACCOUNT, DEBIT, CREDIT, PROJECT, RESULT_UNIT);

  public List<SSVoucher> read(Path input) throws IOException {
    List<Row> rows = FastExcelSupport.readFirstSheet(input);
    if (rows.isEmpty()) throw new SSImportException("Excelbladet innehåller inga verifikationer.");
    Map<String, Integer> c = columns(rows.get(0));
    List<SSVoucher> out = new ArrayList<>();
    SSVoucher current = null;
    for (Row row : rows.subList(1, rows.size())) {
      if (FastExcelSupport.empty(row)) continue;
      if (!text(row, c.get(NUMBER)).isBlank()) {
        current = new SSVoucher(integer(row, c.get(NUMBER), "verifikationsnummer"));
        current.setDescription(text(row, c.get(DESCRIPTION)));
        current.setLocalDate(date(row, c.get(DATE)));
        out.add(current);
      }
      if (current == null || text(row, c.get(ACCOUNT)).isBlank()) continue;
      SSVoucherRow vr = new SSVoucherRow();
      vr.setAccountNr(integer(row, c.get(ACCOUNT), "kontonummer"));
      vr.setDebet(decimal(row, c.get(DEBIT)));
      vr.setCredit(decimal(row, c.get(CREDIT)));
      vr.setProjectNr(emptyToNull(text(row, c.get(PROJECT))));
      vr.setResultUnitNr(emptyToNull(text(row, c.get(RESULT_UNIT))));
      current.addVoucherRow(vr);
    }
    if (out.isEmpty()) throw new SSImportException("Excelbladet innehåller inga verifikationer.");
    return out;
  }

  public Path write(List<SSVoucher> vouchers, Path output, boolean overwrite) throws IOException {
    return FastExcelSupport.write(
        output,
        overwrite,
        "Verifikationer",
        sheet -> {
          FastExcelSupport.header(sheet, HEADINGS);
          int row = 2;
          for (SSVoucher v : vouchers) {
            FastExcelSupport.number(sheet, row, 0, v.getNumber());
            FastExcelSupport.string(sheet, row, 1, v.getDescription());
            FastExcelSupport.string(
                sheet, row, 2, v.getLocalDate() == null ? "" : v.getLocalDate().toString());
            row++;
            for (SSVoucherRow r : v.getRows()) {
              if (r.isCrossed()) continue;
              FastExcelSupport.number(sheet, row, 3, r.getAccountNr());
              FastExcelSupport.number(sheet, row, 4, r.getDebet());
              FastExcelSupport.number(sheet, row, 5, r.getCredit());
              FastExcelSupport.string(sheet, row, 6, r.getProjectNr());
              FastExcelSupport.string(sheet, row, 7, r.getResultUnitNr());
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

  private static int integer(Row row, int column, String field) {
    BigDecimal n = FastExcelSupport.number(row, column);
    if (n != null) return n.intValue();
    try {
      return Integer.parseInt(text(row, column));
    } catch (NumberFormatException e) {
      throw new SSImportException("Ogiltigt " + field + " på rad " + row.getRowNum());
    }
  }

  private static BigDecimal decimal(Row row, int column) {
    if (text(row, column).isBlank()) return null;
    BigDecimal n = FastExcelSupport.number(row, column);
    if (n == null) throw new SSImportException("Ogiltigt belopp på rad " + row.getRowNum());
    return n;
  }

  private static LocalDate date(Row row, int column) {
    try {
      return LocalDate.parse(text(row, column));
    } catch (DateTimeParseException e) {
      throw new SSImportException(
          "Ogiltigt datum på rad " + row.getRowNum() + "; förväntat format är yyyy-MM-dd");
    }
  }

  private static String emptyToNull(String v) {
    return v.isBlank() ? null : v;
  }
}
