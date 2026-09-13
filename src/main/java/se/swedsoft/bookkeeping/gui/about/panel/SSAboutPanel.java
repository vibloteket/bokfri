package se.swedsoft.bookkeeping.gui.about.panel;


import org.fribok.bookkeeping.app.LogFile;
import org.fribok.bookkeeping.app.Version;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.util.BrowserLaunch;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-mar-14
 * Time: 15:15:51
 */
public class SSAboutPanel {    private static final Logger LOG = LoggerFactory.getLogger(SSAboutPanel.class);


    private JPanel iPanel;

    private JButton iCloseButton;

    private JEditorPane iEditorPane;

    private static final Dimension ABOUT_TEXT_SIZE = new Dimension(560, 340);

    /**
     *
     */
    public SSAboutPanel() {
        String iText = SSBundle.getBundle().getString("aboutframe.abouttext");

        iEditorPane.setBackground(iPanel.getBackground());
        iEditorPane.setPreferredSize(ABOUT_TEXT_SIZE);
        iEditorPane.setMinimumSize(ABOUT_TEXT_SIZE);

        iText = iText.replace("{TITLE}", Version.APP_TITLE);
        iText = iText.replace("{VERSION}", Version.APP_VERSION);
        iText = iText.replace("{BUILD}", Version.APP_BUILD);
        String logPath = html(LogFile.file().getAbsolutePath());
        iText = iText.replace("</center></html>",
                "<br><br><b>Loggfil</b><br>" + logPath
                        + "<br><a href=\"bokfri:open-log\">Öppna loggmapp</a> · "
                        + "<a href=\"bokfri:copy-log-path\">Kopiera sökväg</a>"
                        + "</center></html>");

        iEditorPane.setText(iText);

        iEditorPane.addHyperlinkListener(
                new HyperlinkListener() {
            public void hyperlinkUpdate(HyperlinkEvent e) {
                String iEventName = e.getEventType() == null
                        ? ""
                        : e.getEventType().toString();

                if (iEventName.equals("ACTIVATED")) {
                    if ("bokfri:open-log".equals(e.getDescription())) {
                        openLogDirectory();
                    } else if ("bokfri:copy-log-path".equals(e.getDescription())) {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                                new StringSelection(LogFile.file().getAbsolutePath()), null);
                    } else if (e.getURL() != null) {
                        BrowserLaunch.openURL(e.getURL());
                    }
                }
                if (iEventName.equals("ENTERED")) {
                    iEditorPane.setCursor(new Cursor(Cursor.HAND_CURSOR));
                }
                if (iEventName.equals("EXITED")) {
                    iEditorPane.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }

            }
        });
    }

    private void openLogDirectory() {
        try {
            LogFile.openDirectory();
        } catch (Exception exception) {
            LOG.error("Could not open log directory", exception);
            JOptionPane.showMessageDialog(iPanel, exception.getMessage(),
                    "Kunde inte öppna loggmappen", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     *
     * @return
     */
    public JPanel getPanel() {
        return iPanel;
    }

    /**
     *
     * @param iListener
     */
    public void addCloseButtonListener(ActionListener iListener) {
        iCloseButton.addActionListener(iListener);
    }

    /*

     // Used to identify the windows platform.
     private static final String WIN_ID = "Windows";
     // The default system browser under windows.
     private static final String WIN_PATH = "rundll32";
     // The flag to display a url.
     private static final String WIN_FLAG = "url.dll,FileProtocolHandler";
     // The default browser under unix.
     private static final String UNIX_PATH = "netscape";
     // The flag to display a url.
     private static final String UNIX_FLAG = "-remote openURL";

     * Display a file in the sy
     * stem browser.  If you want to display a
     * file, you must include the absolute path name.
     *
     * @param url the file's url (the url must start with either "http://" or "file://").

     public static void displayURL(URL url){

     boolean windows = isWindowsPlatform();
     String cmd = null;
     try{
     if (windows){
     // cmd = 'rundll32 url.dll,FileProtocolHandler http://...'
     cmd = WIN_PATH + " " + WIN_FLAG + " " + url;
     Process p = Runtime.getRuntime().exec(cmd);
     }  else  {
     // Under Unix, Netscape has to be running for the "-remote"
     // command to work.  So, we try sending the command and
     // check for an exit value.  If the exit command is 0,
     // it worked, otherwise we need to start the browser.
     // cmd = 'netscape -remote openURL(http://www.javaworld.com)'
     cmd = UNIX_PATH + " " + UNIX_FLAG + "(" + url + ")";
     Process p = Runtime.getRuntime().exec(cmd);
     try
     {
     // wait for exit code -- if it's 0, command worked,
     // otherwise we need to start the browser up.
     int exitCode = p.waitFor();
     if (exitCode != 0) {
     // Command failed, start up the browser
     // cmd = 'netscape http://www.javaworld.com'
     cmd = UNIX_PATH + " "  + url;

     Runtime.getRuntime().exec(cmd);
     }
     }
     catch(InterruptedException x)  {
     LOG.error("Error bringing up browser, cmd='" + cmd + "'");
     LOG.error("Caught: " + x);
     }
     }
     }
     catch(IOException x) {
     // couldn't exec browser
     LOG.error("Could not invoke browser, command=" + cmd);
     LOG.error("Caught: " + x);
     }
     }

     * Try to determine whether this application is running under Windows
     * or some other platform by examing the "os.name" property.
     *
     * @return true if this application is running under a Windows OS

     public static boolean isWindowsPlatform()
     {
     String os = System.getProperty("os.name");
     return os != null && os.startsWith(WIN_ID);

     }    */

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.about.panel.SSAboutPanel");
        sb.append("{iCloseButton=").append(iCloseButton);
        sb.append(", iEditorPane=").append(iEditorPane);
        sb.append(", iPanel=").append(iPanel);
        sb.append('}');
        return sb.toString();
    }
}
