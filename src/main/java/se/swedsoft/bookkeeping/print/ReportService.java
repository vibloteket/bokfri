package se.swedsoft.bookkeeping.print;

import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.print.report.SSBalancePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSInvoicePrinter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * UI-independent entry point for rendering and exporting reports.
 *
 * <p>Legacy {@link SSPrinter} implementations are adapted here so callers do not need to know
 * how JasperReports is invoked. Additional reports can move behind this boundary incrementally.</p>
 */
public final class ReportService {

    /**
     * Renders one invoice using the same core used by GUI preview and headless export.
     *
     * @param invoice invoice to render
     * @param locale report locale
     * @return rendered invoice
     */
    public RenderedReport renderInvoice(SSInvoice invoice, Locale locale) {
        Objects.requireNonNull(invoice, "invoice");
        Objects.requireNonNull(locale, "locale");
        return render(() -> new SSInvoicePrinter(invoice, locale));
    }

    /**
     * Renders invoices as one multi-page report.
     *
     * @param invoices invoices to render
     * @param locale report locale
     * @return rendered invoices
     */
    public RenderedReport renderInvoices(List<SSInvoice> invoices, Locale locale) {
        Objects.requireNonNull(invoices, "invoices");
        Objects.requireNonNull(locale, "locale");
        SSMultiPrinter printer = new SSMultiPrinter();
        for (SSInvoice invoice : invoices) {
            printer.addReport(new SSInvoicePrinter(Objects.requireNonNull(invoice, "invoice"), locale));
        }
        return render(printer);
    }

    /**
     * Renders a balance report without consulting the global database.
     *
     * @param year accounting year
     * @param from first report date
     * @param to last report date
     * @return rendered balance report
     */
    public RenderedReport renderBalance(SSNewAccountingYear year, LocalDate from, LocalDate to) {
        Objects.requireNonNull(year, "year");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return render(() -> new SSBalancePrinter(year, from, to));
    }

    /**
     * Adapts a legacy printer to the UI-independent rendered-report contract.
     *
     * @param printerFactory factory for a fresh printer
     * @return rendered report
     */
    public RenderedReport render(Supplier<? extends SSPrinter> printerFactory) {
        Objects.requireNonNull(printerFactory, "printerFactory");
        return render(Objects.requireNonNull(printerFactory.get(), "printer"));
    }

    private RenderedReport render(SSPrinter printer) {
        printer.generateReport();
        return new RenderedReport(printer.getPrinter(), printer.getTitle());
    }

    /**
     * Exports an already rendered report to PDF.
     *
     * @param report rendered report
     * @param output destination file
     * @param overwrite whether an existing destination may be replaced
     * @return normalized absolute destination
     * @throws IOException if the destination cannot be prepared
     * @throws ReportExportException if PDF encoding fails
     */
    public Path exportPdf(RenderedReport report, Path output, boolean overwrite)
            throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(output, "output");
        try {
            return PdfReportExporter.export(report.print(), output, overwrite);
        } catch (net.sf.jasperreports.engine.JRException exception) {
            throw new ReportExportException("Could not export PDF to "
                    + output.toAbsolutePath().normalize(), exception);
        }
    }
}
