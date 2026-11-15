package org.fribok.bookkeeping.gui.supplierinvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.HierarchyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Explicit owner-scoped supplier document menu routing; unbound owners retain the legacy route. */
public final class SupplierDocumentMenuBinding {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierDocumentMenuBinding.class);
    private static final String KEY = SupplierDocumentMenuBinding.class.getName();
    private static final String REVISION = KEY + ".revision";
    private final JFrame owner;
    private final SupplierDocumentGuiSession session;
    private Consumer<SupplierJournalAdapter.Kind> show;
    private Runnable invalidated;
    private boolean active = true;
    private final WindowAdapter closed =
            new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    deactivate();
                }
            };

    private SupplierDocumentMenuBinding(
            JFrame owner,
            SupplierDocumentGuiSession session,
            Consumer<SupplierJournalAdapter.Kind> show,
            Runnable invalidated) {
        this.owner = owner;
        this.session = session;
        this.show = show;
        this.invalidated = invalidated;
    }

    /** Installs an explicit owner/selection/view binding without opening a database on the EDT. */
    public static void install(
            JFrame owner,
            SupplierDocumentGuiSession session,
            Consumer<SupplierJournalAdapter.Kind> show,
            Runnable invalidated) {
        requireEdt();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(show, "show");
        Objects.requireNonNull(invalidated, "invalidated");
        if (!session.isActive()) {
            throw new IllegalArgumentException("Cannot install a closed supplier selection");
        }
        var previous = binding(owner);
        if (previous != null && previous.session == session && previous.active) {
            previous.show = show;
            previous.invalidated = invalidated;
            refreshControls(owner);
            return;
        }
        if (previous != null) {
            previous.deactivate();
        }
        var next = new SupplierDocumentMenuBinding(owner, session, show, invalidated);
        owner.getRootPane().putClientProperty(KEY, next);
        owner.addWindowListener(next.closed);
    }

    /**
     * Consumes installed normalized actions before any legacy frame/cache/row lookup. Closed or
     * blocked bindings remain consumed; they never silently fall back to another storage mode.
     */
    public static boolean openIfInstalled(JFrame owner, SupplierJournalAdapter.Kind kind) {
        requireEdt();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        var selected = binding(owner);
        if (selected == null) {
            return false;
        }
        if (selected.current() && owner.isDisplayable()) {
            selected.show.accept(kind);
        }
        return true;
    }

    /**
     * Dispatches a real menu action; unbound owners execute the exact caller-supplied legacy route.
     */
    public static void route(JFrame owner, SupplierJournalAdapter.Kind kind, Runnable legacy) {
        requireEdt();
        Objects.requireNonNull(legacy, "legacy route");
        if (!openIfInstalled(owner, kind)) {
            legacy.run();
        }
    }

    /**
     * Gives installed normalized bindings ownership of this button's enablement, independent of
     * legacy company/year events. Unbound enablement is untouched; listeners detach with the
     * button.
     */
    public static void bindMenuButton(JFrame owner, AbstractButton button) {
        requireEdt();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(button, "button");
        if (button.getClientProperty(KEY + ".bound") != null) {
            return;
        }
        var binding = new ButtonBinding(owner, button);
        button.putClientProperty(KEY + ".bound", binding);
        binding.attachIfOwned();
    }

    private static final class ButtonBinding {
        private final JFrame owner;
        private final AbstractButton button;
        private final PropertyChangeListener root;
        private final PropertyChangeListener enabled;
        private final java.awt.event.HierarchyListener ownerLifecycle;
        private boolean attached;
        private boolean displayed;
        private boolean watched;
        private boolean ownedOnce;

        ButtonBinding(JFrame owner, AbstractButton button) {
            this.owner = owner;
            this.button = button;
            root =
                    event -> {
                        if (KEY.equals(event.getPropertyName())
                                || REVISION.equals(event.getPropertyName())) {
                            update();
                        }
                    };
            enabled =
                    event -> {
                        if ("enabled".equals(event.getPropertyName())) {
                            SwingUtilities.invokeLater(this::update);
                        }
                    };
            ownerLifecycle =
                    event -> {
                        if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                            if (owner.isDisplayable()) {
                                displayed = true;
                                attachIfOwned();
                            } else if (displayed) {
                                detach();
                                stopWatchingOwner();
                            }
                        }
                    };
            watchOwner();
            button.addHierarchyListener(
                    event -> {
                        if ((event.getChangeFlags()
                                        & (HierarchyEvent.PARENT_CHANGED
                                                | HierarchyEvent.DISPLAYABILITY_CHANGED))
                                != 0) {
                            attachIfOwned();
                        }
                    });
        }

        void attachIfOwned() {
            // Menu items live in an unrealized popup until the menu opens: their owner's lifecycle,
            // not popup realization, must govern initial normalized enablement.
            if (!belongsToOwner() || (displayed && !owner.isDisplayable())) {
                detach();
                if (ownedOnce || displayed) {
                    stopWatchingOwner();
                }
                return;
            }
            ownedOnce = true;
            watchOwner();
            if (!attached) {
                attached = true;
                displayed = owner.isDisplayable();
                owner.getRootPane().addPropertyChangeListener(root);
                button.addPropertyChangeListener(enabled);
            }
            update();
        }

        private void detach() {
            if (!attached) {
                return;
            }
            attached = false;
            owner.getRootPane().removePropertyChangeListener(root);
            button.removePropertyChangeListener(enabled);
        }

        private boolean belongsToOwner() {
            java.awt.Component component = button;
            while (component != null && component != owner) {
                component =
                        component instanceof javax.swing.JPopupMenu popup
                                ? popup.getInvoker()
                                : component.getParent();
            }
            return component == owner;
        }

        private void watchOwner() {
            if (!watched) {
                watched = true;
                owner.addHierarchyListener(ownerLifecycle);
            }
        }

        private void stopWatchingOwner() {
            if (watched) {
                watched = false;
                owner.removeHierarchyListener(ownerLifecycle);
            }
        }

        private void update() {
            if (!attached) {
                return;
            }
            var selected = binding(owner);
            if (selected != null) {
                button.setEnabled(selected.current() && owner.isDisplayable());
            }
        }
    }

    /** Publishes selection/uncertain-outcome control changes without opening storage. */
    static void refreshControls(JFrame owner) {
        requireEdt();
        owner.getRootPane().putClientProperty(REVISION, new Object());
    }

    static void closeIfInstalled(JFrame owner, SupplierDocumentGuiSession session) {
        requireEdt();
        var selected = binding(owner);
        if (selected != null && selected.session == session) {
            selected.deactivate();
        }
    }

    private static SupplierDocumentMenuBinding binding(JFrame owner) {
        return (SupplierDocumentMenuBinding) owner.getRootPane().getClientProperty(KEY);
    }

    private boolean current() {
        return active && session.isActive() && !session.isBlocked() && binding(owner) == this;
    }

    private void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        session.close();
        owner.removeWindowListener(closed);
        refreshControls(owner);
        try {
            invalidated.run();
        } catch (RuntimeException failure) {
            LOG.error("Supplier selection closed but view invalidation failed", failure);
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Supplier menu routing requires the EDT");
        }
    }
}
