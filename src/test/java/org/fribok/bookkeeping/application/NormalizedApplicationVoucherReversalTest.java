package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class NormalizedApplicationVoucherReversalTest {
    static final LocalDate DATE = LocalDate.of(2026, 6, 16);

    static long source(Fixture f, NormalizedApplicationOwner owner) throws Exception {
        NormalizedApplicationVoucherDimensionsTest.dimensions(f);
        var lease = owner.select(71, 11);
        lease.vouchers()
                .post(lease.vouchers().preview(NormalizedApplicationVoucherDimensionsTest.draft()));
        return lease.voucherRegister().page(null).headers().get(0).id();
    }

    static void verify(Fixture f, long source, int count) throws Exception {
        try (var owner = f.owner()) {
            var reader = owner.select(71, 11).voucherRegister();
            assertEquals(count, reader.page(null).headers().size());
            var original = reader.detail(source);
            if (count == 1) {
                assertNull(original.header().correctedById());
                return;
            }
            var reversed = reader.detail(original.header().correctedById());
            assertEquals(source, reversed.header().correctsId());
            assertNull(original.header().correctsId());
            assertNull(reversed.header().correctedById());
            assertEquals(original.rows().size(), reversed.rows().size());
            for (int i = 0; i < original.rows().size(); i++) {
                var a = original.rows().get(i);
                var b = reversed.rows().get(i);
                assertEquals(a.accountId(), b.accountId());
                assertEquals(a.project(), b.project());
                assertEquals(a.unit(), b.unit());
                if (a.debit() == null) {
                    assertNull(b.credit());
                    assertEquals(0, a.credit().compareTo(b.debit()));
                } else {
                    assertNull(b.debit());
                    assertEquals(0, a.debit().compareTo(b.credit()));
                }
                assertFalse(b.crossed());
                assertFalse(b.added());
                assertNull(b.editedAt());
                assertNull(b.signature());
            }
        }
    }

    @Test
    void exactReversalPreviewIsReadOnlyAndAtomicLinksPreserveOriginalRowsAndUnrelatedSql()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            long id = source(f, owner);
            var lease = owner.select(71, 11);
            var commands = lease.vouchers();
            var original = lease.voucherRegister().detail(id);
            var before = f.snapshot();
            var token = commands.previewReversal(id, DATE, "Explicit reversal");
            assertEquals(before, f.snapshot());
            assertEquals(id, token.source().id());
            assertEquals(1, token.source().number());
            assertEquals(new BigDecimal("125.50"), token.total().setScale(2));
            assertEquals(2, token.number());
            assertEquals("P-common", token.draft().rows().get(0).project());
            assertThrows(UnsupportedOperationException.class, () -> token.source().rows().clear());
            assertEquals(2, commands.postReversal(token));
            assertTrue(lease.isActive());
            assertEquals(original.rows(), lease.voucherRegister().detail(id).rows());
            var after = f.snapshot();
            var removed = new ArrayList<>(before);
            removed.removeAll(after);
            var added = new ArrayList<>(after);
            added.removeAll(before);
            assertEquals(1, removed.size());
            assertTrue(removed.get(0).startsWith("VOUCHER|"));
            assertEquals(4, added.size());
            assertEquals(2, added.stream().filter(s -> s.startsWith("VOUCHER_ROW|")).count());
            assertThrows(SQLException.class, () -> commands.postReversal(token));
            verify(f, id, 2);
        }
    }

    @Test
    void missingForeignSourcesDateBeforeSourceAndInvalidExplicitInputsDoNotWrite()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            long id = source(f, owner),
                    foreign = NormalizedApplicationVoucherRegisterTest.insert(f, 12, 1, "foreign");
            var commands = owner.select(71, 11).vouchers();
            var before = f.snapshot();
            assertThrows(SQLException.class, () -> commands.previewReversal(foreign, DATE, "x"));
            assertThrows(
                    SQLException.class, () -> commands.previewReversal(Long.MAX_VALUE, DATE, "x"));
            for (var date : List.of(LocalDate.of(2026, 6, 14), LocalDate.of(2027, 1, 1)))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> commands.previewReversal(id, date, "x"));
            assertThrows(
                    IllegalArgumentException.class, () -> commands.previewReversal(id, null, "x"));
            assertThrows(
                    IllegalArgumentException.class, () -> commands.previewReversal(id, DATE, " "));
            assertThrows(
                    IllegalArgumentException.class, () -> commands.previewReversal(id, DATE, null));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    @Test
    void flaggedEditedUnknownDatedUnbalancedNonCentAndInactiveSourcesFailClosed() throws Exception {
        for (String sql :
                List.of(
                        "UPDATE voucher SET voucher_date=NULL",
                        "UPDATE voucher_row SET crossed=TRUE",
                        "UPDATE voucher_row SET added=TRUE",
                        "UPDATE voucher_row SET edited_signature=''",
                        "UPDATE voucher_row SET edited_at=CURRENT_TIMESTAMP",
                        "UPDATE voucher_row SET debit=1.001 WHERE debit IS NOT NULL",
                        "UPDATE voucher_row SET debit=1 WHERE debit IS NOT NULL",
                        "UPDATE voucher_row SET account_id=NULL",
                        "UPDATE account SET active=FALSE WHERE number=4010",
                        "DELETE FROM voucher_row")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                long id = source(f, owner);
                var commands = owner.select(71, 11).vouchers();
                try (var c = f.open()) {
                    c.createStatement().executeUpdate(sql);
                }
                var before = f.snapshot();
                var failure =
                        assertThrows(
                                Exception.class, () -> commands.previewReversal(id, DATE, "x"));
                if (sql.contains("SET debit=")
                        || sql.contains("active=FALSE")
                        || sql.startsWith("DELETE")) {
                    assertInstanceOf(IllegalArgumentException.class, failure, sql);
                } else {
                    assertInstanceOf(SQLException.class, failure, sql);
                }
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
        }
    }

    @Test
    void alreadyCorrectedCorrectionSourcesAndOneSidedInboundGraphsCannotBeReversedAgain()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            long id = source(f, owner);
            var commands = owner.select(71, 11).vouchers();
            commands.postReversal(commands.previewReversal(id, DATE, "reverse"));
            long created =
                    owner.select(71, 11).voucherRegister().detail(id).header().correctedById();
            var fresh = owner.select(71, 11).vouchers();
            var before = f.snapshot();
            assertThrows(SQLException.class, () -> fresh.previewReversal(id, DATE, "again"));
            assertThrows(SQLException.class, () -> fresh.previewReversal(created, DATE, "chain"));
            assertEquals(before, f.snapshot());
        }
        try (var f = new Fixture();
                var owner = f.owner()) {
            long id = source(f, owner),
                    other = NormalizedApplicationVoucherRegisterTest.insert(f, 11, 99, "one-sided");
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE voucher SET corrects_voucher_id="
                                        + id
                                        + " WHERE id="
                                        + other);
            }
            var commands = owner.select(71, 11).vouchers();
            assertThrows(SQLException.class, () -> commands.previewReversal(id, DATE, "x"));
        }
    }

    @Test
    void sourceRowsHeaderCataloguesNumberingAndNewReferencesInvalidateSingleUsePreview()
            throws Exception {
        for (String sql :
                List.of(
                        "UPDATE voucher SET description='changed' WHERE number=1",
                        "UPDATE voucher_row SET debit=2 WHERE debit IS NOT NULL",
                        "UPDATE voucher_row SET row_number=row_number+10",
                        "UPDATE project SET name='changed' WHERE number='P-common'",
                        "UPDATE result_unit SET description='changed' WHERE number='U-common'",
                        "UPDATE voucher SET number=4 WHERE number=1")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                long id = source(f, owner);
                var commands = owner.select(71, 11).vouchers();
                var token = commands.previewReversal(id, DATE, "x");
                try (var c = f.open()) {
                    c.createStatement().executeUpdate(sql);
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.postReversal(token));
                assertEquals(before, f.snapshot());
                assertThrows(SQLException.class, () -> commands.postReversal(token));
            }
        }
        try (var f = new Fixture();
                var owner = f.owner()) {
            long id = source(f, owner);
            var commands = owner.select(71, 11).vouchers();
            var token = commands.previewReversal(id, DATE, "x");
            document(f, "customer_invoice", 71, 1);
            var before = f.snapshot();
            assertThrows(SQLException.class, () -> commands.postReversal(token));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void everyDocumentSnapshotFamilyBlocksConservativelyButForeignCompaniesDoNot()
            throws Exception {
        var families =
                List.of(
                        "customer_invoice",
                        "customer_credit_invoice",
                        "supplier_invoice",
                        "supplier_credit_invoice",
                        "inpayment",
                        "outpayment",
                        "periodic_invoice");
        for (var family : families) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                long id = source(f, owner);
                var commands = owner.select(71, 11).vouchers();
                document(f, family, 72, 1);
                assertDoesNotThrow(() -> commands.previewReversal(id, DATE, "foreign irrelevant"));
                document(f, family, 71, 1);
                var before = f.snapshot();
                assertThrows(
                        SQLException.class, () -> commands.previewReversal(id, DATE, "linked"));
                assertEquals(before, f.snapshot());
            }
        }
        try (var f = new Fixture();
                var c = f.open();
                var tables =
                        c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            Set<String> actual = new TreeSet<>();
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (name.endsWith("_voucher")) actual.add(name.replaceFirst("_voucher$", ""));
            }
            assertEquals(
                    new TreeSet<>(families),
                    actual,
                    "New snapshot families need a reference-guard audit");
        }
    }

    @Test
    void lateRowsAndFinalLinkFailuresRollbackEverythingAndFreshPreviewCanRetry() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            long id = source(f, owner);
            var commands = owner.select(71, 11).vouchers();
            var token = commands.previewReversal(id, DATE, "x");
            var before = f.snapshot();
            f.failure = "voucher-row";
            assertThrows(SQLException.class, () -> commands.postReversal(token));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertEquals(2, commands.postReversal(commands.previewReversal(id, DATE, "retry")));
            verify(f, id, 2);
        }
        try (var f = new Fixture();
                var setup = f.owner()) {
            long id = source(f, setup);
            var fail = new AtomicBoolean(true);
            try (var owner = linkFailureOwner(f, fail)) {
                var commands = owner.select(71, 11).vouchers();
                var token = commands.previewReversal(id, DATE, "x");
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> commands.postReversal(token));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
                fail.set(false);
                commands.postReversal(commands.previewReversal(id, DATE, "retry"));
                verify(f, id, 2);
            }
        }
    }

    @Test
    void uncertainCommitBlocksAllFamiliesAndKnownCommittedCloseFailureIsSuccess() throws Exception {
        for (String mode : List.of("before", "after", "runtime", "close")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                long id = source(f, owner);
                var lease = owner.select(71, 11);
                var commands = lease.vouchers();
                var token = commands.previewReversal(id, DATE, "x");
                f.failure = mode;
                if (mode.equals("close")) {
                    assertEquals(2, commands.postReversal(token));
                    assertFalse(owner.isBlocked());
                } else {
                    if (mode.equals("runtime")) {
                        assertThrows(
                                IllegalStateException.class, () -> commands.postReversal(token));
                    } else {
                        assertThrows(SQLException.class, () -> commands.postReversal(token));
                    }
                    assertTrue(owner.isBlocked());
                    int opens = f.opens.get();
                    assertThrows(
                            SQLException.class, () -> commands.previewReversal(id, DATE, "retry"));
                    assertThrows(SQLException.class, () -> lease.voucherRegister().page(null));
                    assertThrows(SQLException.class, owner::companies);
                    assertEquals(opens, f.opens.get());
                }
                verify(f, id, mode.equals("after") || mode.equals("close") ? 2 : 1);
            }
        }
    }

    @Test
    void foreignAndReplacedTokensRefuseBeforeSqlAndUnrelatedOldJournalEditsDoNotInvalidate()
            throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var other = f.owner()) {
            long id = source(f, owner);
            long old = NormalizedApplicationVoucherRegisterTest.insert(f, 11, 0, "unrelated");
            var commands = owner.select(71, 11).vouchers();
            var token = commands.previewReversal(id, DATE, "x");
            var foreign = other.select(72, 12).vouchers();
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.postReversal(token));
            assertEquals(opens, f.opens.get());
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate("UPDATE voucher SET description='changed' WHERE id=" + old);
            }
            assertEquals(2, commands.postReversal(token));
            var replacement = owner.select(71, 11).vouchers();
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> commands.postReversal(token));
            assertEquals(opens, f.opens.get());
            owner.close();
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> replacement.previewReversal(id, DATE, "x"));
            assertEquals(opens, f.opens.get());
        }
    }

    static void document(Fixture f, String family, int company, int number) throws Exception {
        try (var c = f.open()) {
            long companyId;
            try (var q = c.prepareStatement("SELECT id FROM company WHERE legacy_id=?")) {
                q.setInt(1, company);
                try (var r = q.executeQuery()) {
                    assertTrue(r.next());
                    companyId = r.getLong(1);
                }
            }
            long parent = minimal(c, family, Map.of("COMPANY_ID", companyId, "LEGACY_ID", company));
            if (family.equals("periodic_invoice"))
                minimal(
                        c,
                        "periodic_invoice_snapshot",
                        Map.of("PERIODIC_INVOICE_ID", parent, "INVOICE_INDEX", 0, "PRESENT", true));
            String key =
                    switch (family) {
                        case "customer_invoice" -> "INVOICE_ID";
                        case "customer_credit_invoice" -> "CREDIT_INVOICE_ID";
                        default -> family.toUpperCase(Locale.ROOT) + "_ID";
                    };
            minimal(
                    c,
                    family + "_voucher",
                    Map.of(
                            key,
                            parent,
                            "NUMBER",
                            number,
                            "VOUCHER_KIND",
                            "main",
                            "INVOICE_INDEX",
                            0));
        }
    }

    static long minimal(Connection c, String table, Map<String, Object> overrides)
            throws Exception {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        try (var meta =
                c.getMetaData().getColumns(null, "PUBLIC", table.toUpperCase(Locale.ROOT), "%")) {
            while (meta.next()) {
                String column = meta.getString("COLUMN_NAME");
                if ("YES".equals(meta.getString("IS_AUTOINCREMENT"))) continue;
                if (overrides.containsKey(column)) {
                    columns.add(column);
                    values.add(overrides.get(column));
                } else if (meta.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls
                        && meta.getString("COLUMN_DEF") == null) {
                    columns.add(column);
                    values.add(
                            switch (meta.getInt("DATA_TYPE")) {
                                case Types.BOOLEAN, Types.BIT -> false;
                                case Types.INTEGER,
                                        Types.BIGINT,
                                        Types.SMALLINT,
                                        Types.NUMERIC,
                                        Types.DECIMAL ->
                                        0;
                                case Types.VARCHAR -> "";
                                default -> throw new AssertionError(column);
                            });
                }
            }
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO "
                                + table
                                + "("
                                + String.join(",", columns)
                                + ") VALUES("
                                + String.join(",", Collections.nCopies(columns.size(), "?"))
                                + ")",
                        Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.size(); i++) q.setObject(i + 1, values.get(i));
            q.executeUpdate();
            try (var rows = q.getGeneratedKeys()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    static NormalizedApplicationOwner linkFailureOwner(Fixture f, AtomicBoolean fail) {
        return new NormalizedApplicationOwner(
                () -> {
                    var c = f.open();
                    return (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (p, m, a) -> {
                                        try {
                                            var value = m.invoke(c, a);
                                            if (m.getName().equals("prepareStatement")
                                                    && ((String) a[0])
                                                            .startsWith(
                                                                    "UPDATE voucher SET"
                                                                        + " corrected_by_voucher_id")) {
                                                var statement = (PreparedStatement) value;
                                                return Proxy.newProxyInstance(
                                                        PreparedStatement.class.getClassLoader(),
                                                        new Class<?>[] {PreparedStatement.class},
                                                        (sp, sm, sa) -> {
                                                            try {
                                                                var result =
                                                                        sm.invoke(statement, sa);
                                                                if (sm.getName()
                                                                                .equals(
                                                                                        "executeUpdate")
                                                                        && fail.get())
                                                                    throw new SQLException(
                                                                            "Late final correction"
                                                                                + " link failure");
                                                                return result;
                                                            } catch (InvocationTargetException e) {
                                                                throw e.getCause();
                                                            }
                                                        });
                                            }
                                            return value;
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                },
                Clock.systemUTC());
    }
}
