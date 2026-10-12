package se.swedsoft.bookkeeping.importexport.sie.fields;


import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.gui.util.SSBundleString;
import se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter;
import se.swedsoft.bookkeeping.importexport.sie.SSSIEImporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIELabel;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEReader;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEWriter;
import se.swedsoft.bookkeeping.importexport.util.SSExportException;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import java.time.LocalDate;
import java.util.List;

import static se.swedsoft.bookkeeping.importexport.sie.util.SIEReader.SIEDataType.STRING;


/**
 * Date: 2006-feb-24
 * Time: 09:04:07
 */
public class SIEEntryVerifikation implements SIEEntry {

    /**
     * Imports the entry
     *
     * @param iImporter
     * @param iReader
     * @return If anything was imported
     * @throws SSImportException
     *
     */
    @Override
    public boolean importEntry(SSSIEImporter iImporter, SIEReader iReader, SSNewAccountingYear iYearData) throws SSImportException {
        // #VER serie vernr [verdatum] [vertext] [regdatum]
        if (!iReader.hasFields(STRING, STRING, STRING)) {
            throw new SSImportException(
                    SSBundleString.getString("sieimport.fielderror", iReader.peekLine()));
        }

        SSVoucher iVoucher = new SSVoucher();

        String     iSerie = iReader.nextString();
        Integer    iNumber = iReader.nextInteger().orElse(null);
        LocalDate  iDate = iReader.hasNextDate() ? iReader.nextDate() : SSDateUtil.today();
        String     iDescription = iReader.hasNextString() ? iReader.nextString() : null;

        if (iNumber != null && !iSerie.equals("A") && !iSerie.isEmpty()) {
            if (iSerie.charAt(0) - 'A' < 0) {
                iNumber = iNumber + (10000 * iSerie.charAt(0)) + 2000000;
            } else {
                iNumber = iNumber + (10000 * (iSerie.charAt(0) - 'A')) + 1000000;
            }
        }
        boolean iHasNumber = false;

        if (iNumber != null && !iImporter.hasVoucherNumber(iNumber)) {
            iVoucher.setNumber(iNumber);
            iHasNumber = true;
        }

        iVoucher.setLocalDate(iDate);
        iVoucher.setDescription(iDescription);

        while (iReader.hasNextLine()) {
            iReader.nextLine();

            String iLabel = iReader.next();

            SIEEntry iEntry = iImporter.getFactory().get(iLabel);

            if (iEntry == null || !(iEntry instanceof SIEEntryTransaktion)) {
                throw new SSImportException("Row of voucher is not #TRANS");
            }

            ((SIEEntryTransaktion) iEntry).importEntry(iVoucher, iImporter, iReader,
                    iYearData);
        }
        iImporter.addVoucher(iVoucher, iHasNumber);
        return true;
    }

    /**
     * Exports the entry
     *
     * @param iExporter
     * @param iWriter
     * @return If anything was exported
     * @throws SSExportException
     *
     */
    @Override
    public boolean exportEntry(SSSIEExporter iExporter, SIEWriter iWriter, SSNewAccountingYear iCurrentYearData) throws SSExportException {
        List<SSVoucher> iVouchers = iExporter.getVouchers();

        SIEEntryTransaktion iEntry = new SIEEntryTransaktion();

        // #VER serie vernr [verdatum] [vertext] [regdatum]
        for (SSVoucher iVoucher: iVouchers) {
            iWriter.append(SIELabel.SIE_VER);
            iWriter.append("A"); // Serie A
            iWriter.append(iVoucher.getNumber());
            iWriter.append(iVoucher.getLocalDate());
            iWriter.append(iVoucher.getDescription());
            iWriter.newLine();

            iWriter.newLine("{");
            iEntry.exportEntry(iVoucher, iExporter, iWriter);
            iWriter.newLine("}");
        }

        return !iVouchers.isEmpty();
    }
}
