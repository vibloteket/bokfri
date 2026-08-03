package org.fribok.bookkeeping;

import org.fribok.bookkeeping.cli.BokfriCli;

/** Selects the graphical application or the headless CLI before logging starts. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            Bookkeeping.main(args);
            return;
        }
        System.setProperty("java.awt.headless", "true");
        System.setProperty("logback.configurationFile", "logback-cli.xml");
        BokfriCli.main(args);
    }
}
