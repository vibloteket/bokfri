package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JasperPrint;

import java.util.Objects;

/**
 * A rendered report together with stable metadata needed by callers.
 * JasperReports remains an implementation detail of the print package.
 */
public final class RenderedReport {
    private final JasperPrint print;
    private final String title;

    RenderedReport(JasperPrint print, String title) {
        this.print = Objects.requireNonNull(print, "print");
        this.title = Objects.requireNonNullElse(title, "");
    }

    JasperPrint print() {
        return print;
    }

    /**
     * Returns the display title of the report.
     *
     * @return report title, or an empty string
     */
    public String title() {
        return title;
    }

    /**
     * Returns the number of rendered pages.
     *
     * @return page count
     */
    public int pageCount() {
        return print.getPages().size();
    }
}
