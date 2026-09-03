package se.swedsoft.bookkeeping.print.util;

import net.sf.jasperreports.engine.JasperReport;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.util.SSException;

import java.io.FileNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SSReportCacheTest {

    @Test
    void compiledReportsAreReusedInMemory() {
        SSReportCache cache = SSReportCache.getInstance();

        JasperReport first = cache.getReport("header.jrxml");
        JasperReport second = cache.getReport("header.jrxml");

        assertThat(second).isSameAs(first);
    }

    @Test
    void missingTemplateErrorNamesTemplateAndPreservesCause() {
        assertThatThrownBy(() -> SSReportCache.getInstance().getReport("missing.jrxml"))
                .isInstanceOf(SSException.class)
                .hasMessage("Report template not found: missing.jrxml")
                .hasCauseInstanceOf(FileNotFoundException.class);
    }
}
