package se.swedsoft.bookkeeping.gui.util.filechooser;


import se.swedsoft.bookkeeping.gui.util.filechooser.util.SSFilterPDF;


/**
 * Date: 2006-feb-20
 * Time: 10:10:57
 */
public class SSJasperFileChooser extends SSFileChooser {

    private static SSJasperFileChooser cInstance;

    public static SSJasperFileChooser getInstance() {
        if (cInstance == null) {
            cInstance = new SSJasperFileChooser();
        }
        return cInstance;
    }

    /**
     *
     */
    private SSJasperFileChooser() {
        // Disable the default (Accept All) file filter.
        setAcceptAllFileFilterUsed(false);

        // Add a custom file filter
        SSFilterPDF iFilterPDF = new SSFilterPDF();

        addChoosableFileFilter(iFilterPDF);
        setFileFilter(iFilterPDF);

    }

}

