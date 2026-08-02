package org.fribok.bookkeeping;

import org.fribok.bookkeeping.cli.BokfriCli;

import java.util.Arrays;

/** Selects the graphical application or the headless CLI before logging starts. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("cli")) {
            System.setProperty("java.awt.headless", "true");
            System.setProperty("logback.configurationFile", "logback-cli.xml");
            BokfriCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Bookkeeping.main(args);
    }
}
