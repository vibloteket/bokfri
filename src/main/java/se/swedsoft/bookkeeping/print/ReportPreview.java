package se.swedsoft.bookkeeping.print;

import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.print.view.SSJasperPreviewFrame;

import java.util.Objects;

/** Swing adapter for displaying an already rendered report. */
public final class ReportPreview {
    private ReportPreview() {}

    /**
     * Displays a rendered report in the standard preview window.
     *
     * @param parent parent application window
     * @param report report to display
     */
    public static void show(SSMainFrame parent, RenderedReport report) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(report, "report");
        SSJasperPreviewFrame preview = new SSJasperPreviewFrame(parent, 800, 600);
        preview.setInCenter(parent);
        preview.setPrinter(report.print());
        preview.setVisible(true);
    }
}
