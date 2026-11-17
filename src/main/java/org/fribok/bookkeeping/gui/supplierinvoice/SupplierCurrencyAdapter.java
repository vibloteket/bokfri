package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.CurrencyValues;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.Kind;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Selection-bound supplier currency load/preview/confirm with no mutable domain objects escaping.
 */
public final class SupplierCurrencyAdapter {
    /** Immutable initial source with private adapter ownership. */
    public static final class Loaded {
        private final SupplierCurrencyAdapter owner;
        private final NormalizedSupplierExchangeRateService.Snapshot source;
        private final List<String> currencies;

        private Loaded(
                SupplierCurrencyAdapter owner,
                NormalizedSupplierExchangeRateService.Snapshot source,
                List<String> currencies) {
            this.owner = owner;
            this.source = source;
            this.currencies = List.copyOf(currencies);
        }

        public List<String> currencies() {
            return currencies;
        }

        public NormalizedSupplierExchangeRateService.Snapshot source() {
            return source;
        }
    }

    /** Immutable confirmation values and private workflow token. */
    public static final class Preview {
        private final SupplierCurrencyAdapter owner;
        private final NormalizedSupplierExchangeRateService.CurrencyPlan plan;
        private Saved confirmed;

        private Preview(
                SupplierCurrencyAdapter owner,
                NormalizedSupplierExchangeRateService.CurrencyPlan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        public NormalizedSupplierExchangeRateService.CurrencyPlan plan() {
            return plan;
        }
    }

    /** Confirmed committed identity for same-selection refresh. */
    public record Saved(int companyId, int yearId, Kind kind, long id) {}

    private final SupplierDocumentGuiSession session;
    private final Kind kind;
    private final long id;

    SupplierCurrencyAdapter(SupplierDocumentGuiSession session, Kind kind, long id) {
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
                (connection, company, year) -> {
                    var service = new NormalizedSupplierExchangeRateService(connection);
                    return new Loaded(
                            this, service.load(company.getId(), kind, id), service.currencyCodes());
                });
    }

    /** Revalidates source and returns immutable proposed changes without writes. */
    public Preview preview(Loaded loaded, CurrencyValues proposed) throws SQLException {
        if (loaded == null || loaded.owner != this) {
            throw new SQLException("Foreign supplier currency snapshot");
        }
        CurrencyValues accepted = Objects.requireNonNull(proposed);
        return session.run(
                false,
                (connection, company, year) ->
                        new Preview(
                                this,
                                new NormalizedSupplierExchangeRateService(connection)
                                        .previewCurrency(loaded.source, accepted)));
    }

    /** Revalidates and commits exactly once; confirmed replay opens no new connection/write. */
    public Saved confirm(Preview preview) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (preview == null || preview.owner != this) {
                throw new SQLException("Foreign supplier currency preview");
            }
            if (preview.confirmed != null) {
                return preview.confirmed;
            }
            var updated =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierExchangeRateService(connection)
                                            .confirmCurrency(preview.plan));
            preview.confirmed =
                    new Saved(updated.companyId(), session.yearId(), updated.kind(), updated.id());
            return preview.confirmed;
        }
    }
}
