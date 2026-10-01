package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSNewCompany;
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

class NormalizedInpaymentStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void clearLegacySingletonSelection() {
        se.swedsoft.bookkeeping.data.system.SSDB stale =
                se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
        stale.setCurrentYear(null);
        stale.setCurrentCompany(null);
    }

    @Test
    void inpaymentsRoundTripWithBothVoucherSnapshots() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(21);
            writer.addCompany(connection, company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency (code,description,exchange_rate) "
                        + "VALUES ('SEK','Svensk krona',1)");
            }
            connection.commit();
            NormalizedInpaymentStore store = new NormalizedInpaymentStore(connection);

            SSInpayment inpayment = new SSInpayment();
            inpayment.setNumber(31);
            inpayment.setLocalDate(LocalDate.of(2026, 3, 1));
            inpayment.setText("Inbetalning K1");
            inpayment.getDefaultAccounts().put(SSDefaultAccount.CustomerClaim, 1510);
            SSInpaymentRow row = new SSInpaymentRow();
            row.setInvoiceNr(2001);
            row.setInvoiceCurrency(
                    new NormalizedRegisterStore(connection).getCurrencies().get(0));
            row.setInvoiceCurrencyRate(BigDecimal.ONE);
            row.setValue(new BigDecimal("500"));
            row.setCurrencyRate(BigDecimal.ONE);
            inpayment.setRows(List.of(row));

            SSVoucher main = new SSVoucher(41);
            main.setLocalDate(LocalDate.of(2026, 3, 1));
            main.setDescription("Inbetalningsjournal");
            SSVoucherRow mainRow = new SSVoucherRow();
            mainRow.setAccountNr(1510);
            mainRow.setCredit(new BigDecimal("500"));
            main.getRows().add(mainRow);
            inpayment.setVoucher(main);
            SSVoucher difference = new SSVoucher(42);
            difference.setLocalDate(LocalDate.of(2026, 3, 1));
            difference.setDescription("Kursdifferens");
            inpayment.setDifference(difference);

            store.addInpayment(21, inpayment);
            connection.commit();

            List<SSInpayment> read = store.getInpayments(21);
            assertThat(read).hasSize(1);
            SSInpayment restored = read.get(0);
            assertThat(restored.getNumber()).isEqualTo(31);
            assertThat(restored.getLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(restored.getText()).isEqualTo("Inbetalning K1");
            assertThat(restored.getDefaultAccounts())
                    .containsEntry(SSDefaultAccount.CustomerClaim, 1510);
            assertThat(restored.getRows()).hasSize(1);
            SSInpaymentRow restoredRow = restored.getRows().get(0);
            assertThat(restoredRow.getInvoiceNr()).isEqualTo(2001);
            assertThat(restoredRow.getInvoiceCurrency().getName()).isEqualTo("SEK");
            assertThat(restoredRow.getValue()).isEqualByComparingTo("500");
            assertThat(restored.getVoucher().getNumber()).isEqualTo(41);
            assertThat(restored.getVoucher().getRows()).hasSize(1);
            assertThat(restored.getVoucher().getRows().get(0).getCredit())
                    .isEqualByComparingTo("500");
            assertThat(restored.getStoredDifference().getNumber()).isEqualTo(42);
            assertThat(restored.getStoredDifference().getDescription())
                    .isEqualTo("Kursdifferens");

            assertThat(store.nextInpaymentNumber(21)).isEqualTo(32);
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:inpayment_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
