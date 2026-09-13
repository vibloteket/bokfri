package org.fribok.bookkeeping;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged-launcher smoke test that initializes and paints representative Swing controls. */
public final class GuiSmokeTest {

    private GuiSmokeTest() {}

    /**
     * Runs the UI smoke test and exits normally when Swing initialized successfully.
     *
     * @param arguments ignored
     * @throws Exception when Look & Feel or component initialization fails
     */
    public static void main(String[] arguments) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("GUI smoke test requires a display");
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = null;
            try {
                Bookkeeping.configureLookAndFeel(System.getProperty("os.name"),
                        System.getenv("XDG_CURRENT_DESKTOP"));
                requireFont("Button.font");
                requireFont("Label.font");
                requireFont("Table.font");
                requireFont("TableHeader.font");

                JTable table = new JTable(
                        new Object[][] {{Boolean.TRUE, "Bokfri GUI smoke test"}},
                        new Object[] {"Vald", "Företag"});
                JPanel content = new JPanel(new BorderLayout());
                content.add(new JLabel("Bokfri"), BorderLayout.NORTH);
                content.add(new JScrollPane(table), BorderLayout.CENTER);

                JPanel controls = new JPanel();
                controls.add(new JCheckBox("Visa vid start", true));
                controls.add(new JButton("Stäng"));
                JEditorPane html = new JEditorPane("text/html", "<html><b>Bokfri</b></html>");
                html.setEditable(false);
                controls.add(html);
                content.add(controls, BorderLayout.SOUTH);

                frame = new JFrame("Bokfri GUI smoke test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setContentPane(content);
                frame.pack();
                frame.setVisible(true);
                BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    frame.paint(graphics);
                } finally {
                    graphics.dispose();
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                if (frame != null) {
                    frame.dispose();
                }
            }
        });

        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(failure.get());
        }
        System.out.println("Bokfri packaged GUI smoke test passed");
    }

    private static void requireFont(String key) {
        Font font = UIManager.getFont(key);
        if (font == null || font.getSize2D() <= 0) {
            throw new IllegalStateException("Invalid Swing font default " + key + ": " + font);
        }
    }
}
