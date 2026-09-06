package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri customer workbooks without GUI or database side effects. */
public final class CustomerSpreadsheetService {
  private static final List<String> HEADINGS =
      List.of(
          "Kund-id",
          "Namn",
          "Telefon1",
          "Telefon2",
          "Fax",
          "Epost",
          "Kontaktperson",
          "Organisationsnummer",
          "Bankgiro",
          "Plusgiro",
          "Fakturaadress.Namn",
          "Fakturaadress.Adress1",
          "Fakturaadress.Adress2",
          "Fakturaadress.Postnummer",
          "Fakturaadress.Postort",
          "Fakturaadress.Land",
          "Leveransadress.Namn",
          "Leveransadress.Adress1",
          "Leveransadress.Adress2",
          "Leveransadress.Postnummer",
          "Leveransadress.Postort",
          "Leveransadress.Land");

  public List<SSCustomer> read(Path input) throws IOException {
    List<Row> rows = FastExcelSupport.readFirstSheet(input);
    if (rows.isEmpty()) throw new SSImportException("Excelbladet innehåller inga kunder.");
    Map<String, Integer> columns = columns(rows.get(0));
    if (!columns.containsKey(HEADINGS.get(0)) || !columns.containsKey(HEADINGS.get(1)))
      throw new SSImportException("Importfilen måste innehålla Kund-id och Namn.");
    List<SSCustomer> customers = new ArrayList<>();
    for (Row row : rows.subList(1, rows.size())) {
      if (FastExcelSupport.empty(row)) continue;
      SSCustomer customer = new SSCustomer();
      customer.setNumber(text(row, columns, 0));
      customer.setName(text(row, columns, 1));
      customer.setPhone1(text(row, columns, 2));
      customer.setPhone2(text(row, columns, 3));
      customer.setTelefax(text(row, columns, 4));
      customer.setEMail(text(row, columns, 5));
      customer.setYourContactPerson(text(row, columns, 6));
      customer.setRegistrationNumber(text(row, columns, 7));
      customer.setBankgiro(text(row, columns, 8));
      customer.setPlusgiro(text(row, columns, 9));
      customer.getInvoiceAddress().setName(text(row, columns, 10));
      customer.getInvoiceAddress().setAddress1(text(row, columns, 11));
      customer.getInvoiceAddress().setAddress2(text(row, columns, 12));
      customer.getInvoiceAddress().setZipCode(text(row, columns, 13));
      customer.getInvoiceAddress().setCity(text(row, columns, 14));
      customer.getInvoiceAddress().setCountry(text(row, columns, 15));
      customer.getDeliveryAddress().setName(text(row, columns, 16));
      customer.getDeliveryAddress().setAddress1(text(row, columns, 17));
      customer.getDeliveryAddress().setAddress2(text(row, columns, 18));
      customer.getDeliveryAddress().setZipCode(text(row, columns, 19));
      customer.getDeliveryAddress().setCity(text(row, columns, 20));
      customer.getDeliveryAddress().setCountry(text(row, columns, 21));
      if (!customer.getNumber().isBlank()) customers.add(customer);
    }
    if (customers.isEmpty()) throw new SSImportException("Excelbladet innehåller inga kunder.");
    return customers;
  }

  public Path write(List<SSCustomer> customers, Path output, boolean overwrite) throws IOException {
    return FastExcelSupport.write(
        output,
        overwrite,
        "Kunder",
        sheet -> {
          FastExcelSupport.header(sheet, HEADINGS);
          int row = 1;
          for (SSCustomer c : customers) {
            String[] values = {
              c.getNumber(),
              c.getName(),
              c.getPhone1(),
              c.getPhone2(),
              c.getTelefax(),
              c.getEMail(),
              c.getYourContactPerson(),
              c.getRegistrationNumber(),
              c.getBankgiro(),
              c.getPlusgiro(),
              c.getInvoiceAddress().getName(),
              c.getInvoiceAddress().getAddress1(),
              c.getInvoiceAddress().getAddress2(),
              c.getInvoiceAddress().getZipCode(),
              c.getInvoiceAddress().getCity(),
              c.getInvoiceAddress().getCountry(),
              c.getDeliveryAddress().getName(),
              c.getDeliveryAddress().getAddress1(),
              c.getDeliveryAddress().getAddress2(),
              c.getDeliveryAddress().getZipCode(),
              c.getDeliveryAddress().getCity(),
              c.getDeliveryAddress().getCountry()
            };
            for (int column = 0; column < values.length; column++)
              FastExcelSupport.string(sheet, row, column, values[column]);
            row++;
          }
        });
  }

  private static Map<String, Integer> columns(Row row) {
    Map<String, Integer> result = new HashMap<>();
    for (int column = 0; column < row.getCellCount(); column++) {
      String heading = FastExcelSupport.text(row, column);
      if (!heading.isBlank()) {
        if (!HEADINGS.contains(heading))
          throw new SSImportException("Ogiltigt kolumnnamn i importfilen: " + heading);
        result.put(heading, column);
      }
    }
    return result;
  }

  private static String text(Row row, Map<String, Integer> columns, int heading) {
    Integer column = columns.get(HEADINGS.get(heading));
    return column == null ? "" : FastExcelSupport.text(row, column);
  }
}
