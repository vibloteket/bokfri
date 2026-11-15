package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SupplierJournalGuiFixture implements AutoCloseable {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
    static final LocalDate FROM = LocalDate.of(2026, 1, 1), TO = LocalDate.of(2026, 12, 31);
    final String url = "jdbc:hsqldb:mem:supplier_journal_gui_" + System.nanoTime();
    final Connection anchor;
    final Object gate = new Object();
    final long invoice;

    SupplierJournalGuiFixture() throws Exception {
        anchor = open();
        anchor.setAutoCommit(false);
        new SchemaMigrationRunner(CLOCK).migrate(anchor, NormalizedSchemaMigrations.load());
        DataFormatManager.recordVersion(anchor, 3);
        var writer = new NormalizedAccountingWriter(CLOCK);
        for (int id : List.of(71, 72)) {
            var company = new SSNewCompany();
            company.setId(id);
            company.setName("GUI company " + id);
            writer.addCompany(anchor, company);
            var year = new SSNewAccountingYear();
            year.setId(id == 71 ? 11 : 12);
            year.setLocalFrom(FROM);
            year.setLocalTo(TO);
            var accounts = new SSAccountPlan();
            accounts.setAccounts(
                    new ArrayList<>(
                            List.of(
                                    new SSAccount(2440),
                                    new SSAccount(2640),
                                    new SSAccount(4010))));
            year.setAccountPlan(accounts);
            writer.addAccountingYear(anchor, id, year);
        }
        var original = document(false, 1, "100");
        original.setEntered(true);
        invoice = new NormalizedSupplierInvoiceStore(anchor).addSupplierInvoice(71, original);
        anchor.commit();
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(url, "SA", "");
    }

    SupplierDocumentGuiSession session() {
        return new SupplierDocumentGuiSession(this::open, gate, 71, 11, CLOCK);
    }

    static SSSupplierInvoice document(boolean credit, int number, String amount) {
        var invoice =
                credit
                        ? SSSupplierCreditInvoice.forPersistence()
                        : SSSupplierInvoice.forPersistence();
        invoice.setNumber(number);
        invoice.setSupplierName("Supplier " + number);
        invoice.setLocalDate(LocalDate.of(2026, 10, 1));
        invoice.setTaxSum(BigDecimal.ZERO);
        invoice.setRoundingSum(BigDecimal.ZERO);
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.getDefaultAccounts().put(SSDefaultAccount.SupplierDebt, 2440);
        var row = new SSSupplierInvoiceRow();
        row.setAccountNr(4010);
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal(amount));
        invoice.getRows().add(row);
        if (credit) ((SSSupplierCreditInvoice) invoice).setCreditingNr(1);
        return invoice;
    }

    long add(boolean credit, int number, String amount) throws SQLException {
        var store = new NormalizedSupplierInvoiceStore(anchor);
        var document = document(credit, number, amount);
        long id =
                credit
                        ? store.addSupplierCreditInvoice(71, (SSSupplierCreditInvoice) document)
                        : store.addSupplierInvoice(71, document);
        anchor.commit();
        return id;
    }

    SSSupplierInvoice stored(boolean credit, long id) throws SQLException {
        var store = new NormalizedSupplierInvoiceStore(anchor);
        var document =
                credit
                        ? store.findSupplierCreditInvoice(71, id).orElseThrow().document()
                        : store.findSupplierInvoice(71, id).orElseThrow().document();
        anchor.rollback();
        return document;
    }

    int voucherCount() throws SQLException {
        try (var statement = anchor.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM voucher")) {
            rows.next();
            return rows.getInt(1);
        } finally {
            anchor.rollback();
        }
    }

    Map<String, List<String>> snapshot() throws SQLException {
        Map<String, List<String>> tables = new TreeMap<>();
        try (var metadata =
                anchor.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (metadata.next()) {
                String table = metadata.getString("TABLE_NAME");
                List<String> rows = new ArrayList<>();
                try (var statement = anchor.createStatement();
                        var result = statement.executeQuery("SELECT * FROM " + table)) {
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
        } finally {
            anchor.rollback();
        }
        return tables;
    }

    @FunctionalInterface
    interface Call {
        Object call(Method method, Object[] args) throws Throwable;
    }

    static Connection wrap(Connection connection, Call call) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> call.call(method, args));
    }

    static Object invoke(Connection connection, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    static final class LegacyGuard implements AutoCloseable {
        private final Field field;
        private final Object previous;

        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            previous = field.get(se.swedsoft.bookkeeping.data.system.SSDB.getInstance());
            field.set(
                    se.swedsoft.bookkeeping.data.system.SSDB.getInstance(),
                    wrap(
                            null,
                            (method, args) -> {
                                throw new AssertionError("Legacy JDBC: " + method.getName());
                            }));
        }

        public void close() throws Exception {
            field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), previous);
        }
    }

    @Override
    public void close() throws Exception {
        anchor.rollback();
        try (var statement = anchor.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        anchor.close();
    }
}
