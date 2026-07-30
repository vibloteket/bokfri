package se.swedsoft.bookkeeping.gui.util.menu;


import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.swing.*;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-27
 * @version $Id$
 */
public class SSMenuLoader {    private static final Logger LOG = LoggerFactory.getLogger(SSMenuLoader.class);


    private Map<String, JMenuBar > iMenuBars;
    private Map<String, JMenu    > iMenus;
    private Map<String, JMenuItem> iMenuItems;

    private Map<String, List<ActionListener>> iActions;

    private Map<String, List<JComponent>> iDependancies;

    /**
     * Default constructor.
     */
    public SSMenuLoader() {
        iMenuBars = new HashMap<>();
        iMenus = new HashMap<>();
        iMenuItems = new HashMap<>();

        iActions = new HashMap<>();
        iDependancies = new HashMap<>();
    }

    /**
     *
     * @param iFile
     */
    public void loadMenus(InputStream stream) {
        iMenuBars.clear();
        iMenus.clear();
        iMenuItems.clear();

        XMLReader iReader;

        try {
            iReader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            LOG.error("Unexpected error", e);
            return;
        }

        iReader.setContentHandler(new MenuBuilder());

        try {
            iReader.parse(new InputSource(stream));
        } catch (SAXException ex) {
            LOG.error("Unexpected error", ex);
        } catch (IOException ex) {
            LOG.error("Unexpected error", ex);
        }
    }

    /**
     * Returns a menubar
     *
     * @param pName
     * @return
     */
    public JMenuBar getMenuBar(String pName) {
        return iMenuBars.get(pName);
    }

    /**
     * Returns a menu or menu item
     *
     * @param pName
     * @return
     */
    public JMenu getMenu(String pName) {
        return iMenus.get(pName);
    }

    /**
     *
     * @param pName
     * @param pAction
     */
    public void addActionListener(String pName, ActionListener pAction) {
        List<ActionListener> iListeners = iActions.get(pName);

        if (iListeners == null) {
            iListeners = new LinkedList<>();

            iActions.put(pName, iListeners);
        }

        iListeners.add(pAction);
    }

    /**
     *
     * @param pGroup
     * @param iComponent
     */
    public void addDependancy(String pGroup, JComponent iComponent) {
        List<JComponent> iComponents = iDependancies.get(pGroup);

        if (iComponents == null) {
            iComponents = new LinkedList<>();

            iDependancies.put(pGroup, iComponents);
        }
        iComponents.add(iComponent);
    }

    /**
     *
     * @param pGroup
     * @param pEnabled
     */
    public void setEnabled(String pGroup, boolean pEnabled) {
        List<JComponent> iComponents = iDependancies.get(pGroup);

        if (iComponents != null) {
            for (JComponent iComponent: iComponents) {
                iComponent.setEnabled(pEnabled);
            }
        }
    }

    /**
     *
     * @param pName
     * @param pAction
     */
    private void notifyActionListeners(String pName, ActionEvent pAction) {
        List<ActionListener> iListeners = iActions.get(pName);

        if (iListeners != null) {
            for (ActionListener iListener: iListeners) {
                iListener.actionPerformed(pAction);
            }
        } else {
            LOG.info("(SSMenuLoader)No listeners for " + pName);
        }

    }

    /**
     *
     * @param pName
     * @return
     */
    private ActionListener createActionListener(final String pName) {
        return e -> notifyActionListeners(pName, e);
    }

    /**
     *
     */
    private class MenuBuilder extends DefaultHandler {

        private JMenuBar iMenuBar;

        private Stack<JMenu> iMenuStack;

        /**
         *
         */
        public MenuBuilder() {
            iMenuBar = null;
            iMenuStack = new Stack<>();
        }

        /**
         * @param iAttributes
         */
        private void createMenuBar(Attributes iAttributes) {
            String iName = iAttributes.getValue("Name");

            iMenuBar = new JMenuBar();
            iMenuBar.setName(iName);

            iMenuBars.put(iName, iMenuBar);
        }

        /**
         * @param iAttributes
         * @return
         */
        private JMenu createMenu(Attributes iAttributes) {
            String iName = iAttributes.getValue("Name");
            String iText = iAttributes.getValue("Text");
            String iBundle = iAttributes.getValue("Bundle");
            String iDependent = iAttributes.getValue("Dependent");

            JMenu iMenu = new JMenu();

            iMenu.setName(iName);

            if (iBundle == null) {
                iMenu.setText(iText);
            } else {
                SSMenuUtils.setupMenu(iMenu, iBundle);
            }

            if (iDependent != null) {
                addDependancy(iDependent, iMenu);
            }

            add(iMenu);

            iMenus.put(iName, iMenu);

            return iMenu;
        }

        /**
         * @param iAttributes
         */
        private void createMenuItem(Attributes iAttributes) {
            String iName = iAttributes.getValue("Name");
            String iText = iAttributes.getValue("Text");
            String iAction = iAttributes.getValue("Action");
            String iBundle = iAttributes.getValue("Bundle");
            String iDependent = iAttributes.getValue("Dependent");

            JMenuItem iMenuItem = new JMenuItem();

            iMenuItem.setName(iName);
            iMenuItem.addActionListener(createActionListener(iAction));

            if (iBundle == null) {
                iMenuItem.setText(iText);
            } else {
                SSMenuUtils.setupMenuItem(iMenuItem, iBundle);
            }

            if (iDependent != null) {
                addDependancy(iDependent, iMenuItem);
            }

            add(iMenuItem);

            iMenuItems.put(iName, iMenuItem);
        }

        /**
         *
         * @param iAttributes
         */
        private void createSeparator(Attributes iAttributes) {
            if (!iMenuStack.isEmpty()) {
                JMenu iParent = iMenuStack.peek();

                iParent.addSeparator();
            }
        }

        /**
         *
         * @param iComponent
         */
        private void add(JComponent iComponent) {
            if (!iMenuStack.isEmpty()) {
                JMenu iParent = iMenuStack.peek();

                iParent.add(iComponent);
            } else {
                if (iMenuBar != null) {
                    iMenuBar.add(iComponent);
                }
            }
        }

        /**
         *
         * @param uri
         * @param localName
         * @param qName
         * @param iAttributes
         * @throws SAXException
         */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes iAttributes) throws SAXException {

            if (localName.equalsIgnoreCase("MenuBar")) {
                createMenuBar(iAttributes);
            }

            if (localName.equalsIgnoreCase("Menu")) {
                JMenu iMenu = createMenu(iAttributes);

                iMenuStack.push(iMenu);
            }

            if (localName.equalsIgnoreCase("MenuItem")) {
                createMenuItem(iAttributes);
            }

            if (localName.equalsIgnoreCase("Separator")) {
                createSeparator(iAttributes);

            }

        }

        /**
         *
         * @param uri
         * @param localName
         * @param qName
         * @throws SAXException
         */
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (localName.equalsIgnoreCase("MenuBar")) {
                iMenuBar = null;
            }

            if (localName.equalsIgnoreCase("Menu")) {
                iMenuStack.pop();
            }

        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append("se.swedsoft.bookkeeping.gui.util.menu.SSMenuLoader.MenuBuilder");
            sb.append("{iMenuBar=").append(iMenuBar);
            sb.append(", iMenuStack=").append(iMenuStack);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.util.menu.SSMenuLoader");
        sb.append("{iActions=").append(iActions);
        sb.append(", iDependancies=").append(iDependancies);
        sb.append(", iMenuBars=").append(iMenuBars);
        sb.append(", iMenuItems=").append(iMenuItems);
        sb.append(", iMenus=").append(iMenus);
        sb.append('}');
        return sb.toString();
    }
}
