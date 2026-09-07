package se.swedsoft.bookkeeping.data.backup.util;


import org.fribok.bookkeeping.app.Path;
import se.swedsoft.bookkeeping.data.system.SSSystemCompany;
import se.swedsoft.bookkeeping.data.system.SSSystemYear;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static se.swedsoft.bookkeeping.data.backup.util.SSBackupZip.ArchiveFile;


/**
 * Date: 2006-mar-03
 * Time: 13:00:54
 */
public class SSBackupUtils {
    private SSBackupUtils() {}

    /**
     *
     * @return
     */
    public static List<ArchiveFile> getFiles() {

        List<ArchiveFile> iFiles = new LinkedList<>();

        // Add the database files
        File dbDir = new File(Path.get(Path.USER_DATA), "db");
        iFiles.add(
                new ArchiveFile(new File(dbDir, "JFSDB.properties")));
        iFiles.add(new ArchiveFile(new File(dbDir, "JFSDB.script")));
        iFiles.add(new ArchiveFile(new File(dbDir, "JFSDB.data")));
        iFiles.add(new ArchiveFile(new File(dbDir, "JFSDB.backup")));
        iFiles.add(new ArchiveFile(new File(dbDir, "JFSDB.log")));
        iFiles.add(new ArchiveFile(new File(dbDir, "JFSDB.lobs")));

        return iFiles;
    }

    /**
     *
     * @param pCompany
     * @return
     */
    public static List<ArchiveFile> getFiles(SSSystemCompany pCompany) {
        List<ArchiveFile> iFiles = new LinkedList<>();

        // Add the company
        // iFiles.add(  new ArchiveFile( SSDB.getInstance().getFile(pCompany.getId())) );

        // Loop through all years
        for (SSSystemYear iYear: pCompany.getYears()) {// Add the year
            // iFiles.add(  new ArchiveFile( SSDB.getInstance().getFile(iYear.getId())) );
        }

        return iFiles;
    }

    /**
     *
     * @param pFilename
     * @param iDirectory
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static List<ArchiveFile> getFiles(String pFilename, String iDirectory) throws FileNotFoundException, IOException {

        List<ArchiveFile> iFiles = new LinkedList<>();

        // Get the names of the files in the zip file
        for (String iName: SSBackupZip.getFiles(pFilename)) {

            // Don't extract the info file
            if (iName.equals("backup.info")) {
                continue;
            }

            File iFile = new File(iDirectory + iName);

            iFiles.add(new ArchiveFile(iFile, iName));
        }
        return iFiles;
    }
}
