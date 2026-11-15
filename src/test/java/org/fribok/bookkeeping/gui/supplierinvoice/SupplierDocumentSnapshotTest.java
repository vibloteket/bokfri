package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedOutpaymentStore;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

class SupplierDocumentSnapshotTest {
    @Test
    void companyWideSnapshotHasScopedCreditPaymentBalancesAndPreservesRawStoredValues()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture();
                var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
            long outside = f.add(false, 5, "100");
            f.add(true, 5, "10");
            var store = new NormalizedSupplierInvoiceStore(f.anchor);
            var foreign = SupplierJournalGuiFixture.document(false, 1, "999");
            store.addSupplierInvoice(72, foreign);
            store.addSupplierCreditInvoice(
                    72,
                    (SSSupplierCreditInvoice) SupplierJournalGuiFixture.document(true, 1, "999"));
            addPayment(f, 71, "20");
            addPayment(f, 72, "999");
            try (var statement = f.anchor.createStatement()) {
                statement.executeUpdate(
                        "UPDATE supplier_invoice SET"
                            + " invoice_date=DATE'2027-01-01',tax_sum=NULL,rounding_sum=NULL,currency_code=NULL,currency_rate=NULL,bgc_entered=true"
                            + " WHERE id="
                                + outside);
            }
            f.anchor.commit();
            var before = f.snapshot();
            var loaded = f.session().documents();
            assertEquals(71, loaded.companyId());
            assertEquals(11, loaded.yearId());
            assertEquals(SupplierJournalGuiFixture.FROM, loaded.from());
            assertEquals(SupplierJournalGuiFixture.TO, loaded.to());
            assertEquals(2, loaded.invoices().size());
            assertEquals(1, loaded.credits().size());
            var original =
                    loaded.invoices().stream()
                            .filter(row -> row.id() == f.invoice)
                            .findFirst()
                            .orElseThrow();
            assertEquals(0, new BigDecimal("70").compareTo(original.balance()));
            assertTrue(original.entered());
            var historical =
                    loaded.invoices().stream()
                            .filter(row -> row.id() == outside)
                            .findFirst()
                            .orElseThrow();
            assertEquals(LocalDate.of(2027, 1, 1), historical.date());
            assertNull(historical.currency());
            assertEquals(0, new BigDecimal("100").compareTo(historical.total()));
            assertTrue(historical.bankExported());
            assertFalse(historical.entered());
            assertEquals(1, loaded.credits().getFirst().creditingNumber());
            assertNull(loaded.credits().getFirst().balance());
            assertEquals(before, f.snapshot());
            assertThrows(UnsupportedOperationException.class, () -> loaded.invoices().clear());
            assertThrows(UnsupportedOperationException.class, () -> loaded.credits().clear());
        }
    }

    @Test
    void ambiguousAndAbsentNumbersKeepSeparateIdentitiesAndNeverInventBalances() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long first = f.add(false, 5, "50"),
                    duplicate = f.add(false, 6, "60"),
                    absent = f.add(false, 7, "70");
            try (var statement = f.anchor.createStatement()) {
                statement.executeUpdate(
                        "UPDATE supplier_invoice SET number=5 WHERE id=" + duplicate);
                statement.executeUpdate(
                        "UPDATE supplier_invoice SET number=NULL WHERE id=" + absent);
            }
            f.anchor.commit();
            var before = f.snapshot();
            var documents = f.session().documents().invoices();
            for (long id : List.of(first, duplicate, absent)) {
                var row =
                        documents.stream()
                                .filter(value -> value.id() == id)
                                .findFirst()
                                .orElseThrow();
                assertNull(row.balance());
                if (id == absent) assertNull(row.number());
                else assertEquals(5, row.number());
            }
            assertEquals(4, documents.size());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void closedForeignAndUnsupportedSelectionsNeverReadUsingAnotherContextOrMigrate()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            var closed = f.session();
            closed.close();
            assertThrows(SQLException.class, closed::documents);
            var foreign =
                    new SupplierDocumentGuiSession(
                            f::open, f.gate, 71, 12, SupplierJournalGuiFixture.CLOCK);
            assertThrows(SQLException.class, foreign::documents);
            DataFormatManager.recordVersion(f.anchor, 2);
            f.anchor.commit();
            var before = f.snapshot();
            assertThrows(SQLException.class, () -> f.session().documents());
            assertEquals(before, f.snapshot());
        }
    }

    private static void addPayment(SupplierJournalGuiFixture f, int company, String amount)
            throws Exception {
        SSOutpayment payment = SSOutpayment.forPersistence();
        payment.setNumber(1);
        payment.setLocalDate(SupplierJournalGuiFixture.FROM);
        SSOutpaymentRow row = new SSOutpaymentRow();
        row.setInvoiceNr(1);
        row.setValue(new BigDecimal(amount));
        payment.setRows(List.of(row));
        new NormalizedOutpaymentStore(f.anchor).addOutpayment(company, payment);
    }
}
