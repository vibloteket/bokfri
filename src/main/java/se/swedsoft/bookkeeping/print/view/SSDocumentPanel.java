package se.swedsoft.bookkeeping.print.view;


import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;


/**
 * Date: 2006-feb-16
 * Time: 14:25:25
 */
public class SSDocumentPanel {

    private JPanel iPanel;

    private JPanel iPage;

    private ImagePanel iImagePanel;

    private Image iImage;

    private Dimension iDocumentSize;

    /**
     *
     */
    public SSDocumentPanel() {
        iPanel.setBackground(Color.gray);

        iImagePanel = new ImagePanel();
        iImagePanel.setOpaque(true);
        iImagePanel.setBorder(BorderFactory.createLineBorder(Color.BLACK));

        iPage.setLayout(new BorderLayout());
        iPage.add(iImagePanel, BorderLayout.CENTER);
    }

    /**
     *
     * @param iImage
     * @param iSize
     */
    public void setDocument(Image iImage, Dimension iSize) {
        this.iImage = iImage;
        iDocumentSize = new Dimension(iSize);

        Dimension iPanelSize = new Dimension(iSize.width + 4, iSize.height + 4);

        iPage.setMaximumSize(iSize);
        iPage.setMinimumSize(iSize);
        iPage.setPreferredSize(iSize);

        iPanel.setMaximumSize(iPanelSize);
        iPanel.setMinimumSize(iPanelSize);
        iPanel.setPreferredSize(iPanelSize);

        iImagePanel.revalidate();
        iImagePanel.repaint();

    }

    /**
     *
     * @return
     */
    public JPanel getPanel() {
        return iPanel;
    }

    private class ImagePanel extends JPanel {

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (iImage == null || iDocumentSize == null) {
                return;
            }

            Graphics2D graphics2D = (Graphics2D) graphics.create();
            try {
                graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics2D.drawImage(iImage, 1, 1, iDocumentSize.width, iDocumentSize.height, this);
            } finally {
                graphics2D.dispose();
            }
        }

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.view.SSDocumentPanel");
        sb.append("{iImage=").append(iImage);
        sb.append(", iDocumentSize=").append(iDocumentSize);
        sb.append(", iImagePanel=").append(iImagePanel);
        sb.append(", iPage=").append(iPage);
        sb.append(", iPanel=").append(iPanel);
        sb.append('}');
        return sb.toString();
    }
}

