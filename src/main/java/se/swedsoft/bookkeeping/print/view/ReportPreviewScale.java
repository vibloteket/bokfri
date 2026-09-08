package se.swedsoft.bookkeeping.print.view;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;

/**
 * Separates the report preview's logical zoom from its HiDPI raster resolution.
 */
final class ReportPreviewScale {

    private ReportPreviewScale() {}

    static float logicalScale(int zoomPercent) {
        if (zoomPercent <= 0) {
            throw new IllegalArgumentException("Zoom must be greater than zero");
        }
        return zoomPercent / 100.0f;
    }

    static float renderScale(int zoomPercent, double deviceScale) {
        if (!Double.isFinite(deviceScale) || deviceScale <= 0) {
            throw new IllegalArgumentException("Device scale must be greater than zero");
        }
        return (float) (logicalScale(zoomPercent) * deviceScale);
    }

    static double deviceScale(GraphicsConfiguration configuration) {
        if (configuration == null) {
            return 1.0;
        }

        AffineTransform transform = configuration.getDefaultTransform();
        return Math.max(1.0, Math.max(Math.abs(transform.getScaleX()), Math.abs(transform.getScaleY())));
    }

    static Dimension logicalPageSize(int pageWidth, int pageHeight, int zoomPercent) {
        float scale = logicalScale(zoomPercent);
        return new Dimension(Math.round(pageWidth * scale), Math.round(pageHeight * scale));
    }
}
