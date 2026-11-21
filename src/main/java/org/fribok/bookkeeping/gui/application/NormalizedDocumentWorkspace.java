package org.fribok.bookkeeping.gui.application;

import org.fribok.bookkeeping.application.NormalizedApplicationOwner;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Company;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Selection;
import org.fribok.bookkeeping.application.NormalizedApplicationOwner.Year;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentView;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentView;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Explicit opt-in host for the two restricted normalized document views. Adopts exclusive ownership
 * of the supplied application owner, not its JFrame or database. This is not legacy application
 * bootstrap: construct it deliberately in a dedicated owner window behind the format-3 guard.
 */
public final class NormalizedDocumentWorkspace extends JPanel implements AutoCloseable {
    private record CompanyChoice(Company value) {
        public String toString() {
            return value == null
                    ? text("choosecompany")
                    : value.id() + " : " + literal(value.name());
        }
    }

    private record YearChoice(Year value) {
        public String toString() {
            return value == null
                    ? text("chooseyear")
                    : value.id() + " : " + value.from() + " - " + value.to();
        }
    }

    private final JFrame frame;
    private final NormalizedApplicationOwner application;
    private final JComboBox<CompanyChoice> companies = new JComboBox<>();
    private final JComboBox<YearChoice> years = new JComboBox<>();
    private final JButton reload = new JButton(text("reload"));
    private final JButton apply = new JButton(text("apply"));
    private final JTextArea context = area();
    private final JTextArea status = area();
    private final JTabbedPane tabs = new JTabbedPane();
    private final NormalizedYearCreationPanel yearCreation;
    private final Timer health;
    private final WindowAdapter window;
    private CustomerDocumentView customers;
    private SupplierDocumentView suppliers;
    private Selection selected;
    private boolean busy;
    private boolean closed;
    private boolean blocked;
    private boolean failed;
    private long revision;

    /** Constructs on the EDT; catalogue loading and selection validation run off the EDT. */
    public NormalizedDocumentWorkspace(JFrame frame, NormalizedApplicationOwner application) {
        super(new BorderLayout(8, 8));
        requireEdt();
        this.frame = Objects.requireNonNull(frame);
        this.application = Objects.requireNonNull(application);
        yearCreation = new NormalizedYearCreationPanel(application, this::reload);
        yearCreation.addPropertyChangeListener("busy", event -> controls());
        setName("normalized-workspace");
        companies.setName("workspace-company");
        years.setName("workspace-year");
        companies.setEditable(false);
        years.setEditable(false);
        reload.setName("workspace-reload");
        apply.setName("workspace-apply");
        context.setName("workspace-context");
        status.setName("workspace-status");
        tabs.setName("workspace-documents");
        context.setText(text("unselected"));
        JPanel choices = new JPanel(new GridLayout(2, 3, 8, 8));
        choices.add(new JLabel(text("company")));
        choices.add(companies);
        choices.add(reload);
        choices.add(new JLabel(text("year")));
        choices.add(years);
        choices.add(apply);
        JPanel header = new JPanel(new BorderLayout());
        header.add(choices, BorderLayout.NORTH);
        header.add(context);
        header.add(status, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(tabs);
        add(yearCreation, BorderLayout.SOUTH);
        companies.addActionListener(
                event -> {
                    years.removeAllItems();
                    years.addItem(new YearChoice(null));
                    var choice = (CompanyChoice) companies.getSelectedItem();
                    if (choice != null && choice.value() != null) {
                        for (var year : choice.value().years()) years.addItem(new YearChoice(year));
                    }
                    years.setSelectedIndex(0);
                    var company = choice == null ? null : choice.value();
                    if (company != null && company.years().isEmpty()) {
                        yearCreation.replaceFirstYearCompany(company);
                    } else {
                        yearCreation.replaceSelection(
                                company != null
                                                && selected != null
                                                && selected.company().id() == company.id()
                                        ? selected
                                        : null);
                    }
                    controls();
                });
        years.addActionListener(event -> controls());
        reload.addActionListener(event -> reload());
        apply.addActionListener(event -> select());
        window =
                new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent event) {
                        close();
                    }
                };
        frame.addWindowListener(window);
        // State-only polling: no JDBC or migration. The application owner is UI-framework
        // independent.
        health = new Timer(100, event -> checkHealth());
        health.start();
        reload();
    }

    /**
     * Immutable current lease for explicit host callbacks; requires EDT, never legacy selection.
     */
    public Optional<Selection> selection() {
        requireEdt();
        return closed ? Optional.empty() : Optional.ofNullable(selected);
    }

    /** Whether a catalogue load or context validation is pending; requires EDT. */
    public boolean isBusy() {
        requireEdt();
        return busy;
    }

    /** Exposes the shared uncertainty state without opening storage. */
    public boolean isBlocked() {
        return application.isBlocked();
    }

    /** Reloads choices without changing the current context or silently selecting a first entry. */
    public void reload() {
        reload(-1);
    }

    private void reload(int companyId) {
        requireEdt();
        if (!available()) {
            return;
        }
        busy = true;
        long request = ++revision;
        status.setText(text("loading"));
        controls();
        new SwingWorker<List<Company>, Void>() {
            protected List<Company> doInBackground() throws Exception {
                return application.companies();
            }

            protected void done() {
                if (closed || request != revision) {
                    return;
                }
                busy = false;
                try {
                    var choices = get();
                    companies.removeAllItems();
                    companies.addItem(new CompanyChoice(null));
                    for (var company : choices) companies.addItem(new CompanyChoice(company));
                    companies.setSelectedIndex(0);
                    for (int index = 1; index < companies.getItemCount(); index++) {
                        if (companies.getItemAt(index).value().id() == companyId) {
                            companies.setSelectedIndex(index);
                        }
                    }
                    status.setText(text("ready"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("loadfailed"));
                } catch (ExecutionException error) {
                    status.setText(text("loadfailed") + " " + error.getCause().getMessage());
                }
                checkHealth();
                controls();
            }
        }.execute();
    }

    private void select() {
        requireEdt();
        if (!available()) {
            return;
        }
        var company = (CompanyChoice) companies.getSelectedItem();
        var year = (YearChoice) years.getSelectedItem();
        if (company == null || company.value() == null || year == null || year.value() == null) {
            return;
        }
        int companyId = company.value().id();
        int yearId = year.value().id();
        busy = true;
        long request = ++revision;
        status.setText(text("selecting"));
        controls();
        new SwingWorker<Selection, Void>() {
            protected Selection doInBackground() throws Exception {
                return application.select(companyId, yearId);
            }

            protected void done() {
                if (closed || request != revision) {
                    return;
                }
                busy = false;
                try {
                    install(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText(text("selectfailed"));
                } catch (ExecutionException error) {
                    status.setText(text("selectfailed") + " " + error.getCause().getMessage());
                } catch (RuntimeException error) {
                    // Backend selection succeeded but host installation failed: never keep half a
                    // host active.
                    failed = true;
                    retire();
                    application.close();
                    health.stop();
                    status.setText(text("hostfailed"));
                    org.slf4j.LoggerFactory.getLogger(NormalizedDocumentWorkspace.class)
                            .error("Cannot install normalized workspace selection", error);
                }
                checkHealth();
                controls();
            }
        }.execute();
    }

    private void install(Selection lease) {
        selected = lease;
        yearCreation.replaceSelection(lease);
        if (customers == null) {
            customers = new CustomerDocumentView(frame, lease.customers());
            suppliers = new SupplierDocumentView(frame, lease.suppliers());
            tabs.addTab(text("customers"), customers);
            tabs.addTab(text("suppliers"), suppliers);
        } else {
            customers.replaceSelection(lease.customers());
            suppliers.replaceSelection(lease.suppliers());
        }
        // Each view installs its existing owner-scoped menu binding and current-context callbacks.
        context.setText(
                MessageFormat.format(
                        text("selected"),
                        Integer.toString(lease.company().id()),
                        literal(lease.company().name()),
                        Integer.toString(lease.year().id()),
                        String.valueOf(lease.year().from()),
                        String.valueOf(lease.year().to())));
        status.setText(text("selectedready"));
    }

    private boolean available() {
        checkHealth();
        return !closed && !blocked && !failed && !busy && !yearCreation.isBusy();
    }

    private void checkHealth() {
        if (!closed && application.isBlocked()) {
            if (!blocked) {
                blocked = true;
                retire();
                health.stop();
            }
            // A late load/selection failure must never replace the stronger uncertainty warning.
            status.setText(text("uncertain"));
            controls();
        }
    }

    private void retire() {
        yearCreation.close();
        if (customers != null) {
            customers.close();
        }
        if (suppliers != null) {
            suppliers.close();
        }
    }

    private void controls() {
        boolean active = !closed && !blocked && !failed && !busy && !yearCreation.isBusy();
        yearCreation.setHostAvailable(active);
        companies.setEnabled(active);
        years.setEnabled(active);
        reload.setEnabled(active);
        var company = (CompanyChoice) companies.getSelectedItem();
        var year = (YearChoice) years.getSelectedItem();
        apply.setEnabled(
                active
                        && company != null
                        && company.value() != null
                        && year != null
                        && year.value() != null);
        tabs.setEnabled(!closed && !blocked && !failed);
    }

    /**
     * Retires both menu/view families and the adopted owner, but never disposes the caller's frame.
     */
    @Override
    public void close() {
        requireEdt();
        if (closed) {
            return;
        }
        closed = true;
        revision++;
        health.stop();
        retire();
        application.close();
        frame.removeWindowListener(window);
        controls();
    }

    private static JTextArea area() {
        var value = new JTextArea(2, 90);
        value.setEditable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        return value;
    }

    private static String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        StringBuilder out = new StringBuilder("\"");
        value.codePoints()
                .forEach(
                        code -> {
                            if (code == '\\') {
                                out.append("\\\\");
                            } else if (code == '"') {
                                out.append("\\\"");
                            } else if (Character.isISOControl(code)) {
                                out.append(String.format("\\u%04x", code));
                            } else {
                                out.appendCodePoint(code);
                            }
                        });
        return out.append('"').toString();
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.workspace." + key);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Normalized workspace requires EDT");
        }
    }
}
