package se.swedsoft.bookkeeping.gui.util;

import org.fribok.bookkeeping.service.spreadsheet.AccountPlanSpreadsheetService;
import org.fribok.bookkeeping.service.spreadsheet.CustomerSpreadsheetService;
import org.fribok.bookkeeping.service.spreadsheet.ProductSpreadsheetService;
import org.fribok.bookkeeping.service.spreadsheet.SupplierSpreadsheetService;
import org.fribok.bookkeeping.service.spreadsheet.VoucherSpreadsheetService;
import org.fribok.bookkeeping.service.spreadsheet.VoucherTemplateSpreadsheetService;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.data.system.SSDB;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Swing adapter for the UI-independent spreadsheet services. */
public final class SSSpreadsheetExchange {
    private SSSpreadsheetExchange() {}

    public static void importAccountPlan(JFrame parent, Path file) throws IOException {
        SSAccountPlan plan = new AccountPlanSpreadsheetService().read(file);
        if (SSDB.getInstance().getAccountPlans().stream()
                .anyMatch(existing -> existing.getName().equals(plan.getName()))) {
            throw new IllegalArgumentException("Kontoplanen " + plan.getName() + " finns redan.");
        }
        if (confirm(parent, "Importera kontoplan", plan.getName() + " ("
                + plan.getAccounts().size() + " konton)")) {
            SSDB.getInstance().addAccountPlan(plan);
        }
    }

    public static void importCustomers(JFrame parent, Path file) throws IOException {
        List<SSCustomer> imported = new CustomerSpreadsheetService().read(file);
        List<SSCustomer> additions = newItems(imported, SSDB.getInstance().getCustomers(),
                SSCustomer::getNumber);
        if (confirm(parent, "Importera kunder", summary(imported.size(), additions.size()))) {
            additions.forEach(SSDB.getInstance()::addCustomer);
        }
    }

    public static void importProducts(JFrame parent, Path file) throws IOException {
        List<SSProduct> imported = new ProductSpreadsheetService().read(file,
                SSDB.getInstance().getCurrentCompany(), SSDB.getInstance().getUnits());
        List<SSProduct> additions = newItems(imported, SSDB.getInstance().getProducts(),
                SSProduct::getNumber);
        if (confirm(parent, "Importera produkter", summary(imported.size(), additions.size()))) {
            additions.forEach(SSDB.getInstance()::addProduct);
        }
    }

    public static void importSuppliers(JFrame parent, Path file) throws IOException {
        List<SSSupplier> imported = new SupplierSpreadsheetService().read(file);
        List<SSSupplier> additions = newItems(imported, SSDB.getInstance().getSuppliers(),
                SSSupplier::getNumber);
        int outpaymentNumber = SSDB.getInstance().getSuppliers().stream()
                .map(SSSupplier::getOutpaymentNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        for (SSSupplier supplier : additions) {
            supplier.setOutpaymentNumber(outpaymentNumber++);
        }
        if (confirm(parent, "Importera leverantörer", summary(imported.size(), additions.size()))) {
            additions.forEach(SSDB.getInstance()::addSupplier);
        }
    }

    public static void importVouchers(JFrame parent, Path file) throws IOException {
        List<SSVoucher> imported = new VoucherSpreadsheetService().read(file);
        List<SSVoucher> additions = newItems(imported, SSDB.getInstance().getVouchers(),
                SSVoucher::getNumber);
        if (confirm(parent, "Importera verifikationer", summary(imported.size(), additions.size()))) {
            additions.forEach(voucher -> SSDB.getInstance().addVoucher(voucher, false));
        }
    }

    public static void importVoucherTemplates(JFrame parent, Path file) throws IOException {
        List<SSVoucherTemplate> imported = new VoucherTemplateSpreadsheetService().read(file);
        List<SSVoucherTemplate> additions = newItems(imported,
                SSDB.getInstance().getVoucherTemplates(), SSVoucherTemplate::getDescription);
        if (confirm(parent, "Importera konteringsmallar", summary(imported.size(), additions.size()))) {
            additions.forEach(SSDB.getInstance()::addVoucherTemplate);
        }
    }

    private static boolean confirm(JFrame parent, String title, String details) {
        return JOptionPane.showConfirmDialog(parent, details + "\n\nFortsätt med importen?",
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                == JOptionPane.OK_OPTION;
    }

    private static String summary(int total, int additions) {
        return total + " poster lästes. " + additions + " nya och "
                + (total - additions) + " dubbletter.";
    }

    private static <T, K> List<T> newItems(List<T> imported, List<T> existing,
                                           Function<T, K> key) {
        Set<K> existingKeys = existing.stream().map(key).collect(Collectors.toSet());
        return imported.stream().filter(item -> !existingKeys.contains(key.apply(item))).toList();
    }
}
