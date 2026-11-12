package org.fribok.bookkeeping.gui.creditinvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.swedsoft.bookkeeping.gui.creditinvoice.SSCreditInvoiceDialog;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Owner-scoped normalized credit-menu routing. Unbound owners retain the exact legacy route. */
public final class CustomerCreditMenuBinding {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerCreditMenuBinding.class);
    private static final String KEY = CustomerCreditMenuBinding.class.getName();
    private static final String REVISION = KEY + ".revision";
    private final JFrame owner;
    private final CustomerDocumentGuiSession session;
    private Consumer<Saved> saved;
    private final List<JDialog> dialogs = new ArrayList<>();
    private final WindowAdapter closed = new WindowAdapter() {
        @Override public void windowClosed(WindowEvent event) { deactivate(); }
    };
    private boolean active = true;

    /** Selection-qualified committed notification; callers must not refresh unrelated legacy/global caches. */
    public record Saved(int companyId, int yearId, long invoiceId, CustomerCreditDialogAdapter.Created credit) { }

    private CustomerCreditMenuBinding(JFrame owner, CustomerDocumentGuiSession session, Consumer<Saved> saved) {
        this.owner = owner; this.session = session; this.saved = saved;
    }

    /**
     * Installs an explicit selection binding on the owner. Replacing it invalidates old dialog
     * leases and suppresses stale-selection refresh callbacks; no database is opened on the EDT.
     */
    public static void install(JFrame owner, CustomerDocumentGuiSession session, Consumer<Saved> saved) {
        requireEdt(); Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(session, "session");
        Objects.requireNonNull(saved, "saved callback");
        if (!session.isActive()) throw new IllegalArgumentException("Cannot install a closed selection");
        var old = (CustomerCreditMenuBinding) owner.getRootPane().getClientProperty(KEY);
        if (old != null && old.session == session && old.active) { old.saved = saved; return; }
        if (old != null) old.deactivate();
        var binding = new CustomerCreditMenuBinding(owner, session, saved);
        owner.getRootPane().putClientProperty(KEY, binding); owner.addWindowListener(binding.closed);
    }

    /**
     * Consumes a normalized menu action before any legacy row/selection lookup. A closed installed
     * context stays consumed rather than silently falling back to legacy storage.
     */
    public static boolean openIfInstalled(JFrame owner) {
        requireEdt();
        var binding = (CustomerCreditMenuBinding) owner.getRootPane().getClientProperty(KEY);
        if (binding == null) return false;
        if (binding.current() && owner.isDisplayable()) binding.showSources();
        return true;
    }

    /**
     * Keeps normalized credit actions independent of legacy row selection. Listener ownership follows
     * the toolbar button display lifecycle; unbound legacy enablement is never overridden.
     */
    public static void bindMenuButton(JFrame owner, javax.swing.AbstractButton button) {
        requireEdt();
        boolean[] attached = {false};
        Runnable update = () -> {
            if (!attached[0]) return;
            var binding = (CustomerCreditMenuBinding) owner.getRootPane().getClientProperty(KEY);
            if (binding != null) button.setEnabled(binding.current() && owner.isDisplayable());
        };
        java.beans.PropertyChangeListener root = event -> {
            if (KEY.equals(event.getPropertyName()) || REVISION.equals(event.getPropertyName())) update.run();
        };
        // Defer until AbstractButton and its model have both processed legacy selection enablement.
        java.beans.PropertyChangeListener enabled = event -> {
            if ("enabled".equals(event.getPropertyName())) SwingUtilities.invokeLater(update);
        };
        Runnable attach = () -> {
            if (!attached[0]) {
                attached[0] = true;
                owner.getRootPane().addPropertyChangeListener(root); button.addPropertyChangeListener(enabled);
            }
            update.run();
        };
        button.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (button.isDisplayable()) attach.run();
                else {
                    attached[0] = false;
                    owner.getRootPane().removePropertyChangeListener(root);
                    button.removePropertyChangeListener(enabled);
                }
            }
        });
        if (button.isDisplayable()) attach.run();
    }

    /** Closes only the matching installed selection; keeps its consumed marker to prevent fallback. */
    static void closeIfInstalled(JFrame owner, CustomerDocumentGuiSession session) {
        requireEdt();
        var binding = (CustomerCreditMenuBinding) owner.getRootPane().getClientProperty(KEY);
        if (binding != null && binding.session == session) binding.deactivate();
    }

    private boolean current() {
        return active && session.isActive() && owner.getRootPane().getClientProperty(KEY) == this;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("GUI menu routing requires the EDT");
    }

    private static String text(String key) { return SSBundle.getBundle().getString("creditinvoice.source." + key); }

    private void deactivate() {
        if (!active) return;
        active = false; session.close(); owner.removeWindowListener(closed);
        owner.getRootPane().putClientProperty(REVISION, new Object());
        for (JDialog dialog : List.copyOf(dialogs)) {
            dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void track(JDialog dialog) {
        dialogs.add(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { dialogs.remove(dialog); }
        });
    }

    private void showSources() {
        JDialog dialog = new JDialog(owner, text("title"), true); dialog.setName("customer-credit-source");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JLabel context = new JLabel(text("loading")); context.setName("credit-source-context");
        JComboBox<CustomerDocumentGuiSession.Source> sources = new JComboBox<>(); sources.setName("credit-source");
        sources.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                Object display = value instanceof CustomerDocumentGuiSession.Source source
                        ? MessageFormat.format(text("invoice"), Integer.toString(source.number()),
                                source.customer() == null ? "" : source.customer(),
                                source.date() == null ? "" : source.date().toString()) : value;
                return super.getListCellRendererComponent(list, display, index, selected, focus);
            }
        });
        JTextArea status = new JTextArea(text("loading"), 3, 52); status.setName("credit-source-status");
        status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true);
        JButton choose = new JButton(text("choose")), cancel = new JButton(text("cancel"));
        choose.setName("credit-source-choose"); cancel.setName("credit-source-cancel"); choose.setEnabled(false);
        JPanel middle = new JPanel(new BorderLayout(8, 8));
        middle.add(sources, BorderLayout.NORTH); middle.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(); buttons.add(choose); buttons.add(cancel);
        dialog.add(context, BorderLayout.NORTH); dialog.add(middle, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        sources.addActionListener(event -> choose.setEnabled(current() && sources.getSelectedItem() != null));
        cancel.addActionListener(event -> dialog.dispose());
        choose.addActionListener(event -> {
            if (!current()) { dialog.dispose(); return; }
            var source = (CustomerDocumentGuiSession.Source) sources.getSelectedItem();
            if (source == null) return;
            try {
                var adapter = session.credit(source.id());
                dialog.dispose();
                JDialog request = SSCreditInvoiceDialog.normalizedDialog(owner, adapter, result -> {
                    if (current()) saved.accept(new Saved(session.companyId(), session.yearId(), source.id(), result));
                    else LOG.info("Credit committed for superseded GUI context; no unrelated refresh dispatched");
                });
                request.setName("normalized-credit-request"); track(request); request.setVisible(true);
            } catch (java.sql.SQLException failure) {
                status.setText(MessageFormat.format(text("failed"), failure.getMessage()));
            }
        });
        track(dialog);
        new SwingWorker<CustomerDocumentGuiSession.Sources, Void>() {
            @Override protected CustomerDocumentGuiSession.Sources doInBackground() throws Exception {
                return session.sources();
            }
            @Override protected void done() {
                if (!current() || !dialog.isDisplayable()) { dialog.dispose(); return; }
                try {
                    var loaded = get();
                    context.setText(MessageFormat.format(text("context"), loaded.companyName(),
                            Integer.toString(loaded.companyId()), String.valueOf(loaded.from()),
                            String.valueOf(loaded.to()), Integer.toString(loaded.yearId())));
                    for (var source : loaded.invoices()) sources.addItem(source);
                    sources.setSelectedIndex(-1); choose.setEnabled(false);
                    status.setText(text(loaded.invoices().isEmpty() ? "empty" : "ready"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); status.setText(text("interrupted"));
                } catch (ExecutionException failure) {
                    status.setText(MessageFormat.format(text("failed"), failure.getCause().getMessage()));
                }
            }
        }.execute();
        dialog.pack(); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }
}
