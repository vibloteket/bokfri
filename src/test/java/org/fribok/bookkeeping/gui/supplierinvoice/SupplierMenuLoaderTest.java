package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.util.menu.SSMenuLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.swing.SwingUtilities;

class SupplierMenuLoaderTest {
    @Test
    void realSupplierMenuItemsAreResolvedByActionAndReloadDoesNotKeepStaleItems() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var loader = new SSMenuLoader();
                    loader.loadMenus(getClass().getResourceAsStream("/MainMenu.xml"));
                    assertNotNull(loader.getMenuItemForAction("salemenu.supplierinvoice"));
                    assertNotNull(loader.getMenuItemForAction("salemenu.suppliercreditinvoice"));
                    assertNull(loader.getMenuItemForAction("unknown"));
                    loader.loadMenus(
                            new ByteArrayInputStream(
                                    ("<Menus><MenuBar Name=\"Test\"><Menu Name=\"X\""
                                         + " Text=\"X\"><MenuItem Name=\"Y\" Text=\"Y\""
                                         + " Action=\"replacement\"/></Menu></MenuBar></Menus>")
                                            .getBytes(StandardCharsets.UTF_8)));
                    assertNull(loader.getMenuItemForAction("salemenu.supplierinvoice"));
                    assertNotNull(loader.getMenuItemForAction("replacement"));
                });
    }
}
