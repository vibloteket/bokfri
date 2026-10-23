package se.swedsoft.bookkeeping.gui.invoice;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import javax.swing.JDialog;

/** Initial invoice-dialog geometry, in Swing user-space coordinates on its owner's monitor. */
final class SSInvoiceDialogLayout {
    private SSInvoiceDialogLayout() {
    }

    static void sizeAndPosition(JDialog dialog, Component owner) {
        GraphicsConfiguration configuration = owner == null ? dialog.getGraphicsConfiguration()
                : owner.getGraphicsConfiguration();
        if (configuration == null) {
            configuration = dialog.getGraphicsConfiguration();
        }
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        Rectangle screen = configuration.getBounds();
        Rectangle usable = new Rectangle(screen.x + insets.left, screen.y + insets.top,
                Math.max(1, screen.width - insets.left - insets.right),
                Math.max(1, screen.height - insets.top - insets.bottom));
        sizeAndPosition(dialog, owner, usable);
    }

    // The explicit work area also permits deterministic small-screen/negative-origin tests.
    static void sizeAndPosition(JDialog dialog, Component owner, Rectangle usable) {
        dialog.pack();
        Dimension preferred = dialog.getSize();
        Dimension minimum = dialog.getMinimumSize();
        dialog.setMinimumSize(new Dimension(Math.min(minimum.width, usable.width),
                Math.min(minimum.height, usable.height)));
        dialog.setSize(Math.min(Math.max(800, preferred.width), usable.width),
                Math.min(Math.max(600, preferred.height), usable.height));
        dialog.setLocationRelativeTo(owner);
        dialog.setBounds(clamp(dialog.getBounds(), usable));
    }

    static Rectangle clamp(Rectangle desired, Rectangle usable) {
        int width = Math.min(desired.width, usable.width);
        int height = Math.min(desired.height, usable.height);
        int x = Math.max(usable.x, Math.min(desired.x, usable.x + usable.width - width));
        int y = Math.max(usable.y, Math.min(desired.y, usable.y + usable.height - height));
        return new Rectangle(x, y, width, height);
    }
}
