package org.fribok.bookkeeping;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

import org.fribok.bookkeeping.app.LogFile;
import org.fribok.bookkeeping.cli.BokfriCli;

/** Selects the graphical application or the headless CLI before logging starts. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        registerReportFont("/org/fribok/fonts/OCRA.ttf");
        registerReportFont("/org/fribok/fonts/OCRB.ttf");
        if (args.length == 0) {
            LogFile.configure();
            Bookkeeping.main(args);
            return;
        }
        System.setProperty("java.awt.headless", "true");
        System.setProperty("logback.configurationFile", "logback-cli.xml");
        BokfriCli.main(args);
    }

    private static void registerReportFont(String resource) {
        try (InputStream stream = Launcher.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled report font: " + resource);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not register bundled report font " + resource,
                    exception);
        }
    }
}
