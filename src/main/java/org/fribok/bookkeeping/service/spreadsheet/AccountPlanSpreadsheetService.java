package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/** Reads and writes Bokfri account-plan workbooks without GUI or database side effects. */
public final class AccountPlanSpreadsheetService {
    private static final ResourceBundle BUNDLE = SSBundle.getBundle();
    private static final String NAME = BUNDLE.getString("importaccountplan.field_name");
    private static final String TYPE = BUNDLE.getString("importaccountplan.field_type");
    private static final String YEAR = BUNDLE.getString("importaccountplan.field_year");
    private static final String START = BUNDLE.getString("importaccountplan.field_start");
    private static final int ACCOUNT_START_ROW = 5;

    /** Reads the first sheet of an Excel {@code .xlsx} account-plan file. */
    public SSAccountPlan read(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        try (InputStream stream = Files.newInputStream(input)) {
            return read(stream);
        }
    }

    /** Reads the first sheet from an Excel {@code .xlsx} stream. */
    public SSAccountPlan read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        try (Workbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new SSImportException(BUNDLE, "importaccountplan.nosheets");
            }
            return read(workbook.getSheetAt(0));
        } catch (IllegalArgumentException exception) {
            throw new SSImportException("Ogiltig XLSX-fil: " + exception.getMessage());
        }
    }

    /** Writes an Excel {@code .xlsx} account-plan file. */
    public Path write(SSAccountPlan accountPlan, Path output, boolean overwrite) throws IOException {
        Objects.requireNonNull(accountPlan, "accountPlan");
        Objects.requireNonNull(output, "output");
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(safeSheetName(accountPlan.getName()));
            setString(sheet, 0, 0, NAME);
            setString(sheet, 0, 1, accountPlan.getName());
            setString(sheet, 1, 0, TYPE);
            setString(sheet, 1, 1, accountPlan.getType().toString());
            setString(sheet, 2, 0, YEAR);
            setString(sheet, 2, 1, accountPlan.getAssessementYear());
            setString(sheet, 3, 0, START);
            sheet.getRow(3).createCell(1).setCellValue(ACCOUNT_START_ROW + 1);

            int rowIndex = ACCOUNT_START_ROW;
            for (SSAccount account : accountPlan.getAccounts()) {
                Row row = sheet.createRow(rowIndex++);
                if (account.getNumber() != null) {
                    row.createCell(0).setCellValue(account.getNumber());
                }
                setString(row, 1, account.getDescription());
                setString(row, 2, account.getVATCode());
                setString(row, 3, account.getSRUCode());
                setString(row, 4, account.getReportCode());
            }
            try (OutputStream stream = Files.newOutputStream(resolved)) {
                workbook.write(stream);
            }
        }
        return resolved;
    }

    private SSAccountPlan read(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
        SSAccountPlan accountPlan = new SSAccountPlan();
        int accountStartRow = Integer.MAX_VALUE;
        for (Row row : sheet) {
            if (isEmpty(row, formatter)) {
                continue;
            }
            String marker = string(row, 0, formatter);
            if (marker.startsWith(NAME)) {
                accountPlan.setName(string(row, 1, formatter));
            } else if (marker.startsWith(TYPE)) {
                accountPlan.setType(string(row, 1, formatter));
            } else if (marker.startsWith(YEAR)) {
                accountPlan.setAssessementYear(string(row, 1, formatter));
            } else if (marker.startsWith(START)) {
                accountStartRow = integer(row, 1, formatter) - 1;
            } else if (row.getRowNum() >= accountStartRow) {
                SSAccount account = new SSAccount();
                account.setNumber(integer(row, 0, formatter));
                account.setDescription(string(row, 1, formatter));
                account.setVATCode(string(row, 2, formatter));
                account.setSRUCode(string(row, 3, formatter));
                account.setReportCode(string(row, 4, formatter));
                if (account.getNumber() != null) {
                    accountPlan.addAccount(account);
                }
            }
        }
        if (accountPlan.getAccounts().isEmpty()) {
            throw new SSImportException(BUNDLE, "importaccountplan.fileempty");
        }
        return accountPlan;
    }

    private static boolean isEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String string(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static int integer(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return 0;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }
        try {
            return Integer.parseInt(formatter.formatCellValue(cell).trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static void setString(Sheet sheet, int row, int column, String value) {
        Row target = sheet.getRow(row);
        if (target == null) {
            target = sheet.createRow(row);
        }
        setString(target, column, value);
    }

    private static void setString(Row row, int column, String value) {
        row.createCell(column).setCellValue(value == null ? "" : value);
    }

    private static String safeSheetName(String value) {
        String name = value == null || value.isBlank() ? "Kontoplan" : value;
        name = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return name.substring(0, Math.min(name.length(), 31));
    }
}
