package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.LineAmounts;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ManualLine;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.ProductLink;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.dataformat.NormalizedStockStore;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

class CustomerFinancialProductRowTest {
    @Test
    void bothKindsPersistOnlyExplicitProductUnitAndFinancialFieldsTest() throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                long product = seed(f, 801, "P-Å", "st", true);
                var editor = CustomerFinancialManualRowTest.editor(f, kind, id);
                var loaded = editor.load();
                assertEquals(1, loaded.data().products().size());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> loaded.data().products().clear());
                var before =
                        CustomerFinancialEditAdapterTest.snapshot(
                                f, kind, id, Set.of(), Set.of(), Set.of(), Set.of(20));
                f.anchor.rollback();
                var preview =
                        editor.preview(
                                loaded, patch(loaded, List.of(row(product, "P-Å", "st", 20))));
                assertEquals(0, new BigDecimal("60").compareTo(preview.after()));
                assertEquals(1, editor.load().data().lines().size());
                assertSame(editor.save(preview), editor.save(preview));
                assertEquals(
                        before,
                        CustomerFinancialEditAdapterTest.snapshot(
                                f, kind, id, Set.of(), Set.of(), Set.of(), Set.of(20)));
                f.anchor.rollback();
                var saved = editor.load().data().lines().getLast();
                assertEquals("P-Å", saved.productNumber());
                assertEquals("st", saved.unit());
                assertEquals("Explicit price Å", saved.description());
                assertEquals(3022, saved.account());
                assertEquals("TAXRATE_0", saved.taxCode());
                assertEquals(0, BigDecimal.TEN.compareTo(saved.unitPrice()));
                assertEquals(
                        777,
                        CustomerFinancialAccountingTest.document(f, kind, id)
                                .getStoredVoucher()
                                .getNumber());
            }
    }

    @Test
    void missingForeignAmbiguousExpiredCompoundAndMismatchedProductsAreRefusedTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            long product = seed(f, 801, "GOOD", "st", false);
            long expired = seed(f, 802, "OLD", "st", false);
            long duplicate = seed(f, 803, "DUP", "st", false);
            seed(f, 804, "DUP", null, false);
            long compound = seed(f, 805, "BUNDLE", "st", false);
            var other = new se.swedsoft.bookkeeping.data.SSNewCompany();
            other.setId(72);
            other.setName("Foreign");
            new org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter(
                            CustomerCreditGuiFixture.CLOCK)
                    .addCompany(f.anchor, other);
            long foreign = seedCompany(f, 806, "FOREIGN", "st", false, 72);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE product SET expired=TRUE WHERE id=" + expired);
                sql.executeUpdate(
                        "INSERT INTO"
                            + " product_component(product_id,row_number,component_product_number,quantity)"
                            + " VALUES("
                                + compound
                                + ",0,'GOOD',1)");
            }
            f.anchor.commit();
            var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            for (var link :
                    List.of(
                            new ProductLink(99999, "MISSING", null),
                            new ProductLink(foreign, "FOREIGN", "st"),
                            new ProductLink(expired, "OLD", "st"),
                            new ProductLink(duplicate, "DUP", "st"),
                            new ProductLink(compound, "BUNDLE", "st"),
                            new ProductLink(product, "RENAMED", "st"),
                            new ProductLink(product, "GOOD", null)))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> editor.preview(loaded, patch(loaded, List.of(row(link, 20)))));
            assertEquals(1, editor.load().data().lines().size());
        }
    }

    @Test
    void nullUnitIsAnExactSnapshotAndManualRowsRemainUnlinkedTest() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            long product = seed(f, 801, "NO-UNIT", null, false);
            var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            editor.save(
                    editor.preview(
                            loaded,
                            patch(
                                    loaded,
                                    List.of(
                                            row(product, "NO-UNIT", null, 20),
                                            CustomerFinancialManualRowTest.manual(21)))));
            var rows = editor.load().data().lines();
            assertEquals("NO-UNIT", rows.get(1).productNumber());
            assertNull(rows.get(1).unit());
            assertNull(rows.get(2).productNumber());
            assertNull(rows.get(2).unit());
        }
    }

    @Test
    void productIdentityUnitAvailabilityAndStockChangesInvalidatePreviewTest() throws Exception {
        for (String change :
                List.of(
                        "number='RENAMED'",
                        "unit_name=NULL",
                        "expired=TRUE",
                        "stock_goods=FALSE",
                        "description='Changed'"))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                long product = seed(f, 801, "GOOD", "st", true);
                var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
                var loaded = editor.load();
                var preview =
                        editor.preview(
                                loaded, patch(loaded, List.of(row(product, "GOOD", "st", 20))));
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate("UPDATE product SET " + change + " WHERE id=" + product);
                }
                f.anchor.commit();
                assertThrows(IllegalArgumentException.class, () -> editor.save(preview));
                assertEquals(1, editor.load().data().lines().size());
            }
    }

    @Test
    void stockQuantityAndBothDirectionsFollowStoredDocumentFlagTest() throws Exception {
        for (var kind : Kind.values())
            for (boolean stock : List.of(false, true))
                try (var f = new CustomerCreditGuiFixture()) {
                    long id = target(f, kind);
                    long product = seed(f, 801, "STOCK", "st", true);
                    try (var sql = f.anchor.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE "
                                        + table(kind)
                                        + " SET stock_influencing="
                                        + stock
                                        + " WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var editor = CustomerFinancialManualRowTest.editor(f, kind, id);
                    var loaded = editor.load();
                    assertEquals(stock, loaded.data().stockInfluencing());
                    var fractional =
                            new ManualLine(
                                    20,
                                    "Stock",
                                    new BigDecimal("1.5"),
                                    BigDecimal.TEN,
                                    null,
                                    3022,
                                    "TAXRATE_0",
                                    "P-B",
                                    "U-1",
                                    new ProductLink(product, "STOCK", "st"));
                    if (stock)
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> editor.preview(loaded, patch(loaded, List.of(fractional))));
                    else
                        assertEquals(
                                0,
                                new BigDecimal("55")
                                        .compareTo(
                                                editor.preview(
                                                                loaded,
                                                                patch(loaded, List.of(fractional)))
                                                        .after()));
                    editor.save(
                            editor.preview(
                                    loaded,
                                    patch(loaded, List.of(row(product, "STOCK", "st", 20)))));
                    var stockProduct =
                            new NormalizedRegisterStore(f.anchor).getProducts(71).getFirst();
                    assertEquals(
                            stock ? (kind == Kind.INVOICE ? -2 : 2) : 0,
                            new NormalizedStockStore(f.anchor)
                                    .read(71, List.of(stockProduct))
                                    .getQuantity(stockProduct));
                }
    }

    @Test
    void partialProductInsertAndRemovalRollbackTogetherThenRetryTest() throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = CustomerFinancialRowRemovalTest.target(f, kind);
                long product = seed(f, 801, "GOOD", "st", false);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE "
                                    + table(kind)
                                    + "_row ADD CONSTRAINT product_failure CHECK(unit_price<250)");
                }
                f.anchor.commit();
                var editor = CustomerFinancialManualRowTest.editor(f, kind, id);
                var loaded = editor.load();
                var bad =
                        new ManualLine(
                                21,
                                "Second",
                                BigDecimal.ONE,
                                new BigDecimal("300"),
                                null,
                                3022,
                                "TAXRATE_0",
                                "P-B",
                                "U-1",
                                new ProductLink(product, "GOOD", "st"));
                var values =
                        new FinancialValues(
                                loaded.data().values().date(),
                                loaded.data().values().dueDate(),
                                List.of(new LineAmounts(19, BigDecimal.ONE, new BigDecimal("45"))),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of(7),
                                List.of(row(product, "GOOD", "st", 20), bad));
                var preview = editor.preview(loaded, values);
                var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
                f.anchor.rollback();
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertFalse(editor.isBlocked());
                assertEquals(
                        before, CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
                f.anchor.rollback();
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "ALTER TABLE " + table(kind) + "_row DROP CONSTRAINT product_failure");
                }
                f.anchor.commit();
                assertEquals(0, new BigDecimal("365").compareTo(editor.save(preview).total()));
                assertEquals(
                        List.of(19, 20, 21),
                        editor.load().data().lines().stream().map(l -> l.rowNumber()).toList());
            }
    }

    @Test
    void unknownProductCommitBlocksRetryRegardlessOfDurabilityTest() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, Kind.INVOICE);
                long product = seed(f, 801, "GOOD", "st", false);
                var editor =
                        new CustomerFinancialEditAdapter(
                                () -> {
                                    var c = f.open();
                                    return (Connection)
                                            Proxy.newProxyInstance(
                                                    Connection.class.getClassLoader(),
                                                    new Class<?>[] {Connection.class},
                                                    (p, m, a) -> {
                                                        if (m.getName().equals("commit")) {
                                                            if (durable) c.commit();
                                                            throw new SQLException(
                                                                    "Unknown product insert");
                                                        }
                                                        try {
                                                            return m.invoke(c, a);
                                                        } catch (InvocationTargetException e) {
                                                            throw e.getCause();
                                                        }
                                                    });
                                },
                                f.gate,
                                71,
                                11,
                                Kind.INVOICE,
                                id,
                                CustomerCreditGuiFixture.CLOCK);
                var loaded = editor.load();
                var preview =
                        editor.preview(
                                loaded, patch(loaded, List.of(row(product, "GOOD", "st", 20))));
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertTrue(editor.isBlocked());
                assertThrows(SQLException.class, () -> editor.save(preview));
                assertEquals(
                        durable ? 2 : 1,
                        CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id)
                                .load()
                                .data()
                                .lines()
                                .size());
            }
    }

    @Test
    void separateProductJournalsPreserveExplicitAccountsDimensionsAndDraftVoucherTest()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture()) {
                long id = target(f, kind);
                long product = seed(f, 801, "GOOD", "st", false);
                var editor = CustomerFinancialManualRowTest.editor(f, kind, id);
                var loaded = editor.load();
                editor.save(
                        editor.preview(
                                loaded, patch(loaded, List.of(row(product, "GOOD", "st", 20)))));
                assertEquals(
                        777,
                        CustomerFinancialAccountingTest.document(f, kind, id)
                                .getStoredVoucher()
                                .getNumber());
                f.anchor.rollback();
                var journal =
                        new CustomerJournalAdapter(
                                f::open,
                                f.gate,
                                71,
                                11,
                                kind == Kind.INVOICE
                                        ? CustomerJournalAdapter.Kind.INVOICE
                                        : CustomerJournalAdapter.Kind.CREDIT,
                                CustomerCreditGuiFixture.CLOCK);
                var preview =
                        journal.preview(
                                java.time.LocalDate.of(2026, 1, 1),
                                java.time.LocalDate.of(2026, 12, 31));
                assertEquals(0, new BigDecimal("60").compareTo(preview.summary().debit()));
                assertEquals(0, new BigDecimal("60").compareTo(preview.summary().credit()));
                assertTrue(
                        preview.summary().rows().stream()
                                .anyMatch(
                                        r ->
                                                Integer.valueOf(3022).equals(r.account())
                                                        && "P-B".equals(r.project())
                                                        && "U-1".equals(r.resultUnit())));
                journal.post(preview);
                assertThrows(SQLException.class, editor::load);
            }
    }

    @Test
    void directStoreRefusesForeignOrChangedProductLinksWithoutLosingOuterWorkTest()
            throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            long product = seed(f, 801, "GOOD", "st", false);
            var other = new se.swedsoft.bookkeeping.data.SSNewCompany();
            other.setId(72);
            other.setName("Foreign");
            new org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter(
                            CustomerCreditGuiFixture.CLOCK)
                    .addCompany(f.anchor, other);
            long foreign = seedCompany(f, 802, "FOREIGN", "st", false, 72);
            var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate("UPDATE customer_invoice SET ocr_number='outer' WHERE id=" + id);
            }
            var before = CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of());
            for (var link :
                    List.of(
                            new ProductLink(foreign, "FOREIGN", "st"),
                            new ProductLink(99999, "MISSING", null),
                            new ProductLink(product, "GOOD", null),
                            new ProductLink(product, "OTHER", "st"))) {
                var values = patch(loaded, List.of(row(product, "GOOD", "st", 20), row(link, 21)));
                assertThrows(
                        SQLException.class,
                        () ->
                                new org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore(
                                                f.anchor)
                                        .updateInvoiceFinancialValues(71, id, values));
                assertEquals(
                        before, CustomerFinancialEditAdapterTest.snapshot(f, null, -1, Set.of()));
            }
            f.anchor.rollback();
        }
    }

    @Test
    void
            persistedStockRowsRejectNewFractionalOrOutOfRangeQuantitiesButPreserveHistoricalValuesTest()
                    throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            long id = target(f, Kind.INVOICE);
            long product = seed(f, 801, "STOCK", "st", true);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE customer_invoice SET stock_influencing=TRUE WHERE id=" + id);
            }
            f.anchor.commit();
            var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
            var loaded = editor.load();
            editor.save(
                    editor.preview(
                            loaded, patch(loaded, List.of(row(product, "STOCK", "st", 20)))));
            var fresh = editor.load();
            for (var quantity : List.of(new BigDecimal("1.5"), new BigDecimal("2147483648"))) {
                var amounts =
                        fresh.data().values().rows().stream()
                                .map(
                                        r ->
                                                r.rowNumber() == 20
                                                        ? new LineAmounts(
                                                                20, quantity, r.unitPrice())
                                                        : r)
                                .toList();
                var values =
                        new FinancialValues(
                                fresh.data().values().date(),
                                fresh.data().values().dueDate(),
                                amounts);
                assertThrows(IllegalArgumentException.class, () -> editor.preview(fresh, values));
                var newRow =
                        new ManualLine(
                                21,
                                "Stock",
                                quantity,
                                BigDecimal.TEN,
                                null,
                                3022,
                                "TAXRATE_0",
                                "P-B",
                                "U-1",
                                new ProductLink(product, "STOCK", "st"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> editor.preview(fresh, patch(fresh, List.of(newRow))));
            }
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "UPDATE customer_invoice_row SET quantity=1.5 WHERE invoice_id="
                                + id
                                + " AND row_number=20");
            }
            f.anchor.commit();
            var historical = editor.load();
            assertEquals(
                    0,
                    editor.preview(historical, historical.data().values())
                            .after()
                            .compareTo(new BigDecimal("55")));
        }
    }

    static long target(CustomerCreditGuiFixture f, Kind kind) throws Exception {
        return CustomerFinancialManualRowTest.target(f, kind);
    }

    static FinancialValues patch(
            CustomerFinancialEditAdapter.Loaded loaded, List<ManualLine> rows) {
        return CustomerFinancialManualRowTest.patch(loaded, rows);
    }

    static ManualLine row(long product, String number, String unit, int key) {
        return row(new ProductLink(product, number, unit), key);
    }

    static ManualLine row(ProductLink link, int key) {
        return new ManualLine(
                key,
                "Explicit price Å",
                new BigDecimal("2"),
                BigDecimal.TEN,
                null,
                3022,
                "TAXRATE_0",
                "P-B",
                "U-1",
                link);
    }

    static long seed(
            CustomerCreditGuiFixture f, int legacy, String number, String unit, boolean stock)
            throws Exception {
        return seedCompany(f, legacy, number, unit, stock, 71);
    }

    static long seedCompany(
            CustomerCreditGuiFixture f,
            int legacy,
            String number,
            String unit,
            boolean stock,
            int company)
            throws Exception {
        try (var sql = f.anchor.createStatement()) {
            sql.executeUpdate(
                    "MERGE INTO unit_definition USING (VALUES('st','Pieces')) AS"
                            + " v(name,description) ON unit_definition.name=v.name WHEN NOT MATCHED"
                            + " THEN INSERT(name,description) VALUES(v.name,v.description)");
        }
        try (var sql =
                f.anchor.prepareStatement(
                        "INSERT INTO"
                            + " product(legacy_id,company_id,number,description,selling_price,tax_code,expired,stock_goods,unit_name)"
                            + " SELECT ?,id,?,'Catalogue description',999,'TAXRATE_3',FALSE,?,?"
                            + " FROM company WHERE legacy_id=?")) {
            sql.setInt(1, legacy);
            sql.setString(2, number);
            sql.setBoolean(3, stock);
            sql.setString(4, unit);
            sql.setInt(5, company);
            sql.executeUpdate();
        }
        f.anchor.commit();
        try (var sql = f.anchor.prepareStatement("SELECT id FROM product WHERE legacy_id=?")) {
            sql.setInt(1, legacy);
            try (var r = sql.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "customer_invoice" : "customer_credit_invoice";
    }
}
