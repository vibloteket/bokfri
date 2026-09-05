# JasperReports 7 migration

Bokfri targets JasperReports **7.0.8**. The runtime uses only these Jasper modules:

- `jasperreports` — rendering core and Jackson JRXML loader;
- `jasperreports-jdt` — ECJ expression compilation at runtime;
- `jasperreports-pdf` — PDF export;
- `jasperreports-excel-poi` — the existing multi-sheet XLS save option in Swing preview.

The canonical 116 JRXML reports and shared JRTX style were converted with Jaspersoft Studio
Community 7.0.6. Conversion instructions and the deterministic conversion wrapper are under
`tools/jasperreports7-converter/`.

No legacy JRXML loader is packaged with Bokfri. No generated `.jasper` or `.jasperreport` files are
stored in source or user data. Runtime loading and dynamic report assembly use JasperReports 7 APIs.

## Compatibility verification

The migration is intentionally layout-neutral. The complete deterministic PDF gallery compares at
zero changed pixels against the JasperReports 6.21.5 baselines. Font integration tests verify
embedded DejaVu Sans regular/bold/italic/bold-italic and OCR-B without fallback fonts.

Baseline and migrated fat JAR sizes:

| Build | Bytes | Change |
|---|---:|---:|
| JasperReports 6.21.5 | 30,300,311 | — |
| JasperReports 7.0.8, required modules | 49,978,972 | +19,678,661 (+64.9%) |

Most of the increase is Apache POI and its transitive dependencies, now isolated in the optional
JasperReports Excel module that preserves Bokfri's existing XLS preview export. Removing that UI
feature would reduce the measured fat JAR to 32,176,173 bytes, but doing so is outside this
migration's no-behavior-change scope.

The platform package jobs are the authoritative measurement and verification for the minimal
runtime, AppImage, MSI, and DMG artifacts.
