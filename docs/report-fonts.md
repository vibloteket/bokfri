# Report fonts

Bokfri bundles every font used by JasperReports so previews, printing, and PDF export do not depend on host fonts.

## Families

### Bokfri Sans

`Bokfri Sans` maps to DejaVu Sans 2.37:

- `DejaVuSans.ttf`
- `DejaVuSans-Bold.ttf`
- `DejaVuSans-Oblique.ttf`
- `DejaVuSans-BoldOblique.ttf`

Source: https://github.com/dejavu-fonts/dejavu-fonts/releases/tag/version_2_37

Copyright and license: Bitstream Vera license with DejaVu modifications in the public domain. The complete notice is distributed as `META-INF/licenses/fonts/DejaVu-LICENSE.txt`.

### OCRB

The OCR line on Swedish OCR invoices uses the unmodified OCR-B font from upstream commit
`fedeba81519770109925b5bec70e940be5948d8f`.

Source: https://github.com/jaycee723/ocr-b/tree/fedeba81519770109925b5bec70e940be5948d8f

Copyright 2019 Raisty. Licensed under SIL Open Font License 1.1. The complete license is distributed as `META-INF/licenses/fonts/OCR-B-LICENSE.md`. `OCR-B` is a reserved font name; Bokfri distributes the unmodified font.

## JasperReports configuration

`org/fribok/fonts/fonts.xml` registers both families with Unicode `Identity-H` encoding and PDF embedding. `SSReportFonts` installs this font registry before any report is compiled and selects `Bokfri Sans` as the default report and PDF family.

The generated PDFs use font subsetting: only glyphs used by a document are embedded. OCR-B is embedded only when an OCR invoice uses it. Gallery generation rejects unembedded or unexpected fonts and PDFs larger than 250 KB.

## Updating fonts

1. Keep all font files under `src/main/resources/org/fribok/fonts/`.
2. Update this file with source, version, copyright, and license.
3. Keep the complete license text under `src/main/resources/META-INF/licenses/fonts/`.
4. Run `mvn -Pcoverage clean install` and the PDF gallery.
5. Verify PDF font embedding and review all changed gallery baselines before approval.
