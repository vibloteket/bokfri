package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reads and writes Bokfri customer workbooks without GUI or database side effects. */
public final class CustomerSpreadsheetService {
    private static final List<String> HEADINGS = List.of("Kund-id", "Namn", "Telefon1", "Telefon2",
            "Fax", "Epost", "Kontaktperson", "Organisationsnummer", "Bankgiro", "Plusgiro",
            "Fakturaadress.Namn", "Fakturaadress.Adress1", "Fakturaadress.Adress2",
            "Fakturaadress.Postnummer", "Fakturaadress.Postort", "Fakturaadress.Land",
            "Leveransadress.Namn", "Leveransadress.Adress1", "Leveransadress.Adress2",
            "Leveransadress.Postnummer", "Leveransadress.Postort", "Leveransadress.Land");

    public List<SSCustomer> read(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input); Workbook workbook = new HSSFWorkbook(stream)) {
            if (workbook.getNumberOfSheets() == 0) throw new SSImportException("Excelfilen innehåller inga blad.");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
            Row headings = sheet.getRow(sheet.getFirstRowNum());
            if (headings == null) throw new SSImportException("Excelbladet innehåller inga kunder.");
            Map<String,Integer> columns = columns(headings, formatter);
            if (!columns.containsKey(HEADINGS.get(0)) || !columns.containsKey(HEADINGS.get(1)))
                throw new SSImportException("Importfilen måste innehålla Kund-id och Namn.");
            List<SSCustomer> customers = new ArrayList<>();
            for (int index = headings.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index); if (row == null || empty(row, formatter)) continue;
                SSCustomer customer = new SSCustomer();
                customer.setNumber(text(row, columns, 0, formatter)); customer.setName(text(row, columns, 1, formatter));
                customer.setPhone1(text(row, columns, 2, formatter)); customer.setPhone2(text(row, columns, 3, formatter));
                customer.setTelefax(text(row, columns, 4, formatter)); customer.setEMail(text(row, columns, 5, formatter));
                customer.setYourContactPerson(text(row, columns, 6, formatter));
                customer.setRegistrationNumber(text(row, columns, 7, formatter));
                customer.setBankgiro(text(row, columns, 8, formatter)); customer.setPlusgiro(text(row, columns, 9, formatter));
                customer.getInvoiceAddress().setName(text(row, columns, 10, formatter));
                customer.getInvoiceAddress().setAddress1(text(row, columns, 11, formatter));
                customer.getInvoiceAddress().setAddress2(text(row, columns, 12, formatter));
                customer.getInvoiceAddress().setZipCode(text(row, columns, 13, formatter));
                customer.getInvoiceAddress().setCity(text(row, columns, 14, formatter));
                customer.getInvoiceAddress().setCountry(text(row, columns, 15, formatter));
                customer.getDeliveryAddress().setName(text(row, columns, 16, formatter));
                customer.getDeliveryAddress().setAddress1(text(row, columns, 17, formatter));
                customer.getDeliveryAddress().setAddress2(text(row, columns, 18, formatter));
                customer.getDeliveryAddress().setZipCode(text(row, columns, 19, formatter));
                customer.getDeliveryAddress().setCity(text(row, columns, 20, formatter));
                customer.getDeliveryAddress().setCountry(text(row, columns, 21, formatter));
                if (!customer.getNumber().isBlank()) customers.add(customer);
            }
            if (customers.isEmpty()) throw new SSImportException("Excelbladet innehåller inga kunder.");
            return customers;
        } catch (IllegalArgumentException exception) {
            throw new SSImportException("Ogiltig XLS-fil: " + exception.getMessage());
        }
    }

    public Path write(List<SSCustomer> customers, Path output, boolean overwrite) throws IOException {
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) throw new FileAlreadyExistsException(resolved.toString());
        if (resolved.getParent() != null) Files.createDirectories(resolved.getParent());
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Kunder");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row headingRow = sheet.createRow(0);
            for (int column = 0; column < HEADINGS.size(); column++) {
                Cell cell = headingRow.createCell(column); cell.setCellValue(HEADINGS.get(column)); cell.setCellStyle(header);
            }
            int index = 1;
            for (SSCustomer customer : customers) {
                Row row = sheet.createRow(index++);
                String[] values = {customer.getNumber(), customer.getName(), customer.getPhone1(), customer.getPhone2(),
                        customer.getTelefax(), customer.getEMail(), customer.getYourContactPerson(),
                        customer.getRegistrationNumber(), customer.getBankgiro(), customer.getPlusgiro(),
                        customer.getInvoiceAddress().getName(), customer.getInvoiceAddress().getAddress1(),
                        customer.getInvoiceAddress().getAddress2(), customer.getInvoiceAddress().getZipCode(),
                        customer.getInvoiceAddress().getCity(), customer.getInvoiceAddress().getCountry(),
                        customer.getDeliveryAddress().getName(), customer.getDeliveryAddress().getAddress1(),
                        customer.getDeliveryAddress().getAddress2(), customer.getDeliveryAddress().getZipCode(),
                        customer.getDeliveryAddress().getCity(), customer.getDeliveryAddress().getCountry()};
                for (int column = 0; column < values.length; column++)
                    row.createCell(column).setCellValue(values[column] == null ? "" : values[column]);
            }
            try (OutputStream stream = Files.newOutputStream(resolved)) { workbook.write(stream); }
        }
        return resolved;
    }

    private static Map<String,Integer> columns(Row row, DataFormatter formatter) {
        Map<String,Integer> result = new HashMap<>();
        for (Cell cell : row) {
            String heading = formatter.formatCellValue(cell).trim();
            if (!heading.isBlank()) {
                if (!HEADINGS.contains(heading)) throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + heading);
                result.put(heading, cell.getColumnIndex());
            }
        }
        return result;
    }
    private static String text(Row row, Map<String,Integer> columns, int heading, DataFormatter formatter) {
        Integer column = columns.get(HEADINGS.get(heading)); if (column == null) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
    private static boolean empty(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) return false;
        return true;
    }
}
