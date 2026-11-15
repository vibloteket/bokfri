package org.fribok.bookkeeping.dataformat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSPurchaseOrder;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

class NormalizedSupplierDocumentCrudTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void bothKindsKeepStableIdsNullableDefaultsAndEverySnapshotOnNoopReplacementTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture();
                    var ignored = new LegacyGuard()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                f.c.commit();
                var loaded = find(f.store, credit, 31, id);
                assertEquals(id, loaded.id());
                assertTrue(loaded.legacyId() > 0);
                assertTrue(
                        loaded.document()
                                .getStoredDefaultAccounts()
                                .containsKey(SSDefaultAccount.SupplierDebt));
                assertNull(
                        loaded.document()
                                .getStoredDefaultAccounts()
                                .get(SSDefaultAccount.SupplierDebt));
                assertEquals(2, loaded.document().getRows().size());
                assertNull(loaded.document().getRows().get(1).getQuantity());
                assertNull(loaded.document().getRows().get(1).getAccountNr());
                assertEquals(
                        123456789,
                        loaded.document()
                                .getCorrection()
                                .getRows()
                                .getFirst()
                                .getLocalEditedDate()
                                .getNano());
                var before = snapshot(f.c, null, -1);
                update(f.store, credit, 31, id, loaded.document());
                assertEquals(before, snapshot(f.c, null, -1));
                assertEquals(loaded.legacyId(), find(f.store, credit, 31, id).legacyId());
                assertEquals(
                        1,
                        credit
                                ? f.store.listSupplierCreditInvoices(31).size()
                                : f.store.listSupplierInvoices(31).size());
            }
    }

    @Test
    void editDeleteAreCompanyScopedAndLeaveOtherSqlCountersAndSourceInvoicesUntouchedTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture();
                    var ignored = new LegacyGuard()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                long other = add(f.store, credit, 32, document(credit, 101));
                f.c.commit();
                var before = snapshot(f.c, prefix(credit), id);
                var loaded = find(f.store, credit, 31, id);
                assertTrue(
                        credit
                                ? f.store.findSupplierCreditInvoice(32, id).isEmpty()
                                : f.store.findSupplierInvoice(32, id).isEmpty());
                var changed = loaded.document();
                changed.setNumber(102);
                changed.setSupplierName("Edited Å");
                changed.setReferencenumber(null);
                changed.setTaxSum(new BigDecimal("33.75"));
                changed.getRows().getFirst().setUnitprice(new BigDecimal("41.25"));
                changed.setCorrection(null);
                assertThrows(SQLException.class, () -> update(f.store, credit, 32, id, changed));
                assertThrows(SQLException.class, () -> delete(f.store, credit, 32, id));
                update(f.store, credit, 31, id, changed);
                assertEquals(before, snapshot(f.c, prefix(credit), id));
                assertEquals(
                        "Edited Å", find(f.store, credit, 31, id).document().getSupplierName());
                assertNull(find(f.store, credit, 31, id).document().getCorrection());
                assertEquals(loaded.legacyId(), find(f.store, credit, 31, id).legacyId());
                delete(f.store, credit, 31, id);
                assertTrue(
                        credit
                                ? f.store.findSupplierCreditInvoice(31, id).isEmpty()
                                : f.store.findSupplierInvoice(31, id).isEmpty());
                assertEquals(before, snapshot(f.c, prefix(credit), id));
                assertNotNull(find(f.store, credit, 32, other));
                assertEquals(
                        901,
                        credit
                                ? f.store.nextSupplierCreditInvoiceNumber(31)
                                : f.store.nextSupplierInvoiceNumber(31));
            }
    }

    @Test
    void bookedBankExportedAndPostingStateChangesAreRefusedWithoutWritesTest() throws Exception {
        for (boolean credit : List.of(false, true))
            for (String flag : List.of("entered", "bgc_entered"))
                try (var f = new Fixture()) {
                    long id = add(f.store, credit, 31, document(credit, 101));
                    f.c.commit();
                    var changed = find(f.store, credit, 31, id).document();
                    if (flag.equals("entered")) changed.setEntered(true);
                    else changed.setBGCEntered(true);
                    var before = snapshot(f.c, null, -1);
                    assertThrows(
                            SQLException.class, () -> update(f.store, credit, 31, id, changed));
                    assertEquals(before, snapshot(f.c, null, -1));
                    try (var sql = f.c.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(credit)
                                        + " SET "
                                        + flag
                                        + "=TRUE WHERE id="
                                        + id);
                    }
                    f.c.commit();
                    var protectedValue = find(f.store, credit, 31, id).document();
                    var protectedSnapshot = snapshot(f.c, null, -1);
                    assertThrows(
                            SQLException.class,
                            () -> update(f.store, credit, 31, id, protectedValue));
                    assertThrows(SQLException.class, () -> delete(f.store, credit, 31, id));
                    assertEquals(protectedSnapshot, snapshot(f.c, null, -1));
                }
    }

    @Test
    void supplierReferencesProtectUpdatesDeletesAndRenumberingButForeignReferencesDoNotTest()
            throws Exception {
        for (String reference : List.of("payment", "credit", "order"))
            try (var f = new Fixture()) {
                long id = add(f.store, false, 31, document(false, 101));
                f.c.commit();
                reference(f, reference, 31, 101);
                var value = find(f.store, false, 31, id).document();
                var before = snapshot(f.c, null, -1);
                assertThrows(SQLException.class, () -> update(f.store, false, 31, id, value));
                assertThrows(SQLException.class, () -> delete(f.store, false, 31, id));
                value.setNumber(102);
                assertThrows(SQLException.class, () -> update(f.store, false, 31, id, value));
                assertEquals(before, snapshot(f.c, null, -1));
            }
        try (var f = new Fixture()) {
            long id = add(f.store, false, 31, document(false, 101));
            f.c.commit();
            for (String reference : List.of("payment", "credit", "order"))
                reference(f, reference, 32, 101);
            var before = snapshot(f.c, prefix(false), id);
            var value = find(f.store, false, 31, id).document();
            value.setNumber(102);
            update(f.store, false, 31, id, value);
            delete(f.store, false, 31, id);
            assertEquals(before, snapshot(f.c, prefix(false), id));
        }
    }

    @Test
    void duplicateReservedAndNullBusinessNumbersHaveExplicitIdentitySemanticsTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                f.c.commit();
                var before = snapshot(f.c, null, -1);
                assertThrows(
                        SQLException.class, () -> add(f.store, credit, 31, document(credit, 101)));
                assertEquals(before, snapshot(f.c, null, -1));
                long second = add(f.store, credit, 31, document(credit, 102));
                var changed = find(f.store, credit, 31, second).document();
                changed.setNumber(101);
                assertThrows(
                        SQLException.class, () -> update(f.store, credit, 31, second, changed));
                var nullNumber = document(credit, null);
                long nil = add(f.store, credit, 31, nullNumber);
                update(f.store, credit, 31, nil, find(f.store, credit, 31, nil).document());
                assertNull(find(f.store, credit, 31, nil).document().getNumber());
                delete(f.store, credit, 31, nil);
                assertNotNull(find(f.store, credit, 31, id));
            }
        try (var f = new Fixture()) {
            long id = add(f.store, false, 31, document(false, 101));
            reference(f, "payment", 31, 42);
            var changed = find(f.store, false, 31, id).document();
            changed.setNumber(42);
            var before = snapshot(f.c, null, -1);
            assertThrows(SQLException.class, () -> update(f.store, false, 31, id, changed));
            assertThrows(SQLException.class, () -> add(f.store, false, 31, document(false, 42)));
            assertEquals(before, snapshot(f.c, null, -1));
        }
    }

    @Test
    void supplierCreditSourceCannotBeRetargetedAndDeletingCreditDoesNotRewriteSourceTest()
            throws Exception {
        try (var f = new Fixture()) {
            long source = add(f.store, false, 31, document(false, 42));
            long id = add(f.store, true, 31, document(true, 101));
            f.c.commit();
            var value = find(f.store, true, 31, id).document();
            ((SSSupplierCreditInvoice) value).setCreditingNr(43);
            var before = snapshot(f.c, null, -1);
            assertThrows(SQLException.class, () -> update(f.store, true, 31, id, value));
            assertEquals(before, snapshot(f.c, null, -1));
            var outside = snapshot(f.c, prefix(true), id);
            delete(f.store, true, 31, id);
            assertEquals(outside, snapshot(f.c, prefix(true), id));
            assertEquals(42, find(f.store, false, 31, source).document().getNumber());
        }
    }

    @Test
    void lateInsertFailureRemovesPartialGraphsAndCanRetryWithoutCounterChangesTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row ADD CONSTRAINT supplier_insert_failure"
                                    + " CHECK(unit_price<250)");
                }
                f.c.commit();
                var value = document(credit, 101);
                value.getRows().get(1).setUnitprice(new BigDecimal("300"));
                var before = snapshot(f.c, null, -1);
                assertThrows(SQLException.class, () -> add(f.store, credit, 31, value));
                assertEquals(before, snapshot(f.c, null, -1));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row DROP CONSTRAINT supplier_insert_failure");
                }
                f.c.commit();
                long id = add(f.store, credit, 31, value);
                assertEquals(2, find(f.store, credit, 31, id).document().getRows().size());
                assertEquals(
                        901,
                        credit
                                ? f.store.nextSupplierCreditInvoiceNumber(31)
                                : f.store.nextSupplierInvoiceNumber(31));
            }
    }

    @Test
    void lateReplacementFailureRestoresHeaderRowsDefaultsAndBothVouchersWhileKeepingOuterWorkTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row ADD CONSTRAINT supplier_update_failure"
                                    + " CHECK(unit_price<250)");
                }
                f.c.commit();
                var value = find(f.store, credit, 31, id).document();
                value.setSupplierName("Failed header");
                value.getRows().get(1).setUnitprice(new BigDecimal("300"));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate("UPDATE company SET name='outer' WHERE legacy_id=32");
                }
                var before = snapshot(f.c, null, -1);
                assertThrows(SQLException.class, () -> update(f.store, credit, 31, id, value));
                assertEquals(before, snapshot(f.c, null, -1));
                f.c.rollback();
                assertEquals("Company32", companyName(f.c, 32));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_row DROP CONSTRAINT supplier_update_failure");
                }
                f.c.commit();
                update(f.store, credit, 31, id, value);
                assertEquals(
                        "Failed header",
                        find(f.store, credit, 31, id).document().getSupplierName());
                assertNotNull(find(f.store, credit, 31, id).document().getCorrection());
            }
    }

    @Test
    void lateHeaderDeleteFailureRestoresAllChildrenAndRetriesTest() throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "CREATE TABLE supplier_delete_guard(doc_id BIGINT REFERENCES "
                                    + table(credit)
                                    + "(id))");
                    sql.executeUpdate("INSERT INTO supplier_delete_guard VALUES(" + id + ")");
                }
                f.c.commit();
                var before = snapshot(f.c, null, -1);
                assertThrows(SQLException.class, () -> delete(f.store, credit, 31, id));
                assertEquals(before, snapshot(f.c, null, -1));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate("DELETE FROM supplier_delete_guard");
                }
                f.c.commit();
                delete(f.store, credit, 31, id);
                assertTrue(
                        credit
                                ? f.store.findSupplierCreditInvoice(31, id).isEmpty()
                                : f.store.findSupplierInvoice(31, id).isEmpty());
            }
    }

    @Test
    void unsupportedGraphsNullMapsAndInexactAmountsFailWithoutLegacyFallbackOrWritesTest()
            throws Exception {
        try (var f = new Fixture();
                var ignored = new LegacyGuard()) {
            var before = snapshot(f.c, null, -1);
            for (boolean credit : List.of(false, true)) {
                var map = document(credit, 101);
                map.setDefaultAccounts(null);
                assertThrows(SQLException.class, () -> add(f.store, credit, 31, map));
                var graph = document(credit, 101);
                graph.getCorrection().setCorrects(new SSVoucher(90));
                assertThrows(SQLException.class, () -> add(f.store, credit, 31, graph));
                var price = document(credit, 101);
                price.getRows().getFirst().setUnitprice(new BigDecimal("1e-31"));
                assertThrows(IllegalArgumentException.class, () -> add(f.store, credit, 31, price));
                var rate = document(credit, 101);
                rate.setCurrencyRate(new BigDecimal("1e70"));
                assertThrows(IllegalArgumentException.class, () -> add(f.store, credit, 31, rate));
                var date = document(credit, 101);
                date.setLocalDate(LocalDate.of(10000, 1, 1));
                assertThrows(IllegalArgumentException.class, () -> add(f.store, credit, 31, date));
            }
            class Extra extends SSSupplierInvoice {
                Extra() {
                    super(false);
                }
            }
            assertThrows(SQLException.class, () -> f.store.addSupplierInvoice(31, new Extra()));
            assertEquals(before, snapshot(f.c, null, -1));
        }
    }

    @Test
    void allWriteEntryPointsRequireCallerTransactionsAndAmbiguousNumberPostingRollsBackTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                f.c.commit();
                var value = find(f.store, credit, 31, id).document();
                f.c.commit();
                f.c.setAutoCommit(true);
                var before = snapshot(f.c, null, -1);
                assertThrows(
                        SQLException.class, () -> add(f.store, credit, 31, document(credit, 102)));
                assertThrows(SQLException.class, () -> update(f.store, credit, 31, id, value));
                assertThrows(SQLException.class, () -> delete(f.store, credit, 31, id));
                assertThrows(SQLException.class, () -> mark(f.store, credit, 101, voucher(99)));
                assertEquals(before, snapshot(f.c, null, -1));
                f.c.setAutoCommit(false);
            }
        try (var f = new Fixture()) {
            var empty = SSSupplierInvoice.forPersistence();
            empty.setNumber(101);
            long first = f.store.addSupplierInvoice(31, empty);
            long second = f.store.addSupplierInvoice(32, empty);
            try (var sql = f.c.createStatement()) {
                sql.executeUpdate(
                        "UPDATE supplier_invoice SET company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=31) WHERE id="
                                + second);
            }
            f.c.commit();
            var before = snapshot(f.c, null, -1);
            assertThrows(
                    SQLException.class,
                    () -> f.store.markSupplierInvoiceEntered(31, 101, voucher(99)));
            assertEquals(before, snapshot(f.c, null, -1));
            assertNotNull(f.store.findSupplierInvoice(31, first).orElseThrow());
            assertNotNull(f.store.findSupplierInvoice(31, second).orElseThrow());
        }
    }

    @Test
    void stockProjectionTracksOnlyCommittedSupplierEditsAndDeletionTest() throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "INSERT INTO product(legacy_id,company_id,number,expired,stock_goods)"
                                    + " SELECT 901,id,'STOCK',FALSE,TRUE FROM company WHERE"
                                    + " legacy_id=31");
                }
                f.c.commit();
                var value = document(credit, 101);
                value.setRows(new ArrayList<>(List.of(value.getRows().getFirst())));
                value.getRows().getFirst().setProductNr("STOCK");
                long id = add(f.store, credit, 31, value);
                f.c.commit();
                assertEquals(credit ? -2 : 2, stock(f.c));
                var changed = find(f.store, credit, 31, id).document();
                changed.getRows().getFirst().setQuantity(5);
                update(f.store, credit, 31, id, changed);
                assertEquals(credit ? -5 : 5, stock(f.c));
                f.c.rollback();
                assertEquals(credit ? -2 : 2, stock(f.c));
                update(f.store, credit, 31, id, changed);
                f.c.commit();
                assertEquals(credit ? -5 : 5, stock(f.c));
                delete(f.store, credit, 31, id);
                f.c.commit();
                assertEquals(0, stock(f.c));
            }
    }

    @Test
    void committedSupplierCrudSurvivesTwoRealFileReopensTest(@TempDir Path directory)
            throws Exception {
        for (boolean credit : List.of(false, true)) {
            String url = "jdbc:hsqldb:file:" + directory.resolve(credit ? "credit" : "invoice");
            long id;
            int legacy;
            Map<String, List<String>> before;
            try (var f = new Fixture(url)) {
                id = add(f.store, credit, 31, document(credit, 101));
                f.c.commit();
                var loaded = find(f.store, credit, 31, id);
                legacy = loaded.legacyId();
                loaded.document().setSupplierName("Durable Å");
                update(f.store, credit, 31, id, loaded.document());
                f.c.commit();
                before = snapshot(f.c, null, -1);
            }
            for (int reopen = 0; reopen < 2; reopen++)
                try (var c = DriverManager.getConnection(url, "SA", "")) {
                    c.setAutoCommit(false);
                    var store = new NormalizedSupplierInvoiceStore(c);
                    assertEquals(
                            "Durable Å", find(store, credit, 31, id).document().getSupplierName());
                    assertEquals(legacy, find(store, credit, 31, id).legacyId());
                    assertEquals(before, snapshot(c, null, -1));
                    c.rollback();
                    try (var sql = c.createStatement()) {
                        sql.execute("SHUTDOWN");
                    }
                }
            try (var c = DriverManager.getConnection(url, "SA", "")) {
                c.setAutoCommit(false);
                delete(new NormalizedSupplierInvoiceStore(c), credit, 31, id);
                c.commit();
                try (var sql = c.createStatement()) {
                    sql.execute("SHUTDOWN");
                }
            }
            try (var c = DriverManager.getConnection(url, "SA", "")) {
                c.setAutoCommit(false);
                assertTrue(
                        credit
                                ? new NormalizedSupplierInvoiceStore(c)
                                        .findSupplierCreditInvoice(31, id)
                                        .isEmpty()
                                : new NormalizedSupplierInvoiceStore(c)
                                        .findSupplierInvoice(31, id)
                                        .isEmpty());
                try (var sql = c.createStatement()) {
                    sql.execute("SHUTDOWN");
                }
            }
        }
    }

    @Test
    void v21WidensOnlySupplierTimestampPrecisionAndPreservesExistingV20ValuesTest()
            throws Exception {
        try (var f = new Fixture("jdbc:hsqldb:mem:supplier_v20_" + System.nanoTime(), 20)) {
            for (boolean credit : List.of(false, true)) {
                var value = document(credit, 101);
                for (var voucher : List.of(value.getVoucher(), value.getCorrection()))
                    voucher.getRows()
                            .getFirst()
                            .setLocalEditedDate(
                                    LocalDateTime.of(2026, 11, 15, 10, 11, 12, 123456000));
                add(f.store, credit, 31, value);
                assertEquals(6, precision(f.c, credit));
            }
            f.c.commit();
            var before = snapshot(f.c, null, -1);
            before.remove("BOKFRI_SCHEMA_HISTORY");
            new SchemaMigrationRunner(CLOCK).migrate(f.c, NormalizedSchemaMigrations.load());
            var after = snapshot(f.c, null, -1);
            after.remove("BOKFRI_SCHEMA_HISTORY");
            assertEquals(before, after);
            for (boolean credit : List.of(false, true)) {
                assertEquals(9, precision(f.c, credit));
                var loaded =
                        credit
                                ? f.store.listSupplierCreditInvoices(31).getFirst()
                                : f.store.listSupplierInvoices(31).getFirst();
                loaded.document()
                        .getCorrection()
                        .getRows()
                        .getFirst()
                        .setLocalEditedDate(LocalDateTime.of(2026, 11, 15, 10, 11, 12, 123456789));
                update(f.store, credit, 31, loaded.id(), loaded.document());
                assertEquals(
                        123456789,
                        find(f.store, credit, 31, loaded.id())
                                .document()
                                .getCorrection()
                                .getRows()
                                .getFirst()
                                .getLocalEditedDate()
                                .getNano());
            }
        }
    }

    static int precision(Connection c, boolean credit) throws Exception {
        try (var sql =
                c.prepareStatement(
                        "SELECT datetime_precision FROM information_schema.columns WHERE"
                                + " table_schema='PUBLIC' AND table_name=? AND"
                                + " column_name='EDITED_AT'")) {
            sql.setString(1, prefix(credit) + "_VOUCHER_ROW");
            try (var row = sql.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
        }
    }

    @Test
    void nullHeaderTotalsAndAbsentSnapshotsAreNotMaterializedAsLegacyDefaultsTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture();
                    var ignored = new LegacyGuard()) {
                var value =
                        credit
                                ? SSSupplierCreditInvoice.forPersistence()
                                : SSSupplierInvoice.forPersistence();
                value.setNumber(101);
                long id = add(f.store, credit, 31, value);
                f.c.commit();
                var loaded = find(f.store, credit, 31, id).document();
                assertNull(loaded.getStoredTaxSum());
                assertNull(loaded.getStoredRoundingSum());
                assertNull(loaded.getCurrencyRate());
                assertNull(loaded.getCurrency());
                assertNull(loaded.getPaymentTerm());
                assertNull(loaded.getVoucher());
                assertNull(loaded.getCorrection());
                assertTrue(loaded.getStoredDefaultAccounts().isEmpty());
                assertTrue(loaded.getRows().isEmpty());
                var before = snapshot(f.c, null, -1);
                update(f.store, credit, 31, id, loaded);
                assertEquals(before, snapshot(f.c, null, -1));
                assertEquals(0, BigDecimal.ZERO.compareTo(loaded.getTaxSum()));
                assertNull(find(f.store, credit, 31, id).document().getStoredTaxSum());
            }
    }

    @Test
    void postingPrimitivePreservesManualCorrectionAndRollsBackLateVoucherFailuresTest()
            throws Exception {
        for (boolean credit : List.of(false, true))
            try (var f = new Fixture()) {
                long id = add(f.store, credit, 31, document(credit, 101));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_voucher_row ADD CONSTRAINT supplier_post_failure"
                                    + " CHECK(debit<250)");
                }
                f.c.commit();
                var before = snapshot(f.c, null, -1);
                var bad = voucher(99);
                bad.getRows().getFirst().setDebet(new BigDecimal("300"));
                assertThrows(SQLException.class, () -> mark(f.store, credit, 101, bad));
                assertEquals(before, snapshot(f.c, null, -1));
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(credit)
                                    + "_voucher_row DROP CONSTRAINT supplier_post_failure");
                    sql.executeUpdate(
                            "UPDATE " + table(credit) + " SET bgc_entered=TRUE WHERE id=" + id);
                }
                f.c.commit();
                mark(f.store, credit, 101, voucher(99));
                var saved = find(f.store, credit, 31, id).document();
                assertTrue(saved.isEntered());
                assertTrue(saved.isBGCEntered());
                assertEquals(99, saved.getVoucher().getNumber());
                assertEquals(52, saved.getCorrection().getNumber());
                assertEquals(
                        123456789,
                        saved.getCorrection().getRows().getFirst().getLocalEditedDate().getNano());
                assertTrue(
                        saved.getStoredDefaultAccounts()
                                .containsKey(SSDefaultAccount.SupplierDebt));
                assertNull(saved.getStoredDefaultAccounts().get(SSDefaultAccount.SupplierDebt));
            }
    }

    static SSSupplierInvoice document(boolean credit, Integer number) {
        SSSupplierInvoice value =
                credit
                        ? SSSupplierCreditInvoice.forPersistence()
                        : SSSupplierInvoice.forPersistence();
        value.setNumber(number);
        if (credit) ((SSSupplierCreditInvoice) value).setCreditingNr(42);
        value.setLocalDate(LocalDate.of(2026, 11, 15));
        value.setLocalDueDate(LocalDate.of(2026, 12, 15));
        value.setSupplierNr("SUP-Å");
        value.setSupplierName("Supplier Å");
        value.setReferencenumber("");
        value.setCurrency(new SSCurrency("EUR", "Euro"));
        value.setCurrencyRate(new BigDecimal("10.125"));
        value.setPaymentTerm(new SSPaymentTerm("Net30", "Net30"));
        value.setTaxSum(new BigDecimal("25.125"));
        value.setRoundingSum(new BigDecimal("-0.125"));
        value.setStockInfluencing(true);
        value.getStoredDefaultAccounts().put(SSDefaultAccount.SupplierDebt, null);
        value.getStoredDefaultAccounts().put(SSDefaultAccount.Purchases, 4010);
        var row = new SSSupplierInvoiceRow();
        row.setProductNr("P-Å");
        row.setDescription("Item Å");
        row.setUnitprice(new BigDecimal("100.000000000000000000000000000001"));
        row.setQuantity(2);
        row.setUnit(new SSUnit("st", "Pieces"));
        row.setUnitFreight(new BigDecimal("1.25"));
        row.setAccountNr(4010);
        row.setProjectNr("P1");
        row.setResultUnitNr("U1");
        var nil = new SSSupplierInvoiceRow();
        nil.setDescription(null);
        nil.setQuantity((Integer) null);
        nil.setUnitprice(null);
        nil.setAccountNr(null);
        value.setRows(new ArrayList<>(List.of(row, nil)));
        value.setVoucher(voucher(51));
        value.setCorrection(voucher(52));
        return value;
    }

    static SSVoucher voucher(int number) {
        var v = new SSVoucher(number, LocalDate.of(2026, 11, 15));
        v.setDescription("Voucher Å");
        var row = new SSVoucherRow();
        row.setAccountNr(null);
        row.setDebet(new BigDecimal("12.500000000000000000000000000001"));
        row.setCredit(null);
        row.setProjectNr("P1");
        row.setResultUnitNr("U1");
        row.setLocalEditedDate(LocalDateTime.of(2026, 11, 15, 10, 11, 12, 123456789));
        row.setEditedSignature("");
        row.setCrossed(true);
        row.setAdded(false);
        v.getRows().add(row);
        return v;
    }

    static long add(
            NormalizedSupplierInvoiceStore store,
            boolean credit,
            int company,
            SSSupplierInvoice value)
            throws SQLException {
        return credit
                ? store.addSupplierCreditInvoice(company, (SSSupplierCreditInvoice) value)
                : store.addSupplierInvoice(company, value);
    }

    static NormalizedSupplierInvoiceStore.Stored<? extends SSSupplierInvoice> find(
            NormalizedSupplierInvoiceStore store, boolean credit, int company, long id)
            throws SQLException {
        return credit
                ? store.findSupplierCreditInvoice(company, id).orElseThrow()
                : store.findSupplierInvoice(company, id).orElseThrow();
    }

    static void update(
            NormalizedSupplierInvoiceStore store,
            boolean credit,
            int company,
            long id,
            SSSupplierInvoice value)
            throws SQLException {
        if (credit) store.updateSupplierCreditInvoice(company, id, (SSSupplierCreditInvoice) value);
        else store.updateSupplierInvoice(company, id, value);
    }

    static void delete(NormalizedSupplierInvoiceStore store, boolean credit, int company, long id)
            throws SQLException {
        if (credit) store.deleteSupplierCreditInvoice(company, id);
        else store.deleteSupplierInvoice(company, id);
    }

    static void mark(
            NormalizedSupplierInvoiceStore store, boolean credit, int number, SSVoucher voucher)
            throws SQLException {
        if (credit) store.markSupplierCreditInvoiceEntered(31, number, voucher);
        else store.markSupplierInvoiceEntered(31, number, voucher);
    }

    static String table(boolean credit) {
        return credit ? "supplier_credit_invoice" : "supplier_invoice";
    }

    static String prefix(boolean credit) {
        return table(credit).toUpperCase(java.util.Locale.ROOT);
    }

    static String owner(boolean credit) {
        return credit ? "supplier_credit_invoice_id" : "supplier_invoice_id";
    }

    static Integer stock(Connection c) throws Exception {
        var products = new NormalizedRegisterStore(c).getProducts(31);
        return new NormalizedStockStore(c).read(31, products).getQuantity(products.getFirst());
    }

    static String companyName(Connection c, int company) throws Exception {
        try (var sql = c.createStatement();
                var r = sql.executeQuery("SELECT name FROM company WHERE legacy_id=" + company)) {
            r.next();
            return r.getString(1);
        }
    }

    static void reference(Fixture f, String type, int company, int invoice) throws Exception {
        switch (type) {
            case "payment" -> {
                try (var sql = f.c.createStatement()) {
                    sql.executeUpdate(
                            "INSERT INTO outpayment(legacy_id,company_id,entered) SELECT"
                                    + " 901,id,FALSE FROM company WHERE legacy_id="
                                    + company);
                    sql.executeUpdate(
                            "INSERT INTO outpayment_row(outpayment_id,row_number,invoice_number)"
                                    + " SELECT id,0,"
                                    + invoice
                                    + " FROM outpayment WHERE legacy_id=901");
                }
                f.c.commit();
            }
            case "credit" -> {
                var value = SSSupplierCreditInvoice.forPersistence();
                value.setNumber(901);
                value.setCreditingNr(invoice);
                f.store.addSupplierCreditInvoice(company, value);
                f.c.commit();
            }
            case "order" -> {
                var value = SSPurchaseOrder.forPersistence();
                value.setNumber(901);
                value.setInvoiceNr(invoice);
                new NormalizedPurchaseOrderStore(f.c).addPurchaseOrder(company, value);
                f.c.commit();
            }
            default -> throw new AssertionError(type);
        }
    }

    static Map<String, List<String>> snapshot(Connection c, String prefix, long skipId)
            throws Exception {
        Map<String, List<String>> values = new TreeMap<>();
        try (var tables = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                List<String> rows = new ArrayList<>();
                try (var sql = c.createStatement();
                        var r = sql.executeQuery("SELECT * FROM " + name)) {
                    var m = r.getMetaData();
                    while (r.next()) {
                        boolean own =
                                prefix != null
                                        && (name.equals(prefix)
                                                || List.of(
                                                                "_ROW",
                                                                "_DEFAULT_ACCOUNT",
                                                                "_VOUCHER",
                                                                "_VOUCHER_ROW")
                                                        .stream()
                                                        .anyMatch(s -> name.equals(prefix + s)));
                        if (own && r.getLong(name.equals(prefix) ? "ID" : prefix + "_ID") == skipId)
                            continue;
                        StringBuilder value = new StringBuilder();
                        for (int i = 1; i <= m.getColumnCount(); i++) {
                            String text = r.getString(i);
                            if (text != null
                                    && m.getColumnType(i) == java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                                text = r.getObject(i, java.time.OffsetDateTime.class).toString();
                            value.append(
                                    text == null ? "N;" : "V" + text.length() + ":" + text + ";");
                        }
                        rows.add(value.toString());
                    }
                }
                Collections.sort(rows);
                values.put(name, rows);
            }
        }
        return values;
    }

    static final class Fixture implements AutoCloseable {
        final Connection c;
        final NormalizedSupplierInvoiceStore store;

        Fixture() throws Exception {
            this("jdbc:hsqldb:mem:supplier_crud_" + System.nanoTime());
        }

        Fixture(String url) throws Exception {
            this(url, NormalizedSchemaMigrations.load().size());
        }

        Fixture(String url, int migrationCount) throws Exception {
            c = DriverManager.getConnection(url, "SA", "");
            c.setAutoCommit(false);
            new SchemaMigrationRunner(CLOCK)
                    .migrate(c, NormalizedSchemaMigrations.load().subList(0, migrationCount));
            DataFormatManager.recordVersion(c, 3);
            var writer = new NormalizedAccountingWriter(CLOCK);
            for (int id : List.of(31, 32)) {
                var company = new SSNewCompany();
                company.setId(id);
                company.setName("Company" + id);
                writer.addCompany(c, company);
            }
            try (var sql = c.createStatement()) {
                sql.executeUpdate(
                        "INSERT INTO currency(code,description,exchange_rate)"
                                + " VALUES('EUR','Euro',999)");
                sql.executeUpdate("INSERT INTO unit_definition VALUES('st','Pieces')");
                sql.executeUpdate("INSERT INTO payment_term VALUES('Net30','Net30')");
                sql.executeUpdate(
                        "INSERT INTO project(company_id,number,concluded) SELECT id,'P1',FALSE FROM"
                                + " company");
                sql.executeUpdate(
                        "INSERT INTO result_unit(company_id,number) SELECT id,'U1' FROM company");
                sql.executeUpdate(
                        "DELETE FROM company_auto_increment WHERE counter_name IN"
                                + " ('supplierinvoice','suppliercreditinvoice')");
                for (String name : List.of("supplierinvoice", "suppliercreditinvoice"))
                    sql.executeUpdate(
                            "INSERT INTO company_auto_increment SELECT id,'"
                                    + name
                                    + "',900 FROM company");
            }
            c.commit();
            store = new NormalizedSupplierInvoiceStore(c);
        }

        public void close() throws Exception {
            c.rollback();
            try (var sql = c.createStatement()) {
                sql.execute("SHUTDOWN");
            }
            c.close();
        }
    }

    static final class LegacyGuard implements AutoCloseable {
        final Field field;
        final Object db, previous;

        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
            previous = field.get(db);
            field.set(
                    db,
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, args) -> {
                                throw new AssertionError("Legacy supplier access " + m.getName());
                            }));
        }

        public void close() throws Exception {
            field.set(db, previous);
        }
    }
}
