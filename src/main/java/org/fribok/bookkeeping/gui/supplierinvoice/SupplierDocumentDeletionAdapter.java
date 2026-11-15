package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService.Plan;

import java.sql.SQLException;
import java.util.Objects;

/** Selection-bound supplier deletion with dedicated transactions and confirmed-result replay. */
public final class SupplierDocumentDeletionAdapter {
    /** Private workflow token with immutable presentation. */
    public static final class Preview {
        private final SupplierDocumentDeletionAdapter owner;
        private final Plan plan;
        private Saved confirmed;

        private Preview(SupplierDocumentDeletionAdapter owner, Plan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        /** Complete immutable confirmation values. */
        public Plan plan() {
            return plan;
        }
    }

    /** Confirmed committed identity for owner-qualified table refresh. */
    public record Saved(int companyId, int yearId, Kind kind, long id) {}

    private final SupplierDocumentGuiSession session;
    private final Kind kind;
    private final long id;

    SupplierDocumentDeletionAdapter(SupplierDocumentGuiSession session, Kind kind, long id) {
        this.session = Objects.requireNonNull(session);
        this.kind = Objects.requireNonNull(kind);
        this.id = id;
    }

    /** Explicit selected accounting-year identity. */
    public int yearId() {
        return session.yearId();
    }

    /** Whether selection closure/replacement invalidated future requests. */
    public boolean isActive() {
        return session.isActive();
    }

    /** Unknown commit outcomes block all supplier actions in the owning selection. */
    public boolean isBlocked() {
        return session.isBlocked();
    }

    /** Read-only eligibility and complete-source preview in a fresh dedicated session. */
    public Preview preview() throws SQLException {
        return session.run(
                false,
                (connection, company, year) ->
                        new Preview(
                                this,
                                new NormalizedSupplierDocumentDeletionService(connection)
                                        .plan(company.getId(), kind, id)));
    }

    /**
     * Revalidates and commits once; replay of a confirmed token opens no connection or second
     * write.
     */
    public Saved delete(Preview preview) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (preview == null || preview.owner != this) {
                throw new SQLException("Foreign supplier deletion preview");
            }
            if (preview.confirmed != null) {
                return preview.confirmed;
            }
            var result =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierDocumentDeletionService(connection)
                                            .delete(preview.plan));
            preview.confirmed =
                    new Saved(result.companyId(), session.yearId(), result.kind(), result.id());
            return preview.confirmed;
        }
    }
}
