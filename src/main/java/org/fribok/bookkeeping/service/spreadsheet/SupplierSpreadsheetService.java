package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Reads and writes Bokfri supplier workbooks without GUI or database side effects. */
public final class SupplierSpreadsheetService {
    private static final String NUMBER = "Leverantörs-id";
    private static final String NAME = "Namn";
    private static final String PHONE_1 = "Telefon1";
    private static final String PHONE_2 = "Telefon2";
    private static final String FAX = "Fax";
    private static final String EMAIL = "Epost";
    private static final String HOMEPAGE = "Hemsida";
    private static final String CONTACT = "Kontaktperson";
    private static final String REGISTRATION_NUMBER = "Organisationsnummer";
    private static final String OUR_CUSTOMER_NUMBER = "Vårt kundnummer";
    private static final String BANKGIRO = "Bankgiro";
    private static final String PLUSGIRO = "Plusgiro";
    private static final String ADDRESS_NAME = "Adress.Namn";
    private static final String ADDRESS_1 = "Adress.Adress1";
    private static final String ADDRESS_2 = "Adress.Adress2";
    private static final String ZIP_CODE = "Adress.Postnummer";
    private static final String CITY = "Adress.Postort";
    private static final String COUNTRY = "Adress.Land";
    private static final List<String> HEADINGS = List.of(NUMBER, NAME, PHONE_1, PHONE_2, FAX,
            EMAIL, HOMEPAGE, CONTACT, REGISTRATION_NUMBER, OUR_CUSTOMER_NUMBER, BANKGIRO,
            PLUSGIRO, ADDRESS_NAME, ADDRESS_1, ADDRESS_2, ZIP_CODE, CITY, COUNTRY);

    /** Reads suppliers from the first sheet of a legacy Excel {@code .xls} file. */
    public List<SSSupplier> read(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        try (InputStream stream = Files.newInputStream(input);
             Workbook workbook = new HSSFWorkbook(stream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new SSImportException("Excelfilen innehåller inga blad.");
            }
            return read(workbook.getSheetAt(0));
        } catch (IllegalArgumentException exception) {
            throw new SSImportException("Ogiltig XLS-fil: " + exception.getMessage());
        }
    }

    /** Writes suppliers to a legacy Excel {@code .xls} file. */
    public Path write(List<SSSupplier> suppliers, Path output, boolean overwrite) throws IOException {
        Objects.requireNonNull(suppliers, "suppliers");
        Objects.requireNonNull(output, "output");
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Leverantörer");
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
            for (SSSupplier supplier : suppliers) {
                Row row = sheet.createRow(rowIndex++);
                String[] values = {supplier.getNumber(), supplier.getName(), supplier.getPhone1(),
                        supplier.getPhone2(), supplier.getTelefax(), supplier.getEMail(),
                        supplier.getHomepage(), supplier.getYourContact(),
                        supplier.getRegistrationNumber(), supplier.getOurCustomerNr(),
                        supplier.getBankgiro(), supplier.getPlusgiro(), supplier.getAddress().getName(),
                        supplier.getAddress().getAddress1(), supplier.getAddress().getAddress2(),
                        supplier.getAddress().getZipCode(), supplier.getAddress().getCity(),
                        supplier.getAddress().getCountry()};
                for (int column = 0; column < values.length; column++) {
                    row.createCell(column).setCellValue(value(values[column]));
                }
            }
            try (OutputStream stream = Files.newOutputStream(resolved)) {
                workbook.write(stream);
            }
        }
        return resolved;
    }

    private List<SSSupplier> read(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
        Row headingRow = sheet.getRow(sheet.getFirstRowNum());
        if (headingRow == null) {
            throw new SSImportException("Excelbladet innehåller inga leverantörer.");
        }
        Map<String, Integer> columns = columns(headingRow, formatter);
        if (!columns.containsKey(NUMBER) || !columns.containsKey(NAME)) {
            throw new SSImportException("Importfilen måste innehålla kolumnerna " + NUMBER
                    + " och " + NAME + '.');
        }
        List<SSSupplier> suppliers = new ArrayList<>();
        for (int rowIndex = headingRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isEmpty(row, formatter)) {
                continue;
            }
            SSSupplier supplier = new SSSupplier();
            supplier.setNumber(get(row, columns, NUMBER, formatter));
            supplier.setName(get(row, columns, NAME, formatter));
            supplier.setPhone1(get(row, columns, PHONE_1, formatter));
            supplier.setPhone2(get(row, columns, PHONE_2, formatter));
            supplier.setTelefax(get(row, columns, FAX, formatter));
            supplier.setEMail(get(row, columns, EMAIL, formatter));
            supplier.setHomepage(get(row, columns, HOMEPAGE, formatter));
            supplier.setYourContact(get(row, columns, CONTACT, formatter));
            supplier.setRegistrationNumber(get(row, columns, REGISTRATION_NUMBER, formatter));
            supplier.setOurCustomerNr(get(row, columns, OUR_CUSTOMER_NUMBER, formatter));
            supplier.setBankGiro(get(row, columns, BANKGIRO, formatter));
            supplier.setPlusGiro(get(row, columns, PLUSGIRO, formatter));
            supplier.getAddress().setName(get(row, columns, ADDRESS_NAME, formatter));
            supplier.getAddress().setAddress1(get(row, columns, ADDRESS_1, formatter));
            supplier.getAddress().setAddress2(get(row, columns, ADDRESS_2, formatter));
            supplier.getAddress().setZipCode(get(row, columns, ZIP_CODE, formatter));
            supplier.getAddress().setCity(get(row, columns, CITY, formatter));
            supplier.getAddress().setCountry(get(row, columns, COUNTRY, formatter));
            if (!supplier.getNumber().isBlank()) {
                suppliers.add(supplier);
            }
        }
        if (suppliers.isEmpty()) {
            throw new SSImportException("Excelbladet innehåller inga leverantörer.");
        }
        return suppliers;
    }

    private static Map<String, Integer> columns(Row row, DataFormatter formatter) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            String heading = formatter.formatCellValue(cell).trim();
            if (!heading.isBlank()) {
                if (!HEADINGS.contains(heading)) {
                    throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + heading);
                }
                result.put(heading, cell.getColumnIndex());
            }
        }
        return result;
    }

    private static String get(Row row, Map<String, Integer> columns, String heading,
                              DataFormatter formatter) {
        Integer column = columns.get(heading);
        if (column == null) {
            return "";
        }
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static boolean isEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
