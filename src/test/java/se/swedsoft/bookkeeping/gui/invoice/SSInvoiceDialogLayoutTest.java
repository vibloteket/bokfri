package se.swedsoft.bookkeeping.gui.invoice;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SSInvoiceDialogLayoutTest {
    @Test
    void leavesFittingBoundsAlone() {
        Rectangle desired = new Rectangle(150, 120, 800, 600);
        assertEquals(desired, SSInvoiceDialogLayout.clamp(desired, new Rectangle(0, 30, 1440, 850)));
    }

    @Test
    void fitsOversizedWindowInsideWorkAreaNotUnderTaskbar() {
        assertEquals(new Rectangle(20, 40, 640, 440),
                SSInvoiceDialogLayout.clamp(new Rectangle(-50, -50, 1000, 900),
                        new Rectangle(20, 40, 640, 440)));
    }

    @Test
    void supportsMonitorWithNegativeOrigin() {
        assertEquals(new Rectangle(-1920, 100, 800, 600),
                SSInvoiceDialogLayout.clamp(new Rectangle(-2200, 100, 800, 600),
                        new Rectangle(-1920, 0, 1920, 1080)));
    }

    @Test
    void keepsRightAndBottomEdgesInsideOwnersMonitor() {
        assertEquals(new Rectangle(2640, 380, 800, 600),
                SSInvoiceDialogLayout.clamp(new Rectangle(3300, 900, 800, 600),
                        new Rectangle(2000, 40, 1440, 940)));
    }
}
