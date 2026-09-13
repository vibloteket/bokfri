package org.fribok.bookkeeping;

import org.fribok.bookkeeping.app.LogFile;
import org.fribok.bookkeeping.cli.BokfriCli;

/** Selects the graphical application or the headless CLI before logging starts. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--gui-smoke-test")) {
            LogFile.configure();
            try {
                GuiSmokeTest.main(new String[0]);
            } catch (Exception exception) {
                throw new IllegalStateException("Packaged GUI smoke test failed", exception);
            }
            return;
        }
        if (args.length == 0) {
            LogFile.configure();
            Bookkeeping.main(args);
            return;
        }
        System.setProperty("java.awt.headless", "true");
        System.setProperty("logback.configurationFile", "logback-cli.xml");
        BokfriCli.main(args);
    }
}
