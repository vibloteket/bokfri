package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow;

/** Reads and writes Bokfri voucher-template workbooks without GUI or database side effects. */
public final class VoucherTemplateSpreadsheetService {
    static final String DESCRIPTION = "Beskrivning";
    static final String ACCOUNT = "Konto";
    static final String DEBIT = "Debet";
    static final String CREDIT = "Kredit";

    /** Reads voucher templates from the first sheet of an Excel {@code .xlsx} file. */
    public List<SSVoucherTemplate> read(Path input) throws IOException {
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

    /** Writes voucher templates to an Excel {@code .xlsx} file. */
    public Path write(List<SSVoucherTemplate> templates, Path output, boolean overwrite)
            throws IOException {
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(output, "output");
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Konteringmallar");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            String[] headings = {DESCRIPTION, ACCOUNT, DEBIT, CREDIT};
            Row headingRow = sheet.createRow(0);
            for (int column = 0; column < headings.length; column++) {
                Cell cell = headingRow.createCell(column);
                cell.setCellValue(headings[column]);
                cell.setCellStyle(header);
            }

            int rowIndex = 1;
            for (SSVoucherTemplate template : templates) {
                Row templateRow = sheet.createRow(++rowIndex);
                templateRow.createCell(0).setCellValue(value(template.getDescription()));
                for (SSVoucherTemplateRow sourceRow : template.getRows()) {
                    Row row = sheet.createRow(++rowIndex);
                    if (sourceRow.getAccountNr() != null) {
                        row.createCell(1).setCellValue(sourceRow.getAccountNr());
                    }
                    if (sourceRow.getDebet() != null) {
                        row.createCell(2).setCellValue(sourceRow.getDebet().doubleValue());
                    }
                    if (sourceRow.getCredit() != null) {
                        row.createCell(3).setCellValue(sourceRow.getCredit().doubleValue());
                    }
                }
            }
            try (OutputStream stream = Files.newOutputStream(resolved)) {
                workbook.write(stream);
            }
        }
        return resolved;
    }

    private List<SSVoucherTemplate> read(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
        Row headingRow = sheet.getRow(sheet.getFirstRowNum());
        if (headingRow == null) {
            throw new SSImportException("Excelbladet innehåller inga konteringsmallar.");
        }
        Map<String, Integer> columns = columns(headingRow, formatter);
        List<SSVoucherTemplate> templates = new ArrayList<>();
        SSVoucherTemplate current = null;
        for (int rowIndex = headingRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isEmpty(row, formatter)) {
                continue;
            }
            String description = string(row, columns.get(DESCRIPTION), formatter);
            if (!description.isBlank()) {
                current = new SSVoucherTemplate();
                current.setDescription(description);
                templates.add(current);
            }
            if (current == null || blank(row, columns.get(ACCOUNT), formatter)) {
                continue;
            }
            SSVoucherTemplateRow templateRow = new SSVoucherTemplateRow();
            templateRow.setAccountNr(integer(row, columns.get(ACCOUNT), formatter));
            if (!blank(row, columns.get(DEBIT), formatter)) {
                templateRow.setDebet(BigDecimal.ZERO);
            } else if (!blank(row, columns.get(CREDIT), formatter)) {
                templateRow.setCredit(BigDecimal.ZERO);
            }
            current.getRows().add(templateRow);
        }
        if (templates.isEmpty()) {
            throw new SSImportException("Excelbladet innehåller inga konteringsmallar.");
        }
        return templates;
    }

    private static Map<String, Integer> columns(Row row, DataFormatter formatter) {
        Map<String, Integer> result = new HashMap<>();
        for (Cell cell : row) {
            String heading = formatter.formatCellValue(cell).trim();
            if (!heading.isBlank()) {
                if (!List.of(DESCRIPTION, ACCOUNT, DEBIT, CREDIT).contains(heading)) {
                    throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + heading);
                }
                result.put(heading, cell.getColumnIndex());
            }
        }
        for (String required : List.of(DESCRIPTION, ACCOUNT, DEBIT, CREDIT)) {
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

    private static int integer(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }
        try {
            return Integer.parseInt(string(row, column, formatter));
        } catch (NumberFormatException exception) {
            throw new SSImportException("Ogiltigt kontonummer på rad " + (row.getRowNum() + 1));
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
