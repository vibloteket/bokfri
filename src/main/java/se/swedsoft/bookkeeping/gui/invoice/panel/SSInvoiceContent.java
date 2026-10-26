package se.swedsoft.bookkeeping.gui.invoice.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/** Scrolls the form when necessary, without squeezing its grid below its computed minimum. */
final class SSInvoiceContent extends JPanel implements Scrollable {
    SSInvoiceContent(Component form) {
        super(new BorderLayout());
        add(form, BorderLayout.CENTER);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        Dimension minimum = getMinimumSize();
        return new Dimension(Math.max(preferred.width, minimum.width),
                Math.max(preferred.height, minimum.height));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(1, getFontMetrics(getFont()).getHeight());
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        int extent = orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        return Math.max(1, extent - getScrollableUnitIncrement(visible, orientation, direction));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && viewport.getWidth() >= getMinimumSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() >= getMinimumSize().height;
    }
}
