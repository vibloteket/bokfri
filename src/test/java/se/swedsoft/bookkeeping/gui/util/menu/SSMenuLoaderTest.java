package se.swedsoft.bookkeeping.gui.util.menu;

import java.io.InputStream;
import javax.swing.JMenuBar;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for loading the main menu from XML.
 */
class SSMenuLoaderTest {

    @Test
    void loadsMainMenu() {
        SSMenuLoader loader = new SSMenuLoader();

        try (InputStream stream = getClass().getResourceAsStream("/MainMenu.xml")) {
            assertThat(stream).isNotNull();
            loader.loadMenus(stream);
        } catch (Exception e) {
            throw new AssertionError("Could not load the main menu", e);
        }

        JMenuBar menuBar = loader.getMenuBar("MainMenu");
        assertThat(menuBar).isNotNull();
        assertThat(menuBar.getMenuCount()).isPositive();
    }
}
