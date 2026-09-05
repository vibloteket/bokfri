# JasperReports 7 migration

Bokfri targets JasperReports **7.0.8**. The runtime uses only these Jasper modules:

- `jasperreports` — rendering core and Jackson JRXML loader;
- `jasperreports-jdt` — ECJ expression compilation at runtime;
- `jasperreports-pdf` — PDF export.

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

The legacy HTML, RTF, and layout-oriented XLS exports have been removed from the Swing report
preview. PDF is the supported presentation and archive format; future structured data exports
should use CSV or another data-oriented format. Removing the JasperReports Excel module also avoids
shipping Apache POI solely for the obsolete preview export.

The platform package jobs are the authoritative measurement and verification for the minimal
runtime, AppImage, MSI, and DMG artifacts.
