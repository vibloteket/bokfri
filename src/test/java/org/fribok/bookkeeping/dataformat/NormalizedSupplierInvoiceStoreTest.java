package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedSupplierInvoiceStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void clearLegacySingletonSelection() {
        se.swedsoft.bookkeeping.data.system.SSDB stale =
                se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
        stale.setCurrentYear(null);
        stale.setCurrentCompany(null);
    }

    @Test
    void supplierInvoicesAndCreditsRoundTripWithSums() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(31);
            writer.addCompany(connection, company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency (code,description,exchange_rate) "
                        + "VALUES ('SEK','Svensk krona',1)");
                statement.executeUpdate("INSERT INTO unit_definition (name,description) "
                        + "VALUES ('st','Styck')");
                statement.executeUpdate("INSERT INTO payment_term (name,description) "
                        + "VALUES ('30 dagar','Netto 30')");
            }
            connection.commit();
            NormalizedSupplierInvoiceStore store =
                    new NormalizedSupplierInvoiceStore(connection);
            NormalizedRegisterStore registers = new NormalizedRegisterStore(connection);

            SSSupplierInvoice invoice = new SSSupplierInvoice();
            invoice.setNumber(501);
            invoice.setLocalDate(LocalDate.of(2026, 4, 1));
            invoice.setLocalDueDate(LocalDate.of(2026, 5, 1));
            invoice.setSupplierNr("L1");
            invoice.setSupplierName("Leverantör AB");
            invoice.setReferencenumber("REF-9");
            invoice.setCurrency(registers.getCurrencies().get(0));
            invoice.setPaymentTerm(registers.getPaymentTerms().get(0));
            invoice.setCurrencyRate(BigDecimal.ONE);
            invoice.setTaxSum(new BigDecimal("50"));
            invoice.setRoundingSum(BigDecimal.ZERO);
            invoice.getDefaultAccounts().put(SSDefaultAccount.SupplierDebt, 2440);
            SSSupplierInvoiceRow row = new SSSupplierInvoiceRow();
            row.setProductNr("A1");
            row.setDescription("Inköp");
            row.setUnitprice(new BigDecimal("100"));
            row.setQuantity(2);
            row.setUnit(registers.getUnits().get(0));
            row.setUnitFreight(new BigDecimal("10"));
            row.setAccountNr(4010);
            invoice.setRows(List.of(row));
            SSVoucher voucher = new SSVoucher(51);
            voucher.setLocalDate(LocalDate.of(2026, 4, 1));
            voucher.setDescription("Levjournal");
            SSVoucherRow voucherRow = new SSVoucherRow();
            voucherRow.setAccountNr(2440);
            voucherRow.setCredit(new BigDecimal("260"));
            voucher.getRows().add(voucherRow);
            invoice.setVoucher(voucher);
            store.addSupplierInvoice(31, invoice);
            connection.commit();

            List<SSSupplierInvoice> read = store.getSupplierInvoices(31);
            assertThat(read).hasSize(1);
            SSSupplierInvoice restored = read.get(0);
            assertThat(restored.getNumber()).isEqualTo(501);
            assertThat(restored.getSupplierNr()).isEqualTo("L1");
            assertThat(restored.getReferencenumber()).isEqualTo("REF-9");
            assertThat(restored.getCurrency().getName()).isEqualTo("SEK");
            assertThat(restored.getPaymentTerm().getName()).isEqualTo("30 dagar");
            assertThat(restored.getTaxSum()).isEqualByComparingTo("50");
            assertThat(restored.getDefaultAccounts())
                    .containsEntry(SSDefaultAccount.SupplierDebt, 2440);
            assertThat(restored.getRows()).hasSize(1);
            assertThat(restored.getRows().get(0).getUnitFreight())
                    .isEqualByComparingTo("10");
            assertThat(restored.getRows().get(0).getAccountNr()).isEqualTo(4010);
            assertThat(restored.getVoucher().getNumber()).isEqualTo(51);
            assertThat(restored.getVoucher().getRows().get(0).getCredit())
                    .isEqualByComparingTo("260");
            assertThat(store.nextSupplierInvoiceNumber(31)).isEqualTo(502);

            SSSupplierCreditInvoice credit = new SSSupplierCreditInvoice();
            credit.setNumber(601);
            credit.setCreditingNr(501);
            credit.setLocalDate(LocalDate.of(2026, 4, 15));
            credit.setLocalDueDate(LocalDate.of(2026, 4, 15));
            credit.setSupplierNr("L1");
            credit.setSupplierName("Leverantör AB");
            credit.setTaxSum(new BigDecimal("25"));
            credit.setRoundingSum(BigDecimal.ZERO);
            SSSupplierInvoiceRow creditRow = new SSSupplierInvoiceRow();
            creditRow.setDescription("Kredit");
            creditRow.setUnitprice(new BigDecimal("100"));
            creditRow.setQuantity(1);
            creditRow.setAccountNr(4010);
            credit.setRows(List.of(creditRow));
            store.addSupplierCreditInvoice(31, credit);
            connection.commit();

            List<SSSupplierCreditInvoice> credits = store.getSupplierCreditInvoices(31);
            assertThat(credits).hasSize(1);
            assertThat(credits.get(0).getCreditingNr()).isEqualTo(501);
            assertThat(credits.get(0).getRows()).hasSize(1);
            // Credit total: 100 net + 25 tax = 125 against invoice 501.
            assertThat(store.supplierCreditInvoiceSum(31, 501)).isEqualByComparingTo("125");
            assertThat(store.supplierCreditInvoiceSum(31, 9999)).isEqualByComparingTo("0");
            assertThat(store.nextSupplierCreditInvoiceNumber(31)).isEqualTo(602);
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:supplier_invoice_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
