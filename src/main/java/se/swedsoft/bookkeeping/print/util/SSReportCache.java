package se.swedsoft.bookkeeping.print.util;


import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import se.swedsoft.bookkeeping.util.SSException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-14
 * Time: 17:01:15
 * @version $Id$
 */
public class SSReportCache {
    private static final Logger LOG = LoggerFactory.getLogger(SSReportCache.class);
    private static final String REPORT_RESOURCE = "/reports/report/";

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
    public synchronized JasperReport getReport(String pReportName) throws SSException {
        // Try to get the report from cache
        JasperReport pReport = iReportCache.get(pReportName);

        if (pReport == null) {
            try {
                pReport = loadReport(pReportName);
            } catch (FileNotFoundException ex) {
                throw new SSException("Report template not found: " + pReportName, ex);
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
        String reportResource = REPORT_RESOURCE + pReportName;

        try {
            LOG.info("Compiling report {}...", reportResource);
            return JasperCompileManager.compileReport(
                    new java.io.ByteArrayInputStream(readResource(reportResource)));
        } catch (FileNotFoundException ex) {
            throw ex;
        } catch (JRException | IOException ex) {
            throw new SSException("Could not compile report " + pReportName + ": "
                    + ex.getLocalizedMessage(), ex);
        }
    }

    private byte[] readResource(String resourceName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new FileNotFoundException(resourceName);
            }
            return input.readAllBytes();
        }
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
