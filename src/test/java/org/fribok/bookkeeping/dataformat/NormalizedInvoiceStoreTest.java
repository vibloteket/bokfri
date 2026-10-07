package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;

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

class NormalizedInvoiceStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void clearLegacySingletonSelection() {
        se.swedsoft.bookkeeping.data.system.SSDB stale =
                se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
        stale.setCurrentYear(null);
        stale.setCurrentCompany(null);
    }

    @Test
    void invoicesRoundTripWithChildrenAndVoucherSnapshot() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(11);
            writer.addCompany(connection, company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency (code,description,exchange_rate) "
                        + "VALUES ('SEK','Svensk krona',1)");
                statement.executeUpdate("INSERT INTO unit_definition (name,description) "
                        + "VALUES ('st','Styck')");
                statement.executeUpdate("INSERT INTO payment_term (name,description) "
                        + "VALUES ('30 dagar','Netto 30')");
                statement.executeUpdate("INSERT INTO project (company_id,number,name,concluded) "
                        + "SELECT id,'P1','Projekt ett',false FROM company WHERE legacy_id=11");
            }
            connection.commit();
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(connection);
            NormalizedRegisterStore registers = new NormalizedRegisterStore(connection);

            SSInvoice invoice = new SSInvoice();
            invoice.setNumber(1001);
            invoice.setLocalDate(LocalDate.of(2026, 1, 15));
            invoice.setLocalDueDate(LocalDate.of(2026, 2, 14));
            invoice.setCustomerNr("K1");
            invoice.setCustomerName("Kund AB");
            invoice.setOurContactPerson("Vi");
            invoice.setYourContactPerson("De");
            invoice.setCurrency(registers.getCurrencies().get(0));
            invoice.setPaymentTerm(registers.getPaymentTerms().get(0));
            invoice.setTaxRate1(new BigDecimal("25"));
            invoice.setTaxRate2(new BigDecimal("12"));
            invoice.setTaxRate3(new BigDecimal("6"));
            invoice.setType(SSInvoiceType.NORMAL);
            invoice.setCurrencyRate(BigDecimal.ONE);
            invoice.setYourOrderNumber("PO-55");
            invoice.setOCRNumber("100155");
            invoice.setText("Fakturatext");
            invoice.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
            SSSaleRow row = new SSSaleRow();
            row.setProductNr("A1");
            row.setDescription("Artikel");
            row.setUnitprice(new BigDecimal("100"));
            row.setQuantity(2);
            row.setUnit(registers.getUnits().get(0));
            row.setTaxCode(SSTaxCode.TAXRATE_1);
            row.setAccountNr(3010);
            row.setProjectNr("P1");
            invoice.setRows(List.of(row));
            se.swedsoft.bookkeeping.data.SSAddress invoiceAddress =
                    new se.swedsoft.bookkeeping.data.SSAddress();
            invoiceAddress.setName("Kund AB");
            invoiceAddress.setCity("Stockholm");
            invoice.setInvoiceAddress(invoiceAddress);

            SSVoucher snapshot = new SSVoucher(17);
            snapshot.setLocalDate(LocalDate.of(2026, 1, 15));
            snapshot.setDescription("Fakturajournal");
            SSVoucherRow voucherRow = new SSVoucherRow();
            voucherRow.setAccountNr(1510);
            voucherRow.setDebet(new BigDecimal("250"));
            voucherRow.setAdded(true);
            snapshot.getRows().add(voucherRow);
            invoice.setVoucher(snapshot);

            store.addInvoice(11, invoice);
            connection.commit();

            List<SSInvoice> read = store.getInvoices(11);
            assertThat(read).hasSize(1);
            SSInvoice restored = read.get(0);
            assertThat(restored.getNumber()).isEqualTo(1001);
            assertThat(restored.getLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(restored.getLocalDueDate()).isEqualTo(LocalDate.of(2026, 2, 14));
            assertThat(restored.getCustomerNr()).isEqualTo("K1");
            assertThat(restored.getOurContactPerson()).isEqualTo("Vi");
            assertThat(restored.getCurrency().getName()).isEqualTo("SEK");
            assertThat(restored.getPaymentTerm().getName()).isEqualTo("30 dagar");
            assertThat(restored.getStoredTaxRates().get(0)).isEqualByComparingTo("25");
            assertThat(restored.getStoredType()).isEqualTo(SSInvoiceType.NORMAL);
            assertThat(restored.getCurrencyRate()).isEqualByComparingTo("1");
            assertThat(restored.getYourOrderNumber()).isEqualTo("PO-55");
            assertThat(restored.getOCRNumber()).isEqualTo("100155");
            assertThat(restored.getText()).isEqualTo("Fakturatext");
            assertThat(restored.getDefaultAccounts())
                    .containsEntry(SSDefaultAccount.CustomerClaim, 1510);
            assertThat(restored.getInvoiceAddress().getCity()).isEqualTo("Stockholm");
            assertThat(restored.getRows()).hasSize(1);
            SSSaleRow restoredRow = restored.getRows().get(0);
            assertThat(restoredRow.getProductNr()).isEqualTo("A1");
            assertThat(restoredRow.getUnitprice()).isEqualByComparingTo("100");
            assertThat(restoredRow.getQuantity()).isEqualByComparingTo("2");
            assertThat(restoredRow.getUnit().getName()).isEqualTo("st");
            assertThat(restoredRow.getTaxCode()).isEqualTo(SSTaxCode.TAXRATE_1);
            assertThat(restoredRow.getAccountNr()).isEqualTo(3010);
            assertThat(restoredRow.getProjectNr()).isEqualTo("P1");
            SSVoucher restoredVoucher = restored.getStoredVoucher();
            assertThat(restoredVoucher).isNotNull();
            assertThat(restoredVoucher.getNumber()).isEqualTo(17);
            assertThat(restoredVoucher.getLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(restoredVoucher.getRows()).hasSize(1);
            assertThat(restoredVoucher.getRows().get(0).getAccountNr()).isEqualTo(1510);
            assertThat(restoredVoucher.getRows().get(0).getDebet()).isEqualByComparingTo("250");
            assertThat(restoredVoucher.getRows().get(0).isAdded()).isTrue();

            assertThat(store.nextInvoiceNumber(11)).isEqualTo(1002);
        }
    }

    @Test
    void invoiceSumsMirrorLegacyBalanceMath() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(12);
            writer.addCompany(connection, company);
            connection.commit();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO inpayment (legacy_id,company_id,number,"
                        + "payment_date,entered) SELECT 1,id,1,DATE '2026-01-20',false "
                        + "FROM company WHERE legacy_id=12");
                statement.executeUpdate("INSERT INTO inpayment_row (inpayment_id,row_number,"
                        + "invoice_number,value) VALUES ((SELECT id FROM inpayment WHERE legacy_id=1),1,2001,500)");
                statement.executeUpdate("INSERT INTO customer_credit_invoice (legacy_id,"
                        + "company_id,number,crediting_invoice_number,tax_free,"
                        + "eu_sale_commodity,eu_sale_third_party_commodity,printed,entered,"
                        + "reminder_count,interest_invoiced,stock_influencing,tax_rate_1) "
                        + "SELECT 1,id,9001,2001,false,false,false,false,false,0,false,false,25 "
                        + "FROM company WHERE legacy_id=12");
                statement.executeUpdate("INSERT INTO customer_credit_invoice_row "
                        + "(credit_invoice_id,company_id,row_number,description,unit_price,"
                        + "quantity,tax_code) VALUES "
                        + "((SELECT id FROM customer_credit_invoice WHERE legacy_id=1),"
                        + "(SELECT id FROM company WHERE legacy_id=12),"
                        + "0,'Kreditrad',100,1,'TAXRATE_1')");
            }
            connection.commit();
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(connection);

            assertThat(store.inpaymentSum(12, 2001)).isEqualByComparingTo("500");
            assertThat(store.inpaymentSum(12, 9999)).isEqualByComparingTo("0");
            // Credit invoice: 100 net + 25% tax, no rounding (roundingOff=true keeps ören).
            assertThat(store.creditInvoiceSum(12, 2001, true)).isEqualByComparingTo("125");
            assertThat(store.creditInvoiceSum(12, 9999, true)).isEqualByComparingTo("0");
        }
    }

    @Test
    void creditInvoicesRoundTripWithCreditingNumber() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(13);
            writer.addCompany(connection, company);
            connection.commit();
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(connection);

            se.swedsoft.bookkeeping.data.SSCreditInvoice credit =
                    new se.swedsoft.bookkeeping.data.SSCreditInvoice();
            credit.setNumber(9001);
            credit.setCreditingNr(2001);
            credit.setLocalDate(LocalDate.of(2026, 2, 1));
            credit.setLocalDueDate(LocalDate.of(2026, 2, 1));
            credit.setCustomerNr("K1");
            credit.setCustomerName("Kund AB");
            credit.setTaxRate1(new BigDecimal("25"));
            credit.setEntered(true);
            SSSaleRow row = new SSSaleRow();
            row.setDescription("Kreditrad");
            row.setUnitprice(new BigDecimal("100"));
            row.setQuantity(1);
            row.setTaxCode(SSTaxCode.TAXRATE_1);
            row.setAccountNr(3010);
            credit.setRows(List.of(row));
            store.addCreditInvoice(13, credit);
            connection.commit();

            List<se.swedsoft.bookkeeping.data.SSCreditInvoice> read = store.getCreditInvoices(13);
            assertThat(read).hasSize(1);
            se.swedsoft.bookkeeping.data.SSCreditInvoice restored = read.get(0);
            assertThat(restored.getNumber()).isEqualTo(9001);
            assertThat(restored.getCreditingNr()).isEqualTo(2001);
            assertThat(restored.getCustomerName()).isEqualTo("Kund AB");
            assertThat(restored.isEntered()).isTrue();
            assertThat(restored.getRows()).hasSize(1);
            assertThat(restored.getRows().get(0).getDescription()).isEqualTo("Kreditrad");

            // The credit sum for the credited invoice now reflects the credit invoice total.
            assertThat(store.creditInvoiceSum(13, 2001, true)).isEqualByComparingTo("125");
            // Filtered reads only return credit invoices crediting that invoice.
            assertThat(store.getCreditInvoices(13, 2001)).hasSize(1);
            assertThat(store.getCreditInvoices(13, 4711)).isEmpty();
            assertThat(store.nextCreditInvoiceNumber(13)).isEqualTo(9002);
        }
    }

    @Test
    void markInvoiceEnteredFlipsFlagAndStoresVoucherSnapshot() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(14);
            writer.addCompany(connection, company);
            connection.commit();
            NormalizedInvoiceStore store = new NormalizedInvoiceStore(connection);

            SSInvoice invoice = new SSInvoice();
            invoice.setNumber(7001);
            invoice.setLocalDate(LocalDate.of(2026, 6, 1));
            invoice.setCustomerNr("K1");
            invoice.setCustomerName("Kund AB");
            store.addInvoice(14, invoice);
            connection.commit();
            assertThat(store.getInvoices(14).get(0).isEntered()).isFalse();
            // The SSInvoice constructor always carries an empty voucher object; the store
            // mirrors the staging converter and persists it as an empty snapshot header.
            SSVoucher empty = store.getInvoices(14).get(0).getStoredVoucher();
            assertThat(empty).isNotNull();
            assertThat(empty.getRows()).isEmpty();

            SSVoucher booked = new SSVoucher(88);
            booked.setLocalDate(LocalDate.of(2026, 6, 1));
            booked.setDescription("Fakturajournal nr 1");
            SSVoucherRow claim = new SSVoucherRow();
            claim.setAccountNr(1510);
            claim.setDebet(new BigDecimal("125"));
            booked.getRows().add(claim);
            store.markInvoiceEntered(14, 7001, booked);
            connection.commit();

            SSInvoice restored = store.getInvoices(14).get(0);
            assertThat(restored.isEntered()).isTrue();
            assertThat(restored.getStoredVoucher()).isNotNull();
            assertThat(restored.getStoredVoucher().getNumber()).isEqualTo(88);
            assertThat(restored.getStoredVoucher().getRows()).hasSize(1);
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:invoice_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
