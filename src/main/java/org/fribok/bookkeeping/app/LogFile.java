package org.fribok.bookkeeping.app;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/** Locates and opens Bokfri's GUI log file. */
public final class LogFile {
    private static final String LOG_DIRECTORY_PROPERTY = "bokfri.logDir";

    private LogFile() {}

    /** Configure Logback before its first logger is created. */
    public static void configure() {
        System.setProperty(LOG_DIRECTORY_PROPERTY, directory().getAbsolutePath());
    }

    public static File directory() {
        return new File(Path.get(Path.USER_DATA), "logs");
    }

    public static File file() {
        return new File(directory(), "bokfri.log");
    }

    public static void openDirectory() throws IOException {
        File directory = directory();
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Kunde inte skapa loggmappen: " + directory);
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Operativsystemet kan inte öppna loggmappen automatiskt.");
        }
        Desktop.getDesktop().open(directory);
    }
}
