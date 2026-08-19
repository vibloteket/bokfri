package se.swedsoft.bookkeeping.gui.util.dialogs;

import org.fribok.bookkeeping.app.LogFile;
import org.fribok.bookkeeping.app.Version;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;

/** A copyable diagnostic dialog for unexpected technical failures. */
public final class SSUnexpectedErrorDialog {
    private SSUnexpectedErrorDialog() {}

    public static void showDialog(JFrame parent, String title, String summary, Throwable error) {
        String diagnostic = diagnosticText(summary, error);
        JTextArea details = new JTextArea(diagnostic, 18, 90);
        details.setEditable(false);
        details.setCaretPosition(0);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, details.getFont().getSize()));

        JLabel message = new JLabel("<html><b>Ett oväntat fel inträffade.</b><br>"
                + html(summary) + "<br><br>Loggfil: " + html(LogFile.file().getAbsolutePath()) + "</html>");
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(message, BorderLayout.NORTH);
        JScrollPane detailsScrollPane = new JScrollPane(details);
        detailsScrollPane.setPreferredSize(new Dimension(760, 330));

        JButton toggleDetails = new JButton("Visa detaljer");
        JButton copy = new JButton("Kopiera felinformation");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(diagnostic), null));
        JButton open = new JButton("Öppna loggmapp");
        JButton close = new JButton("Stäng");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(toggleDetails);
        buttons.add(copy);
        buttons.add(open);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(parent, title, true);
        toggleDetails.addActionListener(event -> {
            boolean showing = detailsScrollPane.getParent() == content;
            if (showing) {
                content.remove(detailsScrollPane);
                toggleDetails.setText("Visa detaljer");
            } else {
                content.add(detailsScrollPane, BorderLayout.CENTER);
                toggleDetails.setText("Dölj detaljer");
            }
            content.revalidate();
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
        });
        open.addActionListener(event -> {
            try {
                LogFile.openDirectory();
            } catch (Exception exception) {
                JOptionPane.showMessageDialog(dialog, exception.getMessage(),
                        "Kunde inte öppna loggmappen", JOptionPane.ERROR_MESSAGE);
            }
        });
        close.addActionListener(event -> dialog.dispose());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(650, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    static String diagnosticText(String summary, Throwable error) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        return "Bokfri: " + Version.APP_VERSION + "\n"
                + "Build: " + Version.APP_BUILD + "\n"
                + "Time: " + OffsetDateTime.now() + "\n"
                + "Operating system: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n"
                + "Java: " + System.getProperty("java.version") + "\n"
                + "Log file: " + LogFile.file().getAbsolutePath() + "\n\n"
                + "Message: " + summary + "\n\n" + stackTrace;
    }

    private static String html(String value) {
        if (value == null || value.isBlank()) {
            return "Ingen ytterligare felbeskrivning finns.";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
