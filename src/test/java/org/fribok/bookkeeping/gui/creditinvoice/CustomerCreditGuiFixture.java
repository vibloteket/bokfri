package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class CustomerCreditGuiFixture implements AutoCloseable {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    final String url = "jdbc:hsqldb:mem:credit_gui_" + System.nanoTime();
    final Connection anchor;
    final long invoice;
    final Object gate = new Object();

    CustomerCreditGuiFixture() throws Exception {
        anchor = open(); anchor.setAutoCommit(false);
        new SchemaMigrationRunner(CLOCK).migrate(anchor, NormalizedSchemaMigrations.load());
        DataFormatManager.recordVersion(anchor, 3);
        var writer = new NormalizedAccountingWriter(CLOCK);
        SSNewCompany company = new SSNewCompany(); company.setId(71); company.setName("GUI company");
        company.setRoundingOff(true); writer.addCompany(anchor, company);
        SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(11);
        year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
        SSAccountPlan plan = new SSAccountPlan();
        plan.setAccounts(new ArrayList<>(List.of(new SSAccount(1510), new SSAccount(3010))));
        year.setAccountPlan(plan); writer.addAccountingYear(anchor, 71, year);
        SSInvoice source = SSInvoice.forPersistence(); source.setNumber(1); source.setEntered(true);
        source.setLocalDate(LocalDate.of(2026, 10, 1)); source.setTaxFree(true); source.setCurrencyRate(BigDecimal.ONE);
        source.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
        SSSaleRow row = new SSSaleRow(); row.setAccountNr(3010); row.setUnitprice(new BigDecimal("100"));
        row.setQuantity(BigDecimal.ONE); source.getRows().add(row);
        source.setVoucher(new SSVoucher(44, source.getLocalDate()));
        invoice = new NormalizedInvoiceStore(anchor).addInvoice(71, source); anchor.commit();
    }
    Connection open() throws SQLException { return DriverManager.getConnection(url, "SA", ""); }
    CustomerCreditDialogAdapter adapter() {
        return new CustomerCreditDialogAdapter(this::open, gate, 71, 11, invoice, CLOCK);
    }
    int count() throws SQLException { return new NormalizedInvoiceStore(anchor).listCreditInvoices(71).size(); }
    @Override public void close() throws Exception {
        anchor.rollback();
        try (var s = anchor.createStatement()) { s.execute("SHUTDOWN"); }
        anchor.close();
    }
}
