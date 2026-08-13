package org.fribok.bookkeeping;

import com.jgoodies.looks.plastic.Plastic3DLookAndFeel;
import com.jgoodies.looks.plastic.PlasticLookAndFeel;
import com.jgoodies.looks.FontPolicy;
import com.jgoodies.looks.FontPolicies;
import com.jgoodies.looks.FontSet;
import com.jgoodies.looks.FontSets;

import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.DataMigrationRequiredException;
import org.fribok.bookkeeping.dataformat.DataMigrationService;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.util.SSConfig;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.frame.SSFrameManager;
import se.swedsoft.bookkeeping.gui.util.graphics.SSIcon;

import javax.swing.*;
import java.awt.Cursor;
import java.awt.Font;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @version $Id$
 */
public class Bookkeeping {    private static final Logger LOG = LoggerFactory.getLogger(Bookkeeping.class);


    public static boolean iRunning;

    private Bookkeeping() {}

    /**
     *
     */
    private static boolean startupDatabase() {
        return startupDatabase(false);
    }

    private static boolean startupDatabase(boolean migrationApproved) {
        try {
            Class.forName("org.hsqldb.jdbcDriver");
        } catch (ClassNotFoundException e) {
            LOG.info("ERROR: failed to load HSQLDB JDBC driver.");
            LOG.error("Unexpected error", e);
            return false;
        }

        Connection connection = null;
        try {
            File dbDir = new File(Path.get(Path.USER_DATA), "db");
            boolean databaseExisted = new File(dbDir, "JFSDB.properties").exists();
            connection = DriverManager.getConnection(
                    "jdbc:hsqldb:file:" + dbDir.getAbsolutePath() + File.separator + "JFSDB", "sa", "");
            connection.setAutoCommit(false);
            try {
                DataFormatManager.checkAndInitialize(connection, databaseExisted);
            } catch (DataMigrationRequiredException exception) {
                connection.close();
                connection = null;
                if (migrationApproved || !approveMigration(exception)) {
                    return false;
                }
                java.nio.file.Path backup = DataMigrationService.migrate(
                        Path.get(Path.USER_DATA).toPath());
                JOptionPane.showMessageDialog(null,
                        "Företaget har uppgraderats till dataformat "
                                + DataFormatManager.CURRENT_DATA_FORMAT_VERSION
                                + ".\nSäkerhetskopian finns i:\n" + backup,
                        "Uppgraderingen är klar", JOptionPane.INFORMATION_MESSAGE);
                return startupDatabase(true);
            }
            SSDB.getInstance().startupLocal(connection);
            return true;
        } catch (SQLException | java.io.IOException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeException) {
                    e.addSuppressed(closeException);
                }
            }
            LOG.error("Failed to start local database", e);
            JOptionPane.showMessageDialog(null,
                    "Bokfri kunde inte öppna databasen. Ingen data har ändrats.\n\n"
                            + e.getMessage(),
                    "Databasen kunde inte öppnas", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static boolean approveMigration(DataMigrationRequiredException exception) {
        String message = "Företaget använder dataformat " + exception.getFoundVersion()
                + " och måste uppgraderas till format " + exception.getRequiredVersion() + ".\n\n"
                + "Bokfri skapar och verifierar automatiskt en fullständig säkerhetskopia först.\n"
                + "Efter uppgraderingen ska företaget inte öppnas med äldre Bokfri/Fribok.\n\n"
                + "Vill du uppgradera nu?";
        return JOptionPane.showConfirmDialog(null, message, "Företaget behöver uppgraderas",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /**
     * The main method of the program.
     *
     * @param args The arguments to the program.
     */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--version")) {
            LOG.info(Version.APP_TITLE + " " + Version.APP_VERSION);
            return;
        }

        try {
	    String os = System.getProperty("os.name");
	    FontSet fontSet = null;
	    if (os.startsWith("Windows")) {
		fontSet = FontSets.createDefaultFontSet(new Font(
				   "arial unicode MS", Font.PLAIN, 13));
	    } else {
		fontSet = FontSets.createDefaultFontSet(new Font(
				   "arial unicode", Font.PLAIN, 13));
	    }
	    FontPolicy fixedPolicy = FontPolicies.createFixedPolicy(fontSet);
	    Plastic3DLookAndFeel.setFontPolicy(fixedPolicy);
	    //Plastic3DLookAndFeel.setHighContrastFocusColorsEnabled(true);
	    String lnfClassName = Plastic3DLookAndFeel.class.getName();  
	    if (os.startsWith("Mac OS") || os.startsWith("Windows")) {
		lnfClassName = UIManager.getSystemLookAndFeelClassName();
	    } else {
		String xdgCurrentDesktop = System.getenv("XDG_CURRENT_DESKTOP");
		if ("Unity".equalsIgnoreCase(xdgCurrentDesktop)
				|| "XFCE".equalsIgnoreCase(xdgCurrentDesktop)
				|| "GNOME".equalsIgnoreCase(xdgCurrentDesktop)
				|| "X-Cinnamon".equalsIgnoreCase(xdgCurrentDesktop)
				|| "LXDE".equalsIgnoreCase(xdgCurrentDesktop)
				) {
			//lnfClassName = UIManager.getSystemLookAndFeelClassName();
			//lnfClassName = PlasticLookAndFeel.class.getName();
		} else {
			lnfClassName = Plastic3DLookAndFeel.class.getName();
		}
	    }

            UIManager.setLookAndFeel(lnfClassName);
        } catch (UnsupportedLookAndFeelException | ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            LOG.error("Unexpected error", e);
        }
        UIManager.put("OptionPane.yesButtonText", "Ja");
        UIManager.put("OptionPane.noButtonText", "Nej");
        UIManager.put("OptionPane.cancelButtonText", "Avbryt");
        UIManager.put("OptionPane.okButtonText", "OK");
        iRunning = true;

        // Print information to ease debugging
        LOG.info("Starting up...");
        LOG.info("Title : " + Version.APP_TITLE);
        LOG.info("Version : " + Version.APP_VERSION);
        LOG.info("Build : " + Version.APP_BUILD);
        LOG.info("Directory : " + Path.get(Path.APP_BASE));
        LOG.info("");
        LOG.info("Operating system: " + System.getProperty("os.name"));
        LOG.info("Architecture : " + System.getProperty("os.arch"));
        LOG.info("Java version : " + System.getProperty("java.version"));
        LOG.info("");
        LOG.info("Paths:");
        for (Path name : Path.values()) {
            LOG.info(String.format("   %-12s = %s", name, Path.get(name)));
        }

        String warning = null;

        // Create paths as needed, warning the user on failure
        for (Path name : Path.values()) {
            File dir = Path.get(name);

            if (!dir.exists()) {
                try {
                    if (dir.mkdirs()) {
                        LOG.info("Created " + dir);
                    } else {
                        warning = "unable to create";
                    }
                } catch (SecurityException e) {
                    LOG.error("Unexpected error", e);
                }
            } else if (!dir.isDirectory()) {
                warning = "exists but is not a directory";
            }
            if (warning != null) {
                LOG.info(" !! WARNING: " + dir + ' ' + warning);
                warning = null;
            }
        }

        // Create and display the main iMainFrame.
        SSMainFrame iMainFrame = SSMainFrame.getInstance();

        UIManager.put("InternalFrame.icon", SSIcon.getIcon("ICON_FRAME"));
        UIManager.put("InternalFrame.inactiveIcon", SSIcon.getIcon("ICON_FRAME"));

        // Display the main frame before database startup so the user gets
        // immediate feedback after launching the application.
        iMainFrame.setVisible(true);
        iMainFrame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        boolean databaseStarted = startupDatabase();
        iMainFrame.setCursor(Cursor.getDefaultCursor());
        if (!databaseStarted) {
            iMainFrame.dispose();
            iRunning = false;
            return;
        }

        // Only display the company iMainFrame if there are no companies defined.
        // I would prefer to only open the select company iMainFrame if there are no companies.
        // But Fredrik and Joakim wants it to displayed every time.
        if ((Boolean) SSConfig.getInstance().get("companyframe.showatstart", true)) {
            iMainFrame.showCompanyFrame();
        }

        SSDB.getInstance().init(true);

        // Perhaps add some type of shut down hook.
        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        () -> {

                                SSFrameManager.getInstance().storeAllFrames();

                                iRunning = false;
                                SSDB.getInstance().shutdown();

                            }));
    }

}
