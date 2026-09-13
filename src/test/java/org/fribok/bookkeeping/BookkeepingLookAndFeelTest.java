package org.fribok.bookkeeping;

import com.jgoodies.looks.plastic.Plastic3DLookAndFeel;
import org.junit.jupiter.api.Test;

import javax.swing.LookAndFeel;

import javax.swing.UIManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for platform-specific Look & Feel initialization. */
class BookkeepingLookAndFeelTest {

    @Test
    void linuxKeepsPlasticOnEveryDesktopWithoutWindowsFontPolicy() throws Exception {
        for (String desktop : new String[] {null, "", "Unity", "XFCE", "GNOME", "X-Cinnamon",
                "LXDE", "KDE", "ubuntu:GNOME"}) {
            assertLinuxTheme(desktop);
        }
    }

    private void assertLinuxTheme(String desktop) throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            Bookkeeping.configureLookAndFeel("Linux", desktop);

            assertThat(UIManager.getLookAndFeel()).isInstanceOf(Plastic3DLookAndFeel.class);
            assertThat(UIManager.getFont("Button.font")).isNotNull();
            assertThat(UIManager.getFont("Table.font")).isNotNull();
            assertThat(UIManager.getFont("InternalFrame.titleFont")).isNotNull();
        } finally {
            UIManager.setLookAndFeel(previous);
        }
    }
}
