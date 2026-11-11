package org.fribok.bookkeeping.service.invoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.dataformat.NormalizedSaleDocumentStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Objects;

/**
 * Guarded customer-document operations, separate from financial posting/correction.
 * Uses only the supplied connection. The caller owns commit, connection and exclusive application access.
 */
public final class NormalizedCustomerDocumentService {
    private final Connection connection;
    private final NormalizedInvoiceStore invoices;
    private final NormalizedSaleDocumentStore orders;

    /** Uses the supplied caller-owned transaction without legacy globals or implicit commits. */
    public NormalizedCustomerDocumentService(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        invoices = new NormalizedInvoiceStore(connection);
        orders = new NormalizedSaleDocumentStore(connection);
    }

    /** Updates the explicitly enumerated invoice metadata fields, never financial snapshots. */
    public void updateInvoiceMetadata(int company, long id, Metadata expected, Metadata replacement)
            throws SQLException {
        invoices.updateInvoiceMetadata(company, id, expected, replacement);
    }

    /** Updates the explicitly enumerated credit metadata fields, including on booked credits. */
    public void updateCreditMetadata(int company, long id, Metadata expected, Metadata replacement)
            throws SQLException {
        invoices.updateCreditInvoiceMetadata(company, id, expected, replacement);
    }

    /** Records one successful reminder action without replacing a stale metadata snapshot. */
    public Metadata recordInvoiceReminder(int company, long id, Metadata expected) throws SQLException {
        Objects.requireNonNull(expected, "expected metadata");
        if (expected.reminderCount() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Reminder count exhausted");
        }
        Metadata next = new Metadata(expected.printed(), expected.ocrNumber(), expected.reminderCount() + 1,
                expected.interestInvoiced());
        invoices.updateInvoiceMetadata(company, id, expected, next);
        return next;
    }

    /** Identity and explicit order-detachment result; caller still owns final commit. */
    public record DeletedInvoice(long id, int legacyId, Integer number, List<Long> detachedOrderIds) {
        public DeletedInvoice { detachedOrderIds = List.copyOf(detachedOrderIds); }
    }

    /**
     * Deletes an unbooked invoice and explicitly detaches its linked orders under one savepoint.
     * Refuses payment/credit references and booked invoices. Ambiguous historical invoice numbers
     * cannot designate order ownership, so their links are never guessed or silently reassigned.
     */
    public DeletedInvoice deleteInvoiceWithOrderDetachment(int company, long id) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Deletion requires a caller-owned transaction");
        Savepoint savepoint = connection.setSavepoint();
        try {
            var stored = invoices.findInvoice(company, id)
                    .orElseThrow(() -> new SQLException("Missing or company-mismatched invoice " + id));
            if (stored.document().isEntered()) throw new SQLException("Booked invoices require a correction workflow");
            Integer number = stored.document().getNumber();
            List<Long> detached = List.of();
            if (number != null) {
                List<Long> linked = orders.orderIdsForInvoice(company, number);
                if (!linked.isEmpty() && invoices.listInvoices(company).stream()
                        .filter(item -> Objects.equals(item.document().getNumber(), number)).count() != 1) {
                    throw new SQLException("Ambiguous invoice number cannot identify linked orders: " + number);
                }
                detached = orders.detachOrdersFromInvoice(company, number);
            }
            // Repository guard still refuses payments/credits; any such refusal restores all order links.
            invoices.deleteInvoice(company, id);
            connection.releaseSavepoint(savepoint);
            return new DeletedInvoice(id, stored.legacyId(), number, detached);
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(savepoint); }
            catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
