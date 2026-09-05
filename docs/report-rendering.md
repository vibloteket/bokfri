# Report rendering and export

Bokfri separates three report responsibilities:

1. Domain and application services select and validate report input.
2. `ReportService` renders that input and exports `RenderedReport` values.
3. Swing adapters own dialogs, preview windows, printing, and user notifications.

`ReportService` is the UI-independent boundary. Callers should prefer named methods such as
`renderInvoice` and `renderBalance`; `render(Supplier<? extends SSPrinter>)` is a compatibility
adapter for reports that have not yet received a named request method. `RenderedReport` exposes
stable metadata while JasperReports access is reserved for adapters in the print package.

PDF path normalization, parent-directory creation, overwrite behavior, and Jasper export error
translation are centralized in `ReportService.exportPdf`. CLI and domain services must not invoke
`JasperExportManager` directly.

## Migrating another report

1. Make the printer accept its data explicitly instead of looking it up from Swing or `SSDB`.
2. Add a named `ReportService.render...` method whose arguments describe the complete request.
3. Make CLI export call `ReportService`; do not expose `JasperPrint` outside preview/print adapters.
4. Make GUI preview render through the same named method, leaving dialogs and window lifecycle in
   Swing code.
5. Add a headless render/export test and retain the existing visual PDF regression coverage.

Do not migrate all legacy printers at once. The compatibility adapter keeps untouched reports
working while each vertical slice replaces hidden dependencies with explicit input.
