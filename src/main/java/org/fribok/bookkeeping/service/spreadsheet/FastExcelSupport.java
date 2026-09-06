package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Shared, deliberately small XLSX adapter around fastexcel. */
final class FastExcelSupport {
  private FastExcelSupport() {}

  static List<Row> readFirstSheet(Path input) throws IOException {
    try (InputStream stream = Files.newInputStream(input)) {
      return readFirstSheet(stream);
    }
  }

  static List<Row> readFirstSheet(InputStream input) throws IOException {
    Objects.requireNonNull(input, "input");
    try (ReadableWorkbook workbook = new ReadableWorkbook(input)) {
      var sheet = workbook.getFirstSheet();
      if (sheet == null) {
        throw new SSImportException("Excelfilen innehåller inga blad.");
      }
      return sheet.read();
    } catch (SSImportException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SSImportException("Ogiltig XLSX-fil: " + exception.getMessage());
    }
  }

  static Path write(Path output, boolean overwrite, String sheetName, SheetWriter writer)
      throws IOException {
    Path resolved = output.toAbsolutePath().normalize();
    if (Files.exists(resolved) && !overwrite) {
      throw new FileAlreadyExistsException(resolved.toString());
    }
    if (resolved.getParent() != null) {
      Files.createDirectories(resolved.getParent());
    }
    try (OutputStream stream = Files.newOutputStream(resolved);
        Workbook workbook = new Workbook(stream, "Bokfri", "1.1")) {
      writer.write(workbook.newWorksheet(sheetName));
    }
    return resolved;
  }

  static void header(Worksheet sheet, List<String> headings) {
    for (int column = 0; column < headings.size(); column++) {
      sheet.value(0, column, headings.get(column));
    }
    if (!headings.isEmpty()) {
      sheet.range(0, 0, 0, headings.size() - 1).style().bold().fillColor("D9D9D9").set();
    }
  }

  static String text(Row row, int column) {
    if (column < 0 || !row.hasCell(column)) return "";
    Cell cell = row.getCell(column);
    if (cell == null || cell.getType() == CellType.EMPTY) return "";
    String value = cell.getText();
    return value == null ? "" : value.trim();
  }

  static BigDecimal number(Row row, int column) {
    if (column < 0 || !row.hasCell(column)) return null;
    Cell cell = row.getCell(column);
    if (cell == null || cell.getType() == CellType.EMPTY || text(row, column).isBlank())
      return null;
    if (cell.getType() == CellType.NUMBER) return cell.asNumber();
    try {
      return new BigDecimal(text(row, column).replace(',', '.'));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  static boolean empty(Row row) {
    return row.stream()
        .allMatch(cell -> cell == null || cell.getText() == null || cell.getText().isBlank());
  }

  static void string(Worksheet sheet, int row, int column, String value) {
    sheet.value(row, column, value == null ? "" : value);
  }

  static void number(Worksheet sheet, int row, int column, Number value) {
    if (value != null) sheet.value(row, column, value);
  }

  @FunctionalInterface
  interface SheetWriter {
    void write(Worksheet sheet) throws IOException;
  }
}
