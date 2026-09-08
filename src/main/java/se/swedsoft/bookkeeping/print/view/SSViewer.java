package se.swedsoft.bookkeeping.print.view;


import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SSViewer extends JPanel {    private static final Logger LOG = LoggerFactory.getLogger(SSViewer.class);


    private Map<String, List<PropertyChangeListener>> iListenerMap;

    private JPanel iPagePanel = new JPanel();

    private SSDocumentPanel iDocumentPanel = new SSDocumentPanel();

    private JasperPrint iJasperPrint;

    private int  iZoom = 100;

    private int  iPageIndex;

    private double iDeviceScale = -1.0;

    /**
     *
     */
    public SSViewer() {
        iListenerMap = new HashMap<>();

        iPagePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        iPagePanel.add(iDocumentPanel.getPanel());

        setLayout(new BorderLayout());
        add(iPagePanel, BorderLayout.CENTER);

        createMouseEvents();
    }

    /**
     *
     * @param pJasperPrint
     */
    public void setPrinter(JasperPrint pJasperPrint) {
        iJasperPrint = pJasperPrint;

        notifyPropertyChangeListeners("page_change");
        notifyPropertyChangeListeners("page_zoom");

        iZoom = 100;
        refreshPage();
    }

    /**
     * Navigate to the first page
     */
    public void firstPage() {
        setCurrentPage(0);
    }

    /**
     * Navigate to the previous page
     */
    public void prevPage() {
        int index = iPageIndex - 1;

        setCurrentPage(index);
    }

    /**
     * Navigate to the next page
     */
    public void nextPage() {
        int index = iPageIndex + 1;

        setCurrentPage(index);
    }

    /**
     *  Navigate to the last page
     */
    public void lastPage() {
        setCurrentPage(getPageCount() - 1);
    }

    /**
     *
     * @return
     */
    public int getPageCount() {
        if (iJasperPrint != null && iJasperPrint.getPages() != null) {
            return iJasperPrint.getPages().size();
        } else {
            return 0;
        }

    }

    /**
     * Get the zoom level, in percent
     *
     * @return The zoom
     */
    public int getZoom() {
        return iZoom;
    }

    /**
     * Set the zoom level, in percent
     *
     * @param pZoom
     */
    public void setZoom(int pZoom) {
        if (iZoom != pZoom) {
            ReportPreviewScale.logicalScale(pZoom);
            iZoom = pZoom;

            notifyPropertyChangeListeners("page_zoom");

            refreshPage();
        }
    }

    /**
     * Set the current page
     *
     * @param pPageIndex
     */
    public void setCurrentPage(int pPageIndex) {
        // Check so we stay in range
        if (pPageIndex >= 0 && pPageIndex < getPageCount() && (iPageIndex != pPageIndex)) {
            iPageIndex = pPageIndex;

            notifyPropertyChangeListeners("page_change");

            refreshPage();
        }
    }

    /**
     * Get the current page
     *
     * @return
     */
    public int getCurrentPage() {
        return iPageIndex;
    }

    /**
     *
     * @param pProperty
     * @param pPropertyChangeListener
     */
    @Override
    public void addPropertyChangeListener(String pProperty, PropertyChangeListener pPropertyChangeListener) {

        List<PropertyChangeListener> iPropertyChangeListeners = iListenerMap.get(pProperty);

        if (iPropertyChangeListeners == null) {
            iPropertyChangeListeners = new LinkedList<>();
            iListenerMap.put(pProperty, iPropertyChangeListeners);
        }
        iPropertyChangeListeners.add(pPropertyChangeListener);
    }

    /**
     *
     * @param pProperty
     */
    private void notifyPropertyChangeListeners(String pProperty) {
        PropertyChangeEvent iEvent = new PropertyChangeEvent(this, pProperty, null, null);

        List<PropertyChangeListener> iPropertyChangeListeners = iListenerMap.get(pProperty);

        if (iPropertyChangeListeners != null) {
            for (PropertyChangeListener iPropertyChangeListener: iPropertyChangeListeners) {
                iPropertyChangeListener.propertyChange(iEvent);
            }
        }
    }

    /**
     *
     */
    private void refreshPage() {
        if (iJasperPrint == null) {
            return;
        }

        try {
            iDeviceScale = ReportPreviewScale.deviceScale(getGraphicsConfiguration());
            float renderScale = ReportPreviewScale.renderScale(iZoom, iDeviceScale);
            Image image = JasperPrintManager.printPageToImage(iJasperPrint, iPageIndex,
                    renderScale);

            Dimension iSize = ReportPreviewScale.logicalPageSize(
                    iJasperPrint.getPageWidth(), iJasperPrint.getPageHeight(), iZoom);
            Dimension iPageSize = new Dimension(iSize.width + 28, iSize.height + 28);

            iDocumentPanel.setDocument(image, iSize);

            iPagePanel.setLocation(0, 0);
            iPagePanel.setSize(iPageSize);

            setMaximumSize(iPageSize);
            setMinimumSize(iPageSize);
            setPreferredSize(iPageSize);
        } catch (JRException e) {
            LOG.error("Unexpected error", e);
        }

        Container container = getParent();

        if (container instanceof JViewport) {
            ((JViewport) container).setViewPosition(new Point(0, 0));
        }

        SwingUtilities.invokeLater(() -> getParent().repaint());

    }

    @Override
    public void paint(Graphics graphics) {
        double deviceScale = ReportPreviewScale.deviceScale(getGraphicsConfiguration());
        if (iJasperPrint != null && Double.compare(iDeviceScale, deviceScale) != 0) {
            refreshPage();
        }
        super.paint(graphics);
    }

    /**
     *
     */
    private void createMouseEvents() {
        final Point iMousePoint = new Point();

        addMouseListener(new MouseListener() {
            public void mouseClicked(MouseEvent e) {}

            public void mousePressed(MouseEvent e) {
                setCursor(new Cursor(Cursor.MOVE_CURSOR));
                iMousePoint.x = e.getX();
                iMousePoint.y = e.getY();
            }

            public void mouseReleased(MouseEvent e) {
                setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }

            public void mouseEntered(MouseEvent e) {}

            public void mouseExited(MouseEvent e) {}
        });

        addMouseMotionListener(
                new MouseMotionListener() {
            public void mouseDragged(MouseEvent e) {

                Point iDelta = new Point(e.getX() - iMousePoint.x,
                        e.getY() - iMousePoint.y);

                Container container = getParent();

                if (container instanceof JViewport) {
                    JViewport viewport = (JViewport) container;

                    Point     iViewPosition = viewport.getViewPosition();

                    int maxX = iPagePanel.getWidth() - viewport.getWidth();
                    int maxY = iPagePanel.getHeight() - viewport.getHeight();

                    int newX = Math.max(0, Math.min(maxX, iViewPosition.x - iDelta.x));
                    int newY = Math.max(0, Math.min(maxY, iViewPosition.y - iDelta.y));

                    viewport.setViewPosition(new Point(newX, newY));
                }
            }

            public void mouseMoved(MouseEvent e) {}
        });
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.view.SSViewer");
        sb.append("{iDocumentPanel=").append(iDocumentPanel);
        sb.append(", iJasperPrint=").append(iJasperPrint);
        sb.append(", iListenerMap=").append(iListenerMap);
        sb.append(", iPageIndex=").append(iPageIndex);
        sb.append(", iPagePanel=").append(iPagePanel);
        sb.append(", iDeviceScale=").append(iDeviceScale);
        sb.append(", iZoom=").append(iZoom);
        sb.append('}');
        return sb.toString();
    }
}
