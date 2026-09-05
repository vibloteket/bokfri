# JasperReports 7 source conversion

The report sources were converted from the JasperReports 6 XML format to the JasperReports 7
Jackson XML format with **Jaspersoft Studio Community 7.0.6**, then compiled and run with
JasperReports **7.0.8**.

The source archive used for the conversion had SHA-256:

```
2a70b496748f398fd81f97566c2e6039070bf78f4dc1a72ac9bfce32d3c76a00  js-studiocomm_7.0.6_windows_x86_64.zip
```

Jaspersoft Studio contains the proprietary legacy JRXML loader used for this one-time format
migration. The repository does not distribute that loader. `convert.sh` accepts an extracted
Studio installation and runs the checked-in converter source against every canonical JRXML and
JRTX file. It writes to a temporary directory first and only replaces all sources after every
conversion succeeds.

```sh
./tools/jasperreports7-converter/convert.sh /path/to/jaspersoftstudio
mvn -Dtest=ReportTemplateInventoryTest test
./scripts/pdf-gallery generate
./scripts/pdf-gallery compare
```

The script currently expects Studio 7.0.6's plugin layout. Studio's legacy writer otherwise emits
random UUIDs, so the converter replaces them with name-based UUIDs derived from the relative source
path and element order. Rerunning it on the pre-migration sources therefore produces the checked-in
files byte for byte. The converter is not part of Bokfri's application or runtime dependencies.
