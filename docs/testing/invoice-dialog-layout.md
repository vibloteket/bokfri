# Customer-invoice dialog sizing (#152)

All four entry points (new, edit, copy, invoice from orders) use the same layout-aware sizing helper after the panel has been populated. The form is packed, retains an 800×600 baseline where space permits, and is limited to the owning monitor's work area, excluding taskbars/docks. Bounds are clamped after positioning; negative monitor origins are supported. No saved-geometry state is introduced.

The existing generated form is preserved. Its action row is moved into a persistent footer, outside a scrollable body. The body follows the viewport while it can fit above its computed minimum; otherwise it scrolls instead of squeezing the grid below that minimum. The minimum window size comes from the footer and a small usable viewport, capped by the available work area.

## Automated verification

Headless geometry tests:

```sh
mvn test -Dtest=SSInvoiceDialogLayoutTest
```

Real GUI tests (Linux without a desktop uses `xvfb-run -a`):

```sh
xvfb-run -a mvn test -Dtest=SSInvoiceDialogGuiTest,SSInvoiceDialogLayoutTest \
  -Dawt.headless=false -Dbokfri.invoiceTest.fontScale=2
```

GUI tests use a temporary in-memory SSDB fixture and synthetic invoice data. They check:

- every tab's action-button containment at normal, small and minimum window sizes;
- scroll fallback without squeezing the form below its layout minimum;
- mouse-clickable actions after horizontal/vertical scrolling;
- all four public entry points, including the invoice-type choice, and Cancel without adding invoices;
- an actual edit dialog: resize, scroll, change a field, click OK, then read the changed value back from the database.

Workflow **Invoice dialog GUI regression** runs separate JVMs at 1×, 1.25×, 1.5× and 2× UI fonts on Linux/Windows/macOS with the application's platform Look & Feel. Linux additionally tests Metal and Nimbus at 1×/2×. It uploads screenshots and reports. These are font/layout tests, not emulation of all native DPI settings or physical monitor configurations. Ordinary headless CI skips the GUI tests.

The original investigation reproduced clipping in 9/12 Linux combinations at fixed 800×600: https://github.com/vibloteket/bokfri/issues/152#issuecomment-6352392719

## Manual native-platform checks

Use test data/a backup, and do not run two instances against the same bookkeeping directory.

1. Open a new customer invoice without manually resizing; confirm both dialog buttons are visible.
2. Repeat for edit, copy and invoice from orders. Check all tabs.
3. On a small display or with larger UI fonts, confirm the form scrolls while buttons remain reachable. Resize smaller/larger and test fields/table interaction.
4. Save a valid test invoice and cancel another; confirm the intended operation occurs.
5. Open from a main window on another monitor, including a monitor to the left of the primary display. Check initial placement and reserved taskbar/dock areas.

The original reporter's exact display/font configuration and manual native Mac retest remain separate from CI coverage. No invoice posting/payment logic or popup-focus behavior is changed by this fix.
