package org.fribok.bookkeeping;

/** Uses the same platform Look & Feel as the application in cross-package GUI tests. */
public final class GuiTestLookAndFeel {
    private GuiTestLookAndFeel() {
    }

    /** Configures the application's Look & Feel. Call on the event dispatch thread. */
    public static void configure() throws Exception {
        Bookkeeping.configureLookAndFeel(System.getProperty("os.name"), System.getenv("XDG_CURRENT_DESKTOP"));
    }
}
