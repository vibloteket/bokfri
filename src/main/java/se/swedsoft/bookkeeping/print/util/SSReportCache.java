package se.swedsoft.bookkeeping.print.util;


import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import se.swedsoft.bookkeeping.util.SSException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-14
 * Time: 17:01:15
 * @version $Id$
 */
public class SSReportCache {    private static final Logger LOG = LoggerFactory.getLogger(SSReportCache.class);

    private static final File REPORT_DIR = new File(Path.get(Path.USER_DATA), "report");
    private static final File COMPILED_DIR = new File(REPORT_DIR, "compiled");
    private static final String REPORT_RESOURCE = "/reports/report/";
    private static final String SHARED_STYLE_RESOURCE = REPORT_RESOURCE + "styles/bokfri.jrtx";
    private static final List<String> FONT_RESOURCES = List.of(
            "/org/fribok/fonts/fonts.xml",
            "/org/fribok/fonts/dejavu/DejaVuSans.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-Oblique.ttf",
            "/org/fribok/fonts/dejavu/DejaVuSans-BoldOblique.ttf",
            "/org/fribok/fonts/ocrb/OCR-B.ttf");
    private static final String CACHE_BUILD_SUFFIX = ".build";

    // The report cache with compiled report definitions.
    private Map<String, JasperReport> iReportCache;

    // our instance
    private static SSReportCache cInstance;

    /**
     * Get the instance of this class
     * @return The instance
     */
    public static SSReportCache getInstance() {
        if (cInstance == null) {
            cInstance = new SSReportCache();
        }
        return cInstance;
    }

    /**
     *
     */
    private SSReportCache() {
        iReportCache = new HashMap<>();
    }

    /**
     * This function will load a report, either from the runtime cache, a
     * precompiled version or from the report source.
     *
     * @param pReportName The name of the report to load, ie vatcontrol.jrxml.
     *
     * @return The JasperReport object
     * @throws SSException
     */
    public JasperReport getReport(String pReportName) throws SSException {
        // Try to get the report from cache
        JasperReport pReport = iReportCache.get(pReportName);

        if (pReport == null) {
            try {
                pReport = loadReport(pReportName);
            } catch (FileNotFoundException ex) {
                throw new SSException(ex.getLocalizedMessage());
            }
            iReportCache.put(pReportName, pReport);
        }
        return pReport;
    }

    /**
     *
     * @param pReportName
     * @return
     * @throws FileNotFoundException
     */
    private JasperReport loadReport(String pReportName) throws FileNotFoundException {
        File iCompiledFile = new File(COMPILED_DIR,
                pReportName.replace(".jrxml", ".jasperreport"));
        String iReportResource = REPORT_RESOURCE + pReportName;

        try {
            byte[] reportSource = readResource(iReportResource);
            String cacheSignature = cacheSignature(reportSource);

            // Reuse a compiled report only when its build, source and shared styles match.
            if (isCompiledReportCurrent(iCompiledFile, cacheSignature)) {
                LOG.info("Loading precompiled report {} from disk...", iCompiledFile);
                return loadCompiledReport(iCompiledFile);
            }
            if (iCompiledFile.exists()) {
                LOG.info("Precompiled report {} is stale; recompiling {}", iCompiledFile,
                        iReportResource);
            }

            LOG.info("Compiling and saving report {} to disk...", iReportResource);

            JasperReport iReport = JasperCompileManager.compileReport(
                    new java.io.ByteArrayInputStream(reportSource));
            saveCompiledReport(iCompiledFile, iReport, cacheSignature);

            return iReport;
        } catch (JRException | IOException ex) {
            throw new SSException("Could not compile report " + pReportName + ": "
                    + ex.getLocalizedMessage());
        }
    }

    private boolean isCompiledReportCurrent(File compiledFile, String cacheSignature) {
        if (!compiledFile.exists()) {
            return false;
        }

        File buildMarkerFile = getBuildMarkerFile(compiledFile);

        if (!buildMarkerFile.exists()) {
            return false;
        }
        try {
            String cachedSignature = Files.readString(
                    buildMarkerFile.toPath(), StandardCharsets.UTF_8).trim();

            return cacheSignature.equals(cachedSignature);
        } catch (IOException e) {
            LOG.error("Unexpected error", e);
        }
        return false;
    }

    private byte[] readResource(String resourceName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new FileNotFoundException(resourceName);
            }
            return input.readAllBytes();
        }
    }

    private String cacheSignature(byte[] reportSource) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            digest.update(Version.APP_BUILD.getBytes(StandardCharsets.UTF_8));
            digest.update(reportSource);
            digest.update(readResource(SHARED_STYLE_RESOURCE));
            for (String fontResource : FONT_RESOURCES) {
                digest.update(readResource(fontResource));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     *
     * @param pCompiledFile
     *
     * @return The report
     */
    private JasperReport loadCompiledReport(File pCompiledFile) {
        try {
            FileInputStream iFileInputStream = new FileInputStream(pCompiledFile);

            ObjectInputStream iObjectInputStream = new ObjectInputStream(
                    new BufferedInputStream(iFileInputStream));

            return (JasperReport) iObjectInputStream.readObject();

        } catch (IOException ex) {
            LOG.error("Unexpected error", ex);
        } catch (ClassNotFoundException ex) {
            LOG.error("Unexpected error", ex);
        }
        return null;
    }

    /**
     *
     * @param pCompiledFile
     * @param pReport
     *
     * @return The report
     */
    private void saveCompiledReport(File pCompiledFile, JasperReport pReport, String cacheSignature) {
        try {
            Files.createDirectories(pCompiledFile.getParentFile().toPath());

            try (ObjectOutputStream iObjectOutputStream = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(pCompiledFile)))) {
                iObjectOutputStream.writeObject(pReport);
            }
            Files.writeString(getBuildMarkerFile(pCompiledFile).toPath(), cacheSignature,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not cache compiled report {}; using in-memory report", pCompiledFile, e);
        }
    }

    private File getBuildMarkerFile(File compiledFile) {
        return new File(compiledFile.getParentFile(), compiledFile.getName() + CACHE_BUILD_SUFFIX);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.util.SSReportCache");
        sb.append("{iReportCache=").append(iReportCache);
        sb.append('}');
        return sb.toString();
    }
}
