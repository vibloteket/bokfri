package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierIdentityService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierIdentityService.Values;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Selection-bound supplier supplier-number load/preview/confirm with no mutable domain objects
 * escaping.
 */
public final class SupplierIdentityAdapter {
    /** Immutable initial source with private adapter ownership. */
    public static final class Loaded {
        private final SupplierIdentityAdapter owner;
        private final NormalizedSupplierIdentityService.Snapshot source;

        private Loaded(
                SupplierIdentityAdapter owner, NormalizedSupplierIdentityService.Snapshot source) {
            this.owner = owner;
            this.source = source;
        }

        public NormalizedSupplierIdentityService.Snapshot source() {
            return source;
        }
    }

    /** Immutable confirmation values and private workflow token. */
    public static final class Preview {
        private final SupplierIdentityAdapter owner;
        private final NormalizedSupplierIdentityService.Plan plan;
        private Saved confirmed;

        private Preview(
                SupplierIdentityAdapter owner, NormalizedSupplierIdentityService.Plan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        public NormalizedSupplierIdentityService.Plan plan() {
            return plan;
        }
    }

    /** Confirmed committed identity for same-selection refresh. */
    public record Saved(int companyId, int yearId, Kind kind, long id) {}

    private final SupplierDocumentGuiSession session;
    private final Kind kind;
    private final long id;

    SupplierIdentityAdapter(SupplierDocumentGuiSession session, Kind kind, long id) {
        this.session = Objects.requireNonNull(session);
        this.kind = Objects.requireNonNull(kind);
        this.id = id;
    }

    public int yearId() {
        return session.yearId();
    }

    public boolean isActive() {
        return session.isActive();
    }

    public boolean isBlocked() {
        return session.isBlocked();
    }

    /** Read-only raw snapshot in a fresh dedicated session. */
    public Loaded load() throws SQLException {
        return session.run(
                false,
                (connection, company, year) ->
                        new Loaded(
                                this,
                                new NormalizedSupplierIdentityService(connection)
                                        .load(company.getId(), kind, id)));
    }

    /** Revalidates source and returns immutable proposed changes without writes. */
    public Preview preview(Loaded loaded, Values proposed) throws SQLException {
        if (loaded == null || loaded.owner != this) {
            throw new SQLException("Foreign supplier supplier-number snapshot");
        }
        Values accepted = Objects.requireNonNull(proposed);
        return session.run(
                false,
                (connection, company, year) ->
                        new Preview(
                                this,
                                new NormalizedSupplierIdentityService(connection)
                                        .preview(loaded.source, accepted)));
    }

    /** Revalidates and commits exactly once; confirmed replay opens no new connection/write. */
    public Saved confirm(Preview preview) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (preview == null || preview.owner != this) {
                throw new SQLException("Foreign supplier supplier-number preview");
            }
            if (preview.confirmed != null) {
                return preview.confirmed;
            }
            var updated =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierIdentityService(connection)
                                            .confirm(preview.plan));
            preview.confirmed =
                    new Saved(updated.companyId(), session.yearId(), updated.kind(), updated.id());
            return preview.confirmed;
        }
    }
}
