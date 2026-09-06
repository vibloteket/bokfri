package org.fribok.bookkeeping.service.spreadsheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSStock;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Reads and writes Bokfri product workbooks without GUI or database side effects. */
public final class ProductSpreadsheetService {
    private static final List<String> HEADINGS = List.of("Produkt-id", "Beskrivning",
            "Försäljningspris", "Inköpspris", "Enhetsfrakt", "Moms", "Enhet", "Vikt",
            "Volym", "Leverantör", "Leverantörens artikelnummer", "Beställningspunkt",
            "Lagerplats", "Lagerantal", "Disponibelt", "Lagerpris");

    public List<SSProduct> read(Path input, SSNewCompany company, List<SSUnit> units)
            throws IOException {
        Objects.requireNonNull(company, "company");
        try (InputStream stream = Files.newInputStream(input); Workbook workbook = new XSSFWorkbook(stream)) {
            if (workbook.getNumberOfSheets() == 0) throw new SSImportException("Excelfilen innehåller inga blad.");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("sv-SE"));
            Row headings = sheet.getRow(sheet.getFirstRowNum());
            if (headings == null) throw new SSImportException("Excelbladet innehåller inga produkter.");
            Map<String, Integer> columns = columns(headings, formatter);
            if (!columns.containsKey(HEADINGS.get(0)) || !columns.containsKey(HEADINGS.get(1))) {
                throw new SSImportException("Importfilen måste innehålla Produkt-id och Beskrivning.");
            }
            List<SSProduct> products = new ArrayList<>();
            for (int index = headings.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || empty(row, formatter)) continue;
                SSProduct product = new SSProduct();
                product.setNumber(text(row, columns, 0, formatter));
                product.setDescription(text(row, columns, 1, formatter));
                product.setSellingPrice(decimal(row, columns, 2, formatter, BigDecimal.ZERO));
                product.setPurchasePrice(decimal(row, columns, 3, formatter, BigDecimal.ZERO));
                product.setUnitFreight(decimal(row, columns, 4, formatter, BigDecimal.ZERO));
                product.setTaxCode(taxCode(decimal(row, columns, 5, formatter, BigDecimal.ZERO), company));
                String unitName = text(row, columns, 6, formatter);
                product.setUnit(units.stream().filter(unit -> unitName.equals(unit.getName())
                        || unitName.equals(unit.getDescription())).findFirst().orElse(null));
                product.setWeight(decimal(row, columns, 7, formatter, BigDecimal.ZERO));
                product.setVolume(decimal(row, columns, 8, formatter, BigDecimal.ZERO));
                product.setSupplierNr(emptyToNull(text(row, columns, 9, formatter)));
                product.setSupplierProductNr(emptyToNull(text(row, columns, 10, formatter)));
                product.setOrderpoint(integer(row, columns, 11, formatter, 0));
                product.setWarehouseLocation(emptyToNull(text(row, columns, 12, formatter)));
                product.setStockPrice(decimal(row, columns, 15, formatter, BigDecimal.ZERO));
                if (!product.getNumber().isBlank()) products.add(product);
            }
            if (products.isEmpty()) throw new SSImportException("Excelbladet innehåller inga produkter.");
            return products;
        } catch (IllegalArgumentException exception) {
            throw new SSImportException("Ogiltig XLSX-fil: " + exception.getMessage());
        }
    }

    public Path write(List<SSProduct> products, SSStock stock, SSNewCompany company,
                      Path output, boolean overwrite) throws IOException {
        Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) throw new FileAlreadyExistsException(resolved.toString());
        if (resolved.getParent() != null) Files.createDirectories(resolved.getParent());
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Produkter");
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row headingRow = sheet.createRow(0);
            for (int column = 0; column < HEADINGS.size(); column++) {
                Cell cell = headingRow.createCell(column); cell.setCellValue(HEADINGS.get(column)); cell.setCellStyle(header);
            }
            int rowIndex = 1;
            for (SSProduct product : products) {
                Row row = sheet.createRow(rowIndex++);
                string(row, 0, product.getNumber()); string(row, 1, product.getDescription());
                number(row, 2, product.getSellingPrice()); number(row, 3, product.getPurchasePrice());
                number(row, 4, product.getUnitFreight()); number(row, 5, taxRate(product.getTaxCode(), company));
                string(row, 6, product.getUnit() == null ? null : product.getUnit().getName());
                number(row, 7, product.getWeight()); number(row, 8, product.getVolume());
                string(row, 9, product.getSupplierNr()); string(row, 10, product.getSupplierProductNr());
                number(row, 11, product.getOrderpoint()); string(row, 12, product.getWarehouseLocation());
                number(row, 13, stock.getQuantity(product)); number(row, 14, stock.getAvaiable(product));
                number(row, 15, product.getStockPrice());
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
    private static BigDecimal decimal(Row row, Map<String,Integer> columns, int heading,
                                      DataFormatter formatter, BigDecimal fallback) {
        Integer column = columns.get(HEADINGS.get(heading)); if (column == null) return fallback;
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || formatter.formatCellValue(cell).isBlank()) return fallback;
        if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
        try { return new BigDecimal(formatter.formatCellValue(cell).trim().replace(',', '.')); }
        catch (NumberFormatException exception) { throw new SSImportException("Ogiltigt tal på rad " + (row.getRowNum() + 1)); }
    }
    private static int integer(Row row, Map<String,Integer> columns, int heading,
                               DataFormatter formatter, int fallback) {
        BigDecimal value = decimal(row, columns, heading, formatter, null);
        return value == null ? fallback : value.intValue();
    }
    private static SSTaxCode taxCode(BigDecimal rate, SSNewCompany company) {
        if (rate.compareTo(BigDecimal.ZERO) == 0) return SSTaxCode.TAXRATE_0;
        if (rate.compareTo(company.getTaxRate1()) == 0) return SSTaxCode.TAXRATE_1;
        if (rate.compareTo(company.getTaxRate2()) == 0) return SSTaxCode.TAXRATE_2;
        if (rate.compareTo(company.getTaxRate3()) == 0) return SSTaxCode.TAXRATE_3;
        throw new SSImportException("Okänd momssats: " + rate.toPlainString());
    }
    private static BigDecimal taxRate(SSTaxCode code, SSNewCompany company) {
        if (code == SSTaxCode.TAXRATE_0) return BigDecimal.ZERO;
        if (code == SSTaxCode.TAXRATE_2) return company.getTaxRate2();
        if (code == SSTaxCode.TAXRATE_3) return company.getTaxRate3();
        return company.getTaxRate1();
    }
    private static boolean empty(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) return false;
        return true;
    }
    private static void string(Row row, int column, String value) { row.createCell(column).setCellValue(value == null ? "" : value); }
    private static void number(Row row, int column, Number value) { if (value != null) row.createCell(column).setCellValue(value.doubleValue()); }
    private static String emptyToNull(String value) { return value.isBlank() ? null : value; }
}
