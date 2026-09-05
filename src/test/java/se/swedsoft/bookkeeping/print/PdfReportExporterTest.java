package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JasperPrint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the shared PDF file policy. */
class PdfReportExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsParentDirectoriesAndNormalizesDestination() throws Exception {
        Path output = temporaryDirectory.resolve("nested/../nested/report.pdf");

        Path exported = PdfReportExporter.export(new JasperPrint(), output, false);

        assertThat(exported).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(exported)).startsWith(
                (byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
    }

    @Test
    void refusesExistingDestinationUnlessOverwriteIsEnabled() throws Exception {
        Path output = Files.writeString(temporaryDirectory.resolve("report.pdf"), "existing");

        assertThatThrownBy(() -> PdfReportExporter.export(new JasperPrint(), output, false))
                .isInstanceOf(FileAlreadyExistsException.class);

        PdfReportExporter.export(new JasperPrint(), output, true);
        assertThat(Files.readAllBytes(output)).startsWith(
                (byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
    }
}
