package se.swedsoft.bookkeeping.gui.util.components;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/** Opt-in, metadata-only diagnostics for searchable dropdown focus. Never records field values or keys. */
public final class SSPopupFocusDiagnostics {
    private static PrintWriter output;
    private static boolean attempted;

    private SSPopupFocusDiagnostics() {
    }

    /** Returns whether the previous popup implementation was requested for comparison. */
    public static boolean isLegacyMode() {
        return Boolean.getBoolean("bokfri.popup.legacy")
                || "1".equals(System.getenv("BOKFRI_POPUP_LEGACY"));
    }

    static void watch(Component field, JPopupMenu popup) {
        if (!enabled()) {
            return;
        }
        initialize();
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                event("field-focus-gained", e.getOppositeComponent());
            }

            @Override
            public void focusLost(FocusEvent e) {
                event("field-focus-lost temporary=" + e.isTemporary(), e.getOppositeComponent());
            }
        });
        popup.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                event("popup-opening focusable=" + popup.isFocusable(), popup);
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                event("popup-closing", popup);
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
                event("popup-canceled", popup);
            }
        });
    }

    static synchronized void event(String name, Component component) {
        if (!enabled()) {
            return;
        }
        initialize();
        if (output != null) {
            output.println(Instant.now() + " " + name + " component=" + describe(component)
                    + " focus=" + describe(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
            output.flush();
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean("bokfri.popup.diagnostics")
                || "1".equals(System.getenv("BOKFRI_POPUP_DIAGNOSTICS"));
    }

    private static synchronized void initialize() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            Path directory = Path.of(System.getProperty("user.home"), ".bokfri", "diagnostics");
            Files.createDirectories(directory);
            Path file = Files.createTempFile(directory, "popup-focus-", ".log");
            output = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
            output.println("Popup focus diagnostics (no field contents), legacy=" + isLegacyMode());
            for (String property : new String[] {"os.name", "os.version", "os.arch", "java.version", "java.vendor"}) {
                output.println(property + "=" + System.getProperty(property));
            }
            output.println("lookAndFeel=" + javax.swing.UIManager.getLookAndFeel().getClass().getName());
            output.flush();
            System.err.println("Popup focus log: " + file);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner",
                    e -> event("focus-owner-change", (Component) e.getNewValue()));
        } catch (IOException | SecurityException e) {
            System.err.println("Cannot create popup focus diagnostics: " + e.getClass().getSimpleName());
        }
    }

    private static String describe(Component component) {
        return component == null ? "null" : component.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(component));
    }
}
