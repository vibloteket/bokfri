package org.fribok.bookkeeping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;

import static org.assertj.core.api.Assertions.assertThat;

class BookkeepingTest {
    private final Object menuItemOffset = UIManager.get("MenuItem.minimumTextOffset");
    private final Object menuOffset = UIManager.get("Menu.minimumTextOffset");

    @AfterEach
    void restoreUiDefaults() {
        restore("MenuItem.minimumTextOffset", menuItemOffset);
        restore("Menu.minimumTextOffset", menuOffset);
    }

    @Test
    void removesMinimumMenuTextOffsetOnWindows() {
        UIManager.put("MenuItem.minimumTextOffset", 24);
        UIManager.put("Menu.minimumTextOffset", 24);

        Bookkeeping.configurePlatformMenuLayout("Windows 11");

        assertThat(UIManager.getInt("MenuItem.minimumTextOffset")).isZero();
        assertThat(UIManager.getInt("Menu.minimumTextOffset")).isZero();
    }

    @Test
    void leavesMenuTextOffsetUnchangedOnOtherPlatforms() {
        UIManager.put("MenuItem.minimumTextOffset", 24);
        UIManager.put("Menu.minimumTextOffset", 24);

        Bookkeeping.configurePlatformMenuLayout("Linux");

        assertThat(UIManager.getInt("MenuItem.minimumTextOffset")).isEqualTo(24);
        assertThat(UIManager.getInt("Menu.minimumTextOffset")).isEqualTo(24);
    }

    private static void restore(String key, Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, value);
        }
    }
}
