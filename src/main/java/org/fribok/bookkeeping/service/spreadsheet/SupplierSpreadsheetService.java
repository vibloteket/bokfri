package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri supplier workbooks without GUI or database side effects. */
public final class SupplierSpreadsheetService {
  private static final String NUMBER = "Leverantörs-id",
      NAME = "Namn",
      PHONE_1 = "Telefon1",
      PHONE_2 = "Telefon2",
      FAX = "Fax",
      EMAIL = "Epost",
      HOMEPAGE = "Hemsida",
      CONTACT = "Kontaktperson",
      REGISTRATION_NUMBER = "Organisationsnummer",
      OUR_CUSTOMER_NUMBER = "Vårt kundnummer",
      BANKGIRO = "Bankgiro",
      PLUSGIRO = "Plusgiro",
      ADDRESS_NAME = "Adress.Namn",
      ADDRESS_1 = "Adress.Adress1",
      ADDRESS_2 = "Adress.Adress2",
      ZIP_CODE = "Adress.Postnummer",
      CITY = "Adress.Postort",
      COUNTRY = "Adress.Land";
  private static final List<String> HEADINGS =
      List.of(
          NUMBER,
          NAME,
          PHONE_1,
          PHONE_2,
          FAX,
          EMAIL,
          HOMEPAGE,
          CONTACT,
          REGISTRATION_NUMBER,
          OUR_CUSTOMER_NUMBER,
          BANKGIRO,
          PLUSGIRO,
          ADDRESS_NAME,
          ADDRESS_1,
          ADDRESS_2,
          ZIP_CODE,
          CITY,
          COUNTRY);

  public List<SSSupplier> read(Path input) throws IOException {
    List<Row> rows = FastExcelSupport.readFirstSheet(input);
    if (rows.isEmpty()) throw new SSImportException("Excelbladet innehåller inga leverantörer.");
    Map<String, Integer> c = columns(rows.get(0));
    if (!c.containsKey(NUMBER) || !c.containsKey(NAME))
      throw new SSImportException(
          "Importfilen måste innehålla kolumnerna " + NUMBER + " och " + NAME + '.');
    List<SSSupplier> out = new ArrayList<>();
    for (Row row : rows.subList(1, rows.size())) {
      if (FastExcelSupport.empty(row)) continue;
      SSSupplier s = new SSSupplier();
      s.setNumber(get(row, c, NUMBER));
      s.setName(get(row, c, NAME));
      s.setPhone1(get(row, c, PHONE_1));
      s.setPhone2(get(row, c, PHONE_2));
      s.setTelefax(get(row, c, FAX));
      s.setEMail(get(row, c, EMAIL));
      s.setHomepage(get(row, c, HOMEPAGE));
      s.setYourContact(get(row, c, CONTACT));
      s.setRegistrationNumber(get(row, c, REGISTRATION_NUMBER));
      s.setOurCustomerNr(get(row, c, OUR_CUSTOMER_NUMBER));
      s.setBankGiro(get(row, c, BANKGIRO));
      s.setPlusGiro(get(row, c, PLUSGIRO));
      s.getAddress().setName(get(row, c, ADDRESS_NAME));
      s.getAddress().setAddress1(get(row, c, ADDRESS_1));
      s.getAddress().setAddress2(get(row, c, ADDRESS_2));
      s.getAddress().setZipCode(get(row, c, ZIP_CODE));
      s.getAddress().setCity(get(row, c, CITY));
      s.getAddress().setCountry(get(row, c, COUNTRY));
      if (!s.getNumber().isBlank()) out.add(s);
    }
    if (out.isEmpty()) throw new SSImportException("Excelbladet innehåller inga leverantörer.");
    return out;
  }

  public Path write(List<SSSupplier> suppliers, Path output, boolean overwrite) throws IOException {
    return FastExcelSupport.write(
        output,
        overwrite,
        "Leverantörer",
        sheet -> {
          FastExcelSupport.header(sheet, HEADINGS);
          int row = 1;
          for (SSSupplier s : suppliers) {
            String[] v = {
              s.getNumber(),
              s.getName(),
              s.getPhone1(),
              s.getPhone2(),
              s.getTelefax(),
              s.getEMail(),
              s.getHomepage(),
              s.getYourContact(),
              s.getRegistrationNumber(),
              s.getOurCustomerNr(),
              s.getBankgiro(),
              s.getPlusgiro(),
              s.getAddress().getName(),
              s.getAddress().getAddress1(),
              s.getAddress().getAddress2(),
              s.getAddress().getZipCode(),
              s.getAddress().getCity(),
              s.getAddress().getCountry()
            };
            for (int col = 0; col < v.length; col++)
              FastExcelSupport.string(sheet, row, col, v[col]);
            row++;
          }
        });
  }

  private static Map<String, Integer> columns(Row row) {
    Map<String, Integer> r = new LinkedHashMap<>();
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

  private static String get(Row row, Map<String, Integer> columns, String heading) {
    Integer c = columns.get(heading);
    return c == null ? "" : FastExcelSupport.text(row, c);
  }
}
