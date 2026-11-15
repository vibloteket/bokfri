package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService.Kind;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

class SupplierDocumentDeletionAdapterTest {
    @Test
    void immutableNoWritePreviewsDeleteExactlyOnceAndLeaveOtherTablesAndCountersUntouched()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = addGraph(f, credit, 5);
                var before = f.snapshot();
                AtomicInteger opens = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    opens.incrementAndGet();
                                    return f.open();
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.deletion(kind, id);
                var preview = adapter.preview();
                assertEquals(before, f.snapshot());
                var p = preview.plan();
                assertEquals(id, p.id());
                assertEquals(71, p.companyId());
                assertEquals(kind, p.kind());
                assertEquals(5, p.number());
                assertEquals("Supplier 5", p.supplier());
                assertEquals(0, new BigDecimal("100").compareTo(p.total()));
                var saved = adapter.delete(preview);
                assertSame(saved, adapter.delete(preview));
                assertEquals(2, opens.get());
                assertEquals(id, saved.id());
                assertEquals(11, saved.yearId());
                assertFalse(exists(f, credit, id));
                var after = f.snapshot();
                assertOtherTables(before, after, credit);
                assertTrue(exists(f, false, f.invoice));
            }
    }

    @Test
    void nullAndDuplicateBusinessNumbersNeverReplaceStableDeletionIdentity() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = f.add(credit, 5, "100"), other = f.add(credit, 6, "100");
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("UPDATE " + table(credit) + " SET number=5 WHERE id=" + other);
                }
                f.anchor.commit();
                var session = f.session();
                var adapter = session.deletion(kind, id);
                adapter.delete(adapter.preview());
                assertFalse(exists(f, credit, id));
                assertTrue(exists(f, credit, other));
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE " + table(credit) + " SET number=NULL WHERE id=" + other);
                }
                f.anchor.commit();
                var remaining = session.deletion(kind, other);
                var preview = remaining.preview();
                assertNull(preview.plan().number());
                remaining.delete(preview);
                assertFalse(exists(f, credit, other));
            }
    }

    @Test
    void allLinkedInvoiceWorkflowsAreRefusedBothAtPreviewAndAfterConfirmation() throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100");
                var session = f.session();
                var adapter = session.deletion(Kind.INVOICE, id);
                var preview = adapter.preview();
                reference(f, reference, 71, 5);
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> adapter.delete(preview));
                assertThrows(SQLException.class, adapter::preview);
                assertEquals(before, f.snapshot());
                assertFalse(session.isBlocked());
                assertTrue(exists(f, false, id));
            }
    }

    @Test
    void foreignCompanyReferencesNeitherBlockNorDetachTheSelectedCompanyDocument()
            throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100");
                reference(f, reference, 72, 5);
                var before = f.snapshot();
                var adapter = f.session().deletion(Kind.INVOICE, id);
                adapter.delete(adapter.preview());
                assertFalse(exists(f, false, id));
                assertOtherTables(before, f.snapshot(), false);
            }
    }

    @Test
    void bookedBankExportedForeignMissingAndClosedTargetsAreRefusedWithoutWrites()
            throws Exception {
        for (var kind : Kind.values())
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = f.add(credit, 5, "100");
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET "
                                        + flag
                                        + "=true WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    var session = f.session();
                    assertThrows(SQLException.class, () -> session.deletion(kind, id).preview());
                    assertThrows(SQLException.class, () -> session.deletion(kind, -1).preview());
                    assertThrows(
                            SQLException.class,
                            () ->
                                    new NormalizedSupplierDocumentDeletionService(f.anchor)
                                            .plan(72, kind, id));
                    assertEquals(before, f.snapshot());
                    session.close();
                    assertThrows(SQLException.class, () -> session.deletion(kind, id));
                }
    }

    @Test
    void completeGraphChangesInvalidateConfirmationIncludingNullsKeysAndNanosecondAudit()
            throws Exception {
        for (var kind : Kind.values())
            for (String changed : List.of("header", "row", "key", "account", "voucher", "audit"))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = addGraph(f, credit, 5);
                    var adapter = f.session().deletion(kind, id);
                    var preview = adapter.preview();
                    String table = table(credit), owner = table + "_id";
                    if (changed.equals("audit"))
                        try (var s =
                                f.anchor.prepareStatement(
                                        "UPDATE "
                                                + table
                                                + "_voucher_row SET edited_at=? WHERE "
                                                + owner
                                                + "=?")) {
                            s.setObject(1, OffsetDateTime.parse("2026-10-01T12:13:14.123456788Z"));
                            s.setLong(2, id);
                            s.executeUpdate();
                        }
                    else
                        try (var s = f.anchor.createStatement()) {
                            String sql =
                                    switch (changed) {
                                        case "header" ->
                                                "UPDATE "
                                                        + table
                                                        + " SET reference_number='' WHERE id="
                                                        + id;
                                        case "row" ->
                                                "UPDATE "
                                                        + table
                                                        + "_row SET description='Changed' WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "key" ->
                                                "UPDATE "
                                                        + table
                                                        + "_row SET row_number=99 WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        case "account" ->
                                                "UPDATE "
                                                        + table
                                                        + "_default_account SET account_number=NULL"
                                                        + " WHERE "
                                                        + owner
                                                        + "="
                                                        + id;
                                        default ->
                                                "UPDATE "
                                                        + table
                                                        + "_voucher SET description='Changed' WHERE"
                                                        + " "
                                                        + owner
                                                        + "="
                                                        + id;
                                    };
                            s.executeUpdate(sql);
                        }
                    f.anchor.commit();
                    var before = f.snapshot();
                    assertThrows(SQLException.class, () -> adapter.delete(preview), changed);
                    assertEquals(before, f.snapshot());
                    adapter.delete(adapter.preview());
                    assertFalse(exists(f, credit, id));
                }
    }

    @Test
    void lateParentFailureRestoresAllSnapshotsAndRetainsEarlierCallerWorkThenAllowsRetry()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = addGraph(f, credit, 5);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "CREATE TABLE deletion_blocker(id BIGINT REFERENCES "
                                    + table(credit)
                                    + "(id))");
                    s.executeUpdate("INSERT INTO deletion_blocker VALUES(" + id + ")");
                }
                f.anchor.commit();
                var service = new NormalizedSupplierDocumentDeletionService(f.anchor);
                var preview = service.plan(71, kind, id);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE company SET name='Earlier caller work' WHERE legacy_id=72");
                }
                var before = snapshot(f.anchor);
                assertThrows(SQLException.class, () -> service.delete(preview));
                assertEquals(before, snapshot(f.anchor));
                f.anchor.rollback();
                try (var s = f.anchor.createStatement();
                        var r = s.executeQuery("SELECT name FROM company WHERE legacy_id=72")) {
                    r.next();
                    assertEquals("GUI company 72", r.getString(1));
                }
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate("DROP TABLE deletion_blocker");
                }
                f.anchor.commit();
                var source = snapshot(f.anchor);
                service.delete(preview);
                f.anchor.rollback();
                assertEquals(source, snapshot(f.anchor));
                service.delete(preview);
                f.anchor.commit();
                assertFalse(exists(f, credit, id));
            }
    }

    @Test
    void unknownCommitBlocksSiblingDeletionJournalAndSelectionReadsOnBothDurabilitySides()
            throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100"), siblingId = f.add(true, 5, "10");
                AtomicInteger opens = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    opens.incrementAndGet();
                                    var c = f.open();
                                    return SupplierJournalGuiFixture.wrap(
                                            c,
                                            (method, args) -> {
                                                if (method.getName().equals("commit")) {
                                                    if (durable) c.commit();
                                                    throw new SQLException(
                                                            "Unknown deletion outcome");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var adapter = session.deletion(Kind.INVOICE, id);
                var sibling = session.deletion(Kind.CREDIT, siblingId);
                var journal = session.journal(SupplierJournalAdapter.Kind.CREDIT);
                var jp =
                        journal.preview(
                                SupplierJournalGuiFixture.FROM, SupplierJournalGuiFixture.TO);
                var preview = adapter.preview();
                assertThrows(SQLException.class, () -> adapter.delete(preview));
                int count = opens.get();
                assertTrue(session.isBlocked());
                assertThrows(SQLException.class, sibling::preview);
                assertThrows(SQLException.class, () -> journal.post(jp));
                assertThrows(SQLException.class, session::documents);
                assertThrows(SQLException.class, () -> adapter.delete(preview));
                assertEquals(count, opens.get());
                assertEquals(!durable, exists(f, false, id));
                assertTrue(exists(f, true, siblingId));
            }
    }

    @Test
    void committedCloseFailureStillConfirmsReplayWithoutAnotherWrite() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(true, 5, "100");
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                int call = opens.incrementAndGet();
                                var c = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        c,
                                        (method, args) -> {
                                            Object value =
                                                    SupplierJournalGuiFixture.invoke(
                                                            c, method, args);
                                            if (call == 2 && method.getName().equals("close"))
                                                throw new SQLException("Confirmed close failure");
                                            return value;
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.deletion(Kind.CREDIT, id);
            var preview = adapter.preview();
            var saved = adapter.delete(preview);
            assertSame(saved, adapter.delete(preview));
            assertEquals(2, opens.get());
            assertFalse(session.isBlocked());
            assertFalse(exists(f, true, id));
        }
    }

    @Test
    void foreignTokensAndReplacedSelectionNeverDeleteOrOpenAnotherSession() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.deletion(Kind.INVOICE, id);
            var preview = adapter.preview();
            assertThrows(
                    SQLException.class, () -> session.deletion(Kind.INVOICE, id).delete(preview));
            assertEquals(1, opens.get());
            session.close();
            assertThrows(SQLException.class, () -> adapter.delete(preview));
            assertEquals(1, opens.get());
            assertTrue(exists(f, false, id));
        }
    }

    @Test
    void servicePlanningIsReadOnlyInAutoCommitButExecutionRequiresCallerTransaction()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = addGraph(f, false, 5);
            var before = f.snapshot();
            f.anchor.setAutoCommit(true);
            var service = new NormalizedSupplierDocumentDeletionService(f.anchor);
            var preview = service.plan(71, Kind.INVOICE, id);
            assertThrows(SQLException.class, () -> service.delete(preview));
            f.anchor.setAutoCommit(false);
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void newlyBookedExportedOrMissingTargetsCannotBeDeletedUsingAnOldConfirmation()
            throws Exception {
        for (String change : List.of("entered", "bgc_entered", "missing"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100");
                var adapter = f.session().deletion(Kind.INVOICE, id);
                var preview = adapter.preview();
                if (change.equals("missing"))
                    new NormalizedSupplierInvoiceStore(f.anchor).deleteSupplierInvoice(71, id);
                else
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE supplier_invoice SET " + change + "=true WHERE id=" + id);
                    }
                f.anchor.commit();
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> adapter.delete(preview));
                assertEquals(before, f.snapshot());
            }
    }

    static long addGraph(SupplierJournalGuiFixture f, boolean credit, int number) throws Exception {
        SSSupplierInvoice document = SupplierJournalGuiFixture.document(credit, number, "100");
        document.setStockInfluencing(true);
        SSVoucher voucher = new SSVoucher(7, SupplierJournalGuiFixture.FROM);
        voucher.setDescription("Saved snapshot");
        SSVoucherRow row = new SSVoucherRow();
        row.setAccountNr(2440);
        row.setDebet(BigDecimal.ONE);
        row.setLocalEditedDate(LocalDateTime.parse("2026-10-01T12:13:14.123456789"));
        voucher.getRows().add(row);
        document.setVoucher(voucher);
        document.setCorrection(new SSVoucher(voucher));
        var store = new NormalizedSupplierInvoiceStore(f.anchor);
        long id =
                credit
                        ? store.addSupplierCreditInvoice(71, (SSSupplierCreditInvoice) document)
                        : store.addSupplierInvoice(71, document);
        f.anchor.commit();
        return id;
    }

    static String table(boolean credit) {
        return credit ? "supplier_credit_invoice" : "supplier_invoice";
    }

    static boolean exists(SupplierJournalGuiFixture f, boolean credit, long id) throws Exception {
        var store = new NormalizedSupplierInvoiceStore(f.anchor);
        boolean exists =
                credit
                        ? store.findSupplierCreditInvoice(71, id).isPresent()
                        : store.findSupplierInvoice(71, id).isPresent();
        f.anchor.rollback();
        return exists;
    }

    static void reference(SupplierJournalGuiFixture f, String type, int company, int number)
            throws Exception {
        try (var s = f.anchor.createStatement()) {
            String owner = "(SELECT id FROM company WHERE legacy_id=" + company + ")";
            if (type.equals("payment")) {
                s.executeUpdate(
                        "INSERT INTO outpayment(legacy_id,company_id,number,entered) VALUES(901,"
                                + owner
                                + ",1,false)");
                s.executeUpdate(
                        "INSERT INTO outpayment_row(outpayment_id,row_number,invoice_number,value)"
                            + " SELECT id,0,"
                                + number
                                + ",1 FROM outpayment WHERE legacy_id=901");
            } else if (type.equals("credit"))
                s.executeUpdate(
                        "INSERT INTO"
                            + " supplier_credit_invoice(legacy_id,company_id,number,crediting_invoice_number,entered,bgc_entered,stock_influencing)"
                            + " VALUES(901,"
                                + owner
                                + ",99,"
                                + number
                                + ",false,false,false)");
            else
                s.executeUpdate(
                        "INSERT INTO"
                            + " purchase_order(legacy_id,company_id,number,invoice_number,printed,stock_influencing)"
                            + " VALUES(901,"
                                + owner
                                + ",1,"
                                + number
                                + ",false,false)");
        }
        f.anchor.commit();
    }

    static void assertOtherTables(
            Map<String, List<String>> before, Map<String, List<String>> after, boolean credit) {
        String family = table(credit).toUpperCase(java.util.Locale.ROOT);
        for (String table : before.keySet())
            if (!table.equals(family) && !table.startsWith(family + "_"))
                assertEquals(before.get(table), after.get(table), table);
    }

    static Map<String, List<String>> snapshot(Connection c) throws Exception {
        Map<String, List<String>> tables = new TreeMap<>();
        try (var metadata =
                c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (metadata.next()) {
                String table = metadata.getString("TABLE_NAME");
                List<String> rows = new ArrayList<>();
                try (var s = c.createStatement();
                        var result = s.executeQuery("SELECT * FROM " + table)) {
                    while (result.next()) {
                        List<String> values = new ArrayList<>();
                        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
                            values.add(result.getString(i));
                        rows.add(values.toString());
                    }
                }
                Collections.sort(rows);
                tables.put(table, rows);
            }
        }
        return tables;
    }
}
