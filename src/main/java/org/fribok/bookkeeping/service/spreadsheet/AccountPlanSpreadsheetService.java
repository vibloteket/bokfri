package org.fribok.bookkeeping.service.spreadsheet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import org.dhatim.fastexcel.reader.Row;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;

/** Reads and writes Bokfri account-plan workbooks without GUI or database side effects. */
public final class AccountPlanSpreadsheetService {
  private static final ResourceBundle BUNDLE = SSBundle.getBundle();
  private static final String NAME = BUNDLE.getString("importaccountplan.field_name");
  private static final String TYPE = BUNDLE.getString("importaccountplan.field_type");
  private static final String YEAR = BUNDLE.getString("importaccountplan.field_year");
  private static final String START = BUNDLE.getString("importaccountplan.field_start");
  private static final int ACCOUNT_START_ROW = 5;

  public SSAccountPlan read(Path input) throws IOException {
    Objects.requireNonNull(input, "input");
    return readRows(FastExcelSupport.readFirstSheet(input));
  }

  public SSAccountPlan read(InputStream input) throws IOException {
    Objects.requireNonNull(input, "input");
    return readRows(FastExcelSupport.readFirstSheet(input));
  }

  public Path write(SSAccountPlan plan, Path output, boolean overwrite) throws IOException {
    Objects.requireNonNull(plan, "accountPlan");
    Objects.requireNonNull(output, "output");
    return FastExcelSupport.write(
        output,
        overwrite,
        safeSheetName(plan.getName()),
        sheet -> {
          FastExcelSupport.string(sheet, 0, 0, NAME);
          FastExcelSupport.string(sheet, 0, 1, plan.getName());
          FastExcelSupport.string(sheet, 1, 0, TYPE);
          FastExcelSupport.string(sheet, 1, 1, plan.getType().toString());
          FastExcelSupport.string(sheet, 2, 0, YEAR);
          FastExcelSupport.string(sheet, 2, 1, plan.getAssessementYear());
          FastExcelSupport.string(sheet, 3, 0, START);
          FastExcelSupport.number(sheet, 3, 1, ACCOUNT_START_ROW + 1);
          int row = ACCOUNT_START_ROW;
          for (SSAccount account : plan.getAccounts()) {
            FastExcelSupport.number(sheet, row, 0, account.getNumber());
            FastExcelSupport.string(sheet, row, 1, account.getDescription());
            FastExcelSupport.string(sheet, row, 2, account.getVATCode());
            FastExcelSupport.string(sheet, row, 3, account.getSRUCode());
            FastExcelSupport.string(sheet, row, 4, account.getReportCode());
            row++;
          }
        });
  }

  private SSAccountPlan readRows(List<Row> rows) {
    SSAccountPlan plan = new SSAccountPlan();
    int start = Integer.MAX_VALUE;
    for (Row row : rows) {
      if (FastExcelSupport.empty(row)) continue;
      String marker = FastExcelSupport.text(row, 0);
      if (marker.startsWith(NAME)) plan.setName(FastExcelSupport.text(row, 1));
      else if (marker.startsWith(TYPE)) plan.setType(FastExcelSupport.text(row, 1));
      else if (marker.startsWith(YEAR)) plan.setAssessementYear(FastExcelSupport.text(row, 1));
      else if (marker.startsWith(START)) start = integer(row, 1) - 1;
      else if (row.getRowNum() >= start) {
        SSAccount account = new SSAccount();
        account.setNumber(integer(row, 0));
        account.setDescription(FastExcelSupport.text(row, 1));
        account.setVATCode(FastExcelSupport.text(row, 2));
        account.setSRUCode(FastExcelSupport.text(row, 3));
        account.setReportCode(FastExcelSupport.text(row, 4));
        if (account.getNumber() != null) plan.addAccount(account);
      }
    }
    if (plan.getAccounts().isEmpty())
      throw new SSImportException(BUNDLE, "importaccountplan.fileempty");
    return plan;
  }

  private static int integer(Row row, int column) {
    var number = FastExcelSupport.number(row, column);
    if (number != null) return number.intValue();
    try {
      return Integer.parseInt(FastExcelSupport.text(row, column));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String safeSheetName(String value) {
    String name = value == null || value.isBlank() ? "Kontoplan" : value;
    name = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
    return name.substring(0, Math.min(name.length(), 31));
  }
}
