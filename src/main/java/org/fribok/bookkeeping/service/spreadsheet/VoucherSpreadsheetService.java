package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Reads and writes Bokfri voucher workbooks without GUI or database side effects. */
public final class VoucherSpreadsheetService {
    static final String NUMBER = "Nummer";
    static final String DESCRIPTION = "Beskrivning";
    static final String DATE = "Datum";
    static final String ACCOUNT = "Konto";
    static final String DEBIT = "Debet";
    static final String CREDIT = "Kredit";
    static final String PROJECT = "Projekt";
    static final String RESULT_UNIT = "Resultatenhet";
    private static final List<String> HEADINGS = List.of(NUMBER, DESCRIPTION, DATE, ACCOUNT,
            DEBIT, CREDIT, PROJECT, RESULT_UNIT);

    /** Reads vouchers from the first sheet of an Excel {@code .xlsx} file. */
    public List<SSVoucher> read(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        try (InputStream stream = Files.newInputStream(input);
             Workbook workbook = new XSSFWorkbook(stream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new SSImportException("Excelfilen innehåller inga blad.");
            }
            return read(workbook.getSheetAt(0));
        } catch (IllegalArgumentException exception) {
            throw new SSImportException("Ogiltig XLSX-fil: " + exception.getMessage());
        }
    }

    /** Writes vouchers to an Excel {@code .xlsx} file. */
    public Path write(List<SSVoucher> vouchers, Path output, boolean overwrite) throws IOException {
        Objects.requireNonNull(vouchers, "vouchers");
        Objects.requireNonNull(output, "output");
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Verifikationer");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row headingRow = sheet.createRow(0);
            for (int column = 0; column < HEADINGS.size(); column++) {
                Cell cell = headingRow.createCell(column);
                cell.setCellValue(HEADINGS.get(column));
                cell.setCellStyle(header);
            }

            int rowIndex = 1;
            for (SSVoucher voucher : vouchers) {
                Row voucherRow = sheet.createRow(++rowIndex);
                voucherRow.createCell(0).setCellValue(voucher.getNumber());
                voucherRow.createCell(1).setCellValue(value(voucher.getDescription()));
                voucherRow.createCell(2).setCellValue(voucher.getLocalDate() == null
                        ? "" : voucher.getLocalDate().toString());
                for (SSVoucherRow sourceRow : voucher.getRows()) {
                    if (sourceRow.isCrossed()) {
                        continue;
                    }
                    Row row = sheet.createRow(++rowIndex);
                    number(row, 3, sourceRow.getAccountNr());
                    decimal(row, 4, sourceRow.getDebet());
                    decimal(row, 5, sourceRow.getCredit());
                    row.createCell(6).setCellValue(value(sourceRow.getProjectNr()));
                    row.createCell(7).setCellValue(value(sourceRow.getResultUnitNr()));
                }
            }
            try (OutputStream stream = Files.newOutputStream(resolved)) {
                workbook.write(stream);
            }
        }
        return resolved;
    }

    private List<SSVoucher> read(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
        Row headingRow = sheet.getRow(sheet.getFirstRowNum());
        if (headingRow == null) {
            throw new SSImportException("Excelbladet innehåller inga verifikationer.");
        }
        Map<String, Integer> columns = columns(headingRow, formatter);
        List<SSVoucher> vouchers = new ArrayList<>();
        SSVoucher current = null;
        for (int rowIndex = headingRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isEmpty(row, formatter)) {
                continue;
            }
            if (!blank(row, columns.get(NUMBER), formatter)) {
                current = new SSVoucher(integer(row, columns.get(NUMBER), formatter, "verifikationsnummer"));
                current.setDescription(string(row, columns.get(DESCRIPTION), formatter));
                current.setLocalDate(date(row, columns.get(DATE), formatter));
                vouchers.add(current);
            }
            if (current == null || blank(row, columns.get(ACCOUNT), formatter)) {
                continue;
            }
            SSVoucherRow voucherRow = new SSVoucherRow();
            voucherRow.setAccountNr(integer(row, columns.get(ACCOUNT), formatter, "kontonummer"));
            voucherRow.setDebet(decimal(row, columns.get(DEBIT), formatter));
            voucherRow.setCredit(decimal(row, columns.get(CREDIT), formatter));
            voucherRow.setProjectNr(emptyToNull(string(row, columns.get(PROJECT), formatter)));
            voucherRow.setResultUnitNr(emptyToNull(string(row, columns.get(RESULT_UNIT), formatter)));
            current.addVoucherRow(voucherRow);
        }
        if (vouchers.isEmpty()) {
            throw new SSImportException("Excelbladet innehåller inga verifikationer.");
        }
        return vouchers;
    }

    private static Map<String, Integer> columns(Row row, DataFormatter formatter) {
        Map<String, Integer> result = new HashMap<>();
        for (Cell cell : row) {
            String heading = formatter.formatCellValue(cell).trim();
            if (!heading.isBlank()) {
                if (!HEADINGS.contains(heading)) {
                    throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + heading);
                }
                result.put(heading, cell.getColumnIndex());
            }
        }
        for (String required : HEADINGS) {
            if (!result.containsKey(required)) {
                throw new SSImportException("Importfilen saknar kolumnen: " + required);
            }
        }
        return result;
    }

    private static boolean isEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(Row row, int column, DataFormatter formatter) {
        return string(row, column, formatter).isBlank();
    }

    private static String string(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static int integer(Row row, int column, DataFormatter formatter, String field) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }
        try {
            return Integer.parseInt(string(row, column, formatter));
        } catch (NumberFormatException exception) {
            throw new SSImportException("Ogiltigt " + field + " på rad " + (row.getRowNum() + 1));
        }
    }

    private static BigDecimal decimal(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || formatter.formatCellValue(cell).isBlank()) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        try {
            return new BigDecimal(formatter.formatCellValue(cell).trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new SSImportException("Ogiltigt belopp på rad " + (row.getRowNum() + 1));
        }
    }

    private static LocalDate date(Row row, int column, DataFormatter formatter) {
        String value = string(row, column, formatter);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new SSImportException("Ogiltigt datum på rad " + (row.getRowNum() + 1)
                    + "; förväntat format är yyyy-MM-dd");
        }
    }

    private static void number(Row row, int column, Number value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private static void decimal(Row row, int column, BigDecimal value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
