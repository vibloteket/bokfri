package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Explicit selection-bound raw supplier metadata boundary; never a mutable financial-domain
 * snapshot.
 */
public final class SupplierDocumentMetadataAdapter {
    /** Immutable source values and a private owner-bound stale-source token. */
    public static final class Loaded {
        private final SupplierDocumentMetadataAdapter owner;
        private final NormalizedSupplierDocumentMetadataService.Snapshot source;
        private Saved confirmed;
        private String confirmedName;
        private String confirmedReference;

        private Loaded(
                SupplierDocumentMetadataAdapter owner,
                NormalizedSupplierDocumentMetadataService.Snapshot source) {
            this.owner = owner;
            this.source = source;
        }

        /** Read-only raw source metadata. */
        public NormalizedSupplierDocumentMetadataService.Snapshot source() {
            return source;
        }
    }

    /** Confirmed committed identity for same-session owner refresh. */
    public record Saved(int companyId, int yearId, Kind kind, long id) {}

    private final SupplierDocumentGuiSession session;
    private final Kind kind;
    private final long id;

    SupplierDocumentMetadataAdapter(SupplierDocumentGuiSession session, Kind kind, long id) {
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

    /** Unknown outcomes block every supplier action in this selection. */
    public boolean isBlocked() {
        return session.isBlocked();
    }

    /** Loads raw strings and complete-source token in a dedicated read-only session. */
    public Loaded load() throws SQLException {
        return session.run(
                false,
                (connection, company, year) ->
                        new Loaded(
                                this,
                                new NormalizedSupplierDocumentMetadataService(connection)
                                        .load(company.getId(), kind, id)));
    }

    /**
     * Saves only bounded metadata once; a confirmed load token cannot be reused for different
     * values.
     */
    public Saved save(Loaded loaded, String name, String reference) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (loaded == null || loaded.owner != this) {
                throw new SQLException("Foreign supplier metadata snapshot");
            }
            if (loaded.confirmed != null) {
                if (!Objects.equals(name, loaded.confirmedName)
                        || !Objects.equals(reference, loaded.confirmedReference)) {
                    throw new SQLException(
                            "Metadata snapshot was already confirmed; reload before another"
                                + " change");
                }
                return loaded.confirmed;
            }
            var updated =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierDocumentMetadataService(connection)
                                            .update(loaded.source, name, reference));
            loaded.confirmed =
                    new Saved(updated.companyId(), session.yearId(), updated.kind(), updated.id());
            loaded.confirmedName = name;
            loaded.confirmedReference = reference;
            return loaded.confirmed;
        }
    }
}
