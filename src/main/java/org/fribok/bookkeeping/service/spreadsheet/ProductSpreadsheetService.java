package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.common.*;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri product workbooks without GUI or database side effects. */
public final class ProductSpreadsheetService {
  private static final List<String> HEADINGS =
      List.of(
          "Produkt-id",
          "Beskrivning",
          "Försäljningspris",
          "Inköpspris",
          "Enhetsfrakt",
          "Moms",
          "Enhet",
          "Vikt",
          "Volym",
          "Leverantör",
          "Leverantörens artikelnummer",
          "Beställningspunkt",
          "Lagerplats",
          "Lagerantal",
          "Disponibelt",
          "Lagerpris");

  public List<SSProduct> read(Path input, SSNewCompany company, List<SSUnit> units)
      throws IOException {
    Objects.requireNonNull(company, "company");
    List<Row> rows = FastExcelSupport.readFirstSheet(input);
    if (rows.isEmpty()) throw new SSImportException("Excelbladet innehåller inga produkter.");
    Map<String, Integer> columns = columns(rows.get(0));
    if (!columns.containsKey(HEADINGS.get(0)) || !columns.containsKey(HEADINGS.get(1)))
      throw new SSImportException("Importfilen måste innehålla Produkt-id och Beskrivning.");
    List<SSProduct> products = new ArrayList<>();
    for (Row row : rows.subList(1, rows.size())) {
      if (FastExcelSupport.empty(row)) continue;
      SSProduct product = new SSProduct();
      product.setNumber(text(row, columns, 0));
      product.setDescription(text(row, columns, 1));
      product.setSellingPrice(decimal(row, columns, 2, BigDecimal.ZERO));
      product.setPurchasePrice(decimal(row, columns, 3, BigDecimal.ZERO));
      product.setUnitFreight(decimal(row, columns, 4, BigDecimal.ZERO));
      product.setTaxCode(taxCode(decimal(row, columns, 5, BigDecimal.ZERO), company));
      String unitName = text(row, columns, 6);
      product.setUnit(
          units.stream()
              .filter(u -> unitName.equals(u.getName()) || unitName.equals(u.getDescription()))
              .findFirst()
              .orElse(null));
      product.setWeight(decimal(row, columns, 7, BigDecimal.ZERO));
      product.setVolume(decimal(row, columns, 8, BigDecimal.ZERO));
      product.setSupplierNr(emptyToNull(text(row, columns, 9)));
      product.setSupplierProductNr(emptyToNull(text(row, columns, 10)));
      product.setOrderpoint(integer(row, columns, 11, 0));
      product.setWarehouseLocation(emptyToNull(text(row, columns, 12)));
      product.setStockPrice(decimal(row, columns, 15, BigDecimal.ZERO));
      if (!product.getNumber().isBlank()) products.add(product);
    }
    if (products.isEmpty()) throw new SSImportException("Excelbladet innehåller inga produkter.");
    return products;
  }

  public Path write(
      List<SSProduct> products, SSStock stock, SSNewCompany company, Path output, boolean overwrite)
      throws IOException {
    return FastExcelSupport.write(
        output,
        overwrite,
        "Produkter",
        sheet -> {
          FastExcelSupport.header(sheet, HEADINGS);
          int row = 1;
          for (SSProduct p : products) {
            FastExcelSupport.string(sheet, row, 0, p.getNumber());
            FastExcelSupport.string(sheet, row, 1, p.getDescription());
            FastExcelSupport.number(sheet, row, 2, p.getSellingPrice());
            FastExcelSupport.number(sheet, row, 3, p.getPurchasePrice());
            FastExcelSupport.number(sheet, row, 4, p.getUnitFreight());
            FastExcelSupport.number(sheet, row, 5, taxRate(p.getTaxCode(), company));
            FastExcelSupport.string(
                sheet, row, 6, p.getUnit() == null ? null : p.getUnit().getName());
            FastExcelSupport.number(sheet, row, 7, p.getWeight());
            FastExcelSupport.number(sheet, row, 8, p.getVolume());
            FastExcelSupport.string(sheet, row, 9, p.getSupplierNr());
            FastExcelSupport.string(sheet, row, 10, p.getSupplierProductNr());
            FastExcelSupport.number(sheet, row, 11, p.getOrderpoint());
            FastExcelSupport.string(sheet, row, 12, p.getWarehouseLocation());
            FastExcelSupport.number(sheet, row, 13, stock.getQuantity(p));
            FastExcelSupport.number(sheet, row, 14, stock.getAvaiable(p));
            FastExcelSupport.number(sheet, row, 15, p.getStockPrice());
            row++;
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
    return r;
  }

  private static String text(Row row, Map<String, Integer> columns, int heading) {
    Integer c = columns.get(HEADINGS.get(heading));
    return c == null ? "" : FastExcelSupport.text(row, c);
  }

  private static BigDecimal decimal(
      Row row, Map<String, Integer> columns, int heading, BigDecimal fallback) {
    Integer c = columns.get(HEADINGS.get(heading));
    if (c == null || FastExcelSupport.text(row, c).isBlank()) return fallback;
    BigDecimal n = FastExcelSupport.number(row, c);
    if (n == null) throw new SSImportException("Ogiltigt tal på rad " + row.getRowNum());
    return n;
  }

  private static int integer(Row row, Map<String, Integer> columns, int heading, int fallback) {
    BigDecimal n = decimal(row, columns, heading, null);
    return n == null ? fallback : n.intValue();
  }

  private static SSTaxCode taxCode(BigDecimal rate, SSNewCompany c) {
    if (rate.compareTo(BigDecimal.ZERO) == 0) return SSTaxCode.TAXRATE_0;
    if (rate.compareTo(c.getTaxRate1()) == 0) return SSTaxCode.TAXRATE_1;
    if (rate.compareTo(c.getTaxRate2()) == 0) return SSTaxCode.TAXRATE_2;
    if (rate.compareTo(c.getTaxRate3()) == 0) return SSTaxCode.TAXRATE_3;
    throw new SSImportException("Okänd momssats: " + rate.toPlainString());
  }

  private static BigDecimal taxRate(SSTaxCode code, SSNewCompany c) {
    if (code == SSTaxCode.TAXRATE_0) return BigDecimal.ZERO;
    if (code == SSTaxCode.TAXRATE_2) return c.getTaxRate2();
    if (code == SSTaxCode.TAXRATE_3) return c.getTaxRate3();
    return c.getTaxRate1();
  }

  private static String emptyToNull(String v) {
    return v.isBlank() ? null : v;
  }
}
