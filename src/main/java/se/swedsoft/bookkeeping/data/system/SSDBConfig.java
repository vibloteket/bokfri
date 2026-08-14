package se.swedsoft.bookkeeping.data.system;


import org.fribok.bookkeeping.app.Path;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.util.SSXmlUtil;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * $Id$
 */
public class SSDBConfig {    private static final Logger LOG = LoggerFactory.getLogger(SSDBConfig.class);


    private static final File CONFIG_FILE = new File(Path.get(Path.USER_CONF),
            "database.config");

    private static Integer iCompanyId;

    private static Integer iYearId;

    private SSDBConfig() {}

    public static Integer getCompanyId() {
        return iCompanyId;
    }

    public static Integer getCompanyId(File iConfigFile) {
        return readIntegerAttribute(iConfigFile, "company");
    }

    public static void setCompanyId(Integer iId) {
        setCompanyId(CONFIG_FILE, iId);
    }

    public static void setCompanyId(File iConfigFile, Integer iId) {
        if (CONFIG_FILE.equals(iConfigFile)) {
            iCompanyId = iId;
        }

        try {
            Document iDocument = readConfigDocument(iConfigFile);

            iDocument.getDocumentElement().setAttribute("company",
                    iId == null ? "" : iId.toString());
            writeConfigDocument(iDocument, iConfigFile);
        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
        }
    }

    public static Integer getYearId() {
        return iYearId;
    }

    public static Integer getYearId(File iConfigFile) {
        return readIntegerAttribute(iConfigFile, "year");
    }

    public static Integer getYearId(File iConfigFile, Integer iCompanyId) {
        if (iCompanyId == null) {
            return null;
        }
        try {
            Document iDocument = readConfigDocument(iConfigFile);
            NodeList iCompanyElements = iDocument.getDocumentElement().getElementsByTagName("company");

            for (int i = 0; i < iCompanyElements.getLength(); i++) {
                Node iCompanyNode = iCompanyElements.item(i);
                if (iCompanyNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element iCompanyElement = (Element) iCompanyNode;
                    if (iCompanyId.toString().equals(iCompanyElement.getAttribute("id"))) {
                        return parseInteger(iCompanyElement.getAttribute("yearid"));
                    }
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
        }
        return null;
    }

    public static void setYearId(Integer pCompanyId, Integer iId) {
        setYearId(CONFIG_FILE, pCompanyId, iId);
    }

    public static void setYearId(File iConfigFile, Integer pCompanyId, Integer iId) {
        if (CONFIG_FILE.equals(iConfigFile)) {
            iYearId = iId;
        }

        try {
            Document iDocument = readConfigDocument(iConfigFile);

            iDocument.getDocumentElement().setAttribute("year",
                    iId == null ? "" : iId.toString());

            boolean iExists = false;
            NodeList iCompanyElements = iDocument.getDocumentElement().getElementsByTagName(
                    "company");

            for (int i = 0; i < iCompanyElements.getLength(); i++) {
                Node iCompanyNode = iCompanyElements.item(i);

                if (iCompanyNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element iCompanyElement = (Element) iCompanyNode;
                    Integer iCompanyElementId = Integer.parseInt(
                            iCompanyElement.getAttribute("id"));

                    if (iCompanyElementId.equals(pCompanyId)) {
                        iCompanyElement.setAttribute("yearid",
                                iId == null ? "" : iId.toString());
                        iExists = true;
                    }
                }
            }
            if (!iExists) {
                Element iCompanyElement = iDocument.createElement("company");

                iCompanyElement.setAttribute("id", pCompanyId.toString());
                iCompanyElement.setAttribute("yearid", iId == null ? "" : iId.toString());
                iDocument.getDocumentElement().appendChild(iCompanyElement);
            }
            writeConfigDocument(iDocument, iConfigFile);

        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
        }
    }

    public static Optional<SSNewAccountingYear> loadCompanySetting(Integer pCompanyId) {
        if (pCompanyId == null) {
            return Optional.empty();
        }

        try {
            Document iDocument = readConfigDocument();
            NodeList iCompanyElements = iDocument.getDocumentElement().getElementsByTagName(
                    "company");

            for (int i = 0; i < iCompanyElements.getLength(); i++) {
                Node iCompanyNode = iCompanyElements.item(i);

                if (iCompanyNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element iCompanyElement = (Element) iCompanyNode;
                    Integer iCompanyElementId = Integer.parseInt(
                            iCompanyElement.getAttribute("id"));

                    if (iCompanyElementId.equals(pCompanyId)) {
                        String iResult = iCompanyElement.getAttribute("yearid");

                        if (iResult == null || iResult.length() == 0) {
                            return Optional.empty();
                        }
                        SSNewAccountingYear iYear = new SSNewAccountingYear();

                        iYear.setId(Integer.parseInt(iResult));
                        return SSDB.getInstance().getAccountingYear(iYear);
                    }
                }
            }

        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
        }
        return Optional.empty();
    }

    static {
        load();
    }

    /*
     * Create a config file if not found
    */
    private static void createIfNotExists(File iConfigFile)
            throws IOException, ParserConfigurationException, TransformerException {
        File parent = iConfigFile.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!iConfigFile.exists()) {
            LOG.info("Creating database config file.");
            Document iDocument = SSXmlUtil.newDocument();
            Element iRoot = iDocument.createElement("database");

            iDocument.appendChild(iRoot);
            writeConfigDocument(iDocument, iConfigFile);
        }
    }

    /**
     *
     */
    public static void load() {

        try {
            Document iDocument = readConfigDocument();

            String iCompany = null;

            if (iDocument.getDocumentElement().hasAttribute("company")) {
                iCompany = iDocument.getDocumentElement().getAttribute("company");
            }
            if (iCompany != null && iCompany.length() != 0) {
                iCompanyId = Integer.parseInt(iCompany);
            }

            String iYear = null;

            if (iDocument.getDocumentElement().hasAttribute("year")) {
                iYear = iDocument.getDocumentElement().getAttribute("year");
            }
            if (iYear != null && iYear.length() != 0) {
                iYearId = Integer.parseInt(iYear);
            }

        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
        }
    }

    private static Integer readIntegerAttribute(File iConfigFile, String iName) {
        try {
            Document iDocument = readConfigDocument(iConfigFile);
            return parseInteger(iDocument.getDocumentElement().getAttribute(iName));
        } catch (IOException | ParserConfigurationException | SAXException | TransformerException ex) {
            LOG.error("Unexpected error", ex);
            return null;
        }
    }

    private static Integer parseInteger(String iValue) {
        return iValue == null || iValue.isEmpty() ? null : Integer.parseInt(iValue);
    }

    private static Document readConfigDocument()
            throws IOException, ParserConfigurationException, SAXException, TransformerException {
        return readConfigDocument(CONFIG_FILE);
    }

    private static Document readConfigDocument(File iConfigFile)
            throws IOException, ParserConfigurationException, SAXException, TransformerException {
        createIfNotExists(iConfigFile);
        try (FileInputStream iInputStream = new FileInputStream(iConfigFile)) {
            return SSXmlUtil.parse(iInputStream);
        }
    }

    private static void writeConfigDocument(Document iDocument) throws IOException, TransformerException {
        writeConfigDocument(iDocument, CONFIG_FILE);
    }

    private static void writeConfigDocument(Document iDocument, File iConfigFile)
            throws IOException, TransformerException {
        SSXmlUtil.write(iDocument, iConfigFile);
    }
}
