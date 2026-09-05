package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JROrigin;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.base.JRBasePrintPage;
import net.sf.jasperreports.engine.base.JRBasePrintText;
import net.sf.jasperreports.engine.base.JRBaseStyle;
import net.sf.jasperreports.engine.type.BandTypeEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for combined Jasper reports.
 */
@Tag("integration")
class SSMultiPrinterTest {

    @BeforeAll
    static void openDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @Test
    void combinedReportKeepsPrintMetadataRequiredForRenderingPages() throws Exception {
        SSMultiPrinter multiPrinter = new SSMultiPrinter();

        multiPrinter.addReport(new FixedPrintPrinter("first", "firstStyle"));
        multiPrinter.addReport(new FixedPrintPrinter("second", "secondStyle"));
        multiPrinter.generateReport();

        JasperPrint combinedPrint = multiPrinter.getPrinter();

        assertThat(combinedPrint.getPages()).hasSize(2);
        assertThat(combinedPrint.getStylesMap()).containsKeys("firstStyle", "secondStyle");
        assertThat(combinedPrint.getOriginsList())
                .extracting(origin -> origin.getReportName() + ":" + origin.getBandType())
                .contains("first:DETAIL", "second:DETAIL");
        assertThat(combinedPrint.getProperty("bokfri.test.firstStyle")).isEqualTo("present");
        assertThat(combinedPrint.getProperty("bokfri.test.secondStyle")).isEqualTo("present");

        JRPrintElement firstElement = combinedPrint.getPages().get(0).getElements().get(0);
        JRPrintElement secondElement = combinedPrint.getPages().get(1).getElements().get(0);

        assertThat(firstElement.getStyle().getName()).isEqualTo("firstStyle");
        assertThat(secondElement.getStyle().getName()).isEqualTo("secondStyle");
    }

    private static final class FixedPrintPrinter extends SSPrinter {
        private final JasperPrint print;
        private final String title;

        private FixedPrintPrinter(String title, String styleName) throws Exception {
            this.title = title;
            this.print = createPrint(title, styleName);
        }

        @Override
        public void generateReport() {}

        @Override
        public JasperPrint getPrinter() {
            return print;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        protected SSDefaultTableModel getModel() {
            return new SSDefaultTableModel() {
                @Override
                public Class<?> getType() {
                    return String.class;
                }

                @Override
                public Object getValueAt(int rowIndex, int columnIndex) {
                    return null;
                }
            };
        }

        private static JasperPrint createPrint(String title, String styleName) throws Exception {
            JasperPrint print = new JasperPrint();

            print.setName(title);
            print.setPageWidth(595);
            print.setPageHeight(842);
            print.setProperty("bokfri.test." + styleName, "present");

            JRBaseStyle style = new JRBaseStyle(styleName);
            print.addStyle(style);
            print.setDefaultStyle(style);

            JROrigin origin = new JROrigin(title, BandTypeEnum.DETAIL);
            print.addOrigin(origin);

            JRBasePrintText text = new JRBasePrintText(print.getDefaultStyleProvider());

            text.setX(10);
            text.setY(10);
            text.setWidth(200);
            text.setHeight(20);
            text.setText(title);
            text.setStyle(style);
            text.setOrigin(origin);

            JRBasePrintPage page = new JRBasePrintPage();

            page.addElement(text);
            print.addPage(page);
            return print;
        }
    }
}
