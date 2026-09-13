package org.fribok.bookkeeping;

import com.jgoodies.looks.plastic.Plastic3DLookAndFeel;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for platform-specific Look & Feel initialization. */
class BookkeepingLookAndFeelTest {

    @Test
    void linuxPlasticLookAndFeelDoesNotUseWindowsFontPolicy() throws Exception {
        Bookkeeping.configureLookAndFeel("Linux", null);

        assertThat(UIManager.getLookAndFeel()).isInstanceOf(Plastic3DLookAndFeel.class);
        assertThat(UIManager.getFont("Button.font")).isNotNull();
    }
}
