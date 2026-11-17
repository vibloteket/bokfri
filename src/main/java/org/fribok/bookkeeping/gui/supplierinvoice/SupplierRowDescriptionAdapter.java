package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.RowValue;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Selection-bound supplier row description load/preview/confirm with no mutable domain objects
 * escaping.
 */
public final class SupplierRowDescriptionAdapter {
    /** Immutable initial source with private adapter ownership. */
    public static final class Loaded {
        private final SupplierRowDescriptionAdapter owner;
        private final NormalizedSupplierDocumentMetadataService.RowSnapshot source;

        private Loaded(
                SupplierRowDescriptionAdapter owner,
                NormalizedSupplierDocumentMetadataService.RowSnapshot source) {
            this.owner = owner;
            this.source = source;
        }

        public NormalizedSupplierDocumentMetadataService.RowSnapshot source() {
            return source;
        }
    }

    /** Immutable confirmation values and private workflow token. */
    public static final class Preview {
        private final SupplierRowDescriptionAdapter owner;
        private final NormalizedSupplierDocumentMetadataService.RowPlan plan;
        private Saved confirmed;

        private Preview(
                SupplierRowDescriptionAdapter owner,
                NormalizedSupplierDocumentMetadataService.RowPlan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        public NormalizedSupplierDocumentMetadataService.RowPlan plan() {
            return plan;
        }
    }

    /** Confirmed committed identity for same-selection refresh. */
    public record Saved(int companyId, int yearId, Kind kind, long id) {}

    private final SupplierDocumentGuiSession session;
    private final Kind kind;
    private final long id;

    SupplierRowDescriptionAdapter(SupplierDocumentGuiSession session, Kind kind, long id) {
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
                                new NormalizedSupplierDocumentMetadataService(connection)
                                        .loadRows(company.getId(), kind, id)));
    }

    /** Revalidates source and returns immutable proposed changes without writes. */
    public Preview preview(Loaded loaded, List<RowValue> proposed) throws SQLException {
        if (loaded == null || loaded.owner != this) {
            throw new SQLException("Foreign supplier row snapshot");
        }
        List<RowValue> accepted = List.copyOf(proposed);
        return session.run(
                false,
                (connection, company, year) ->
                        new Preview(
                                this,
                                new NormalizedSupplierDocumentMetadataService(connection)
                                        .previewRows(loaded.source, accepted)));
    }

    /** Revalidates and commits exactly once; confirmed replay opens no new connection/write. */
    public Saved confirm(Preview preview) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (preview == null || preview.owner != this) {
                throw new SQLException("Foreign supplier description preview");
            }
            if (preview.confirmed != null) {
                return preview.confirmed;
            }
            var updated =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierDocumentMetadataService(connection)
                                            .confirmRows(preview.plan));
            preview.confirmed =
                    new Saved(updated.companyId(), session.yearId(), updated.kind(), updated.id());
            return preview.confirmed;
        }
    }
}
