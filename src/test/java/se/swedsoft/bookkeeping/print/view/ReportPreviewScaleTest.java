package se.swedsoft.bookkeeping.print.view;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests report preview scale calculations independently of a display device.
 */
class ReportPreviewScaleTest {

    @Test
    void keepsLogicalPageSizeIndependentOfDeviceScale() {
        Dimension pageSize = ReportPreviewScale.logicalPageSize(595, 842, 125);

        assertThat(pageSize).isEqualTo(new Dimension(744, 1053));
        assertThat(ReportPreviewScale.renderScale(125, 1.0)).isEqualTo(1.25f);
        assertThat(ReportPreviewScale.renderScale(125, 1.25)).isEqualTo(1.5625f);
        assertThat(ReportPreviewScale.renderScale(125, 2.0)).isEqualTo(2.5f);
    }

    @Test
    void defaultsToOneForComponentsWithoutGraphicsConfiguration() {
        assertThat(ReportPreviewScale.deviceScale(null)).isEqualTo(1.0);
    }

    @Test
    void rejectsInvalidScales() {
        assertThatIllegalArgumentException().isThrownBy(() -> ReportPreviewScale.logicalScale(0));
        assertThatIllegalArgumentException().isThrownBy(() -> ReportPreviewScale.renderScale(100, 0));
        assertThatIllegalArgumentException().isThrownBy(
                () -> ReportPreviewScale.renderScale(100, Double.NaN));
    }
}
