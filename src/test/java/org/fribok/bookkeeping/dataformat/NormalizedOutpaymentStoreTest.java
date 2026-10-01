package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;

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

class NormalizedOutpaymentStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void clearLegacySingletonSelection() {
        se.swedsoft.bookkeeping.data.system.SSDB stale =
                se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
        stale.setCurrentYear(null);
        stale.setCurrentCompany(null);
    }

    @Test
    void outpaymentsRoundTripWithSum() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(41);
            writer.addCompany(connection, company);
            connection.commit();
            NormalizedOutpaymentStore store = new NormalizedOutpaymentStore(connection);

            SSOutpayment outpayment = new SSOutpayment();
            outpayment.setNumber(71);
            outpayment.setLocalDate(LocalDate.of(2026, 5, 3));
            outpayment.setText("Utbetalning L1");
            SSOutpaymentRow row = new SSOutpaymentRow();
            row.setInvoiceNr(501);
            row.setValue(new BigDecimal("260"));
            row.setCurrencyRate(BigDecimal.ONE);
            outpayment.setRows(List.of(row));
            store.addOutpayment(41, outpayment);
            connection.commit();

            List<SSOutpayment> read = store.getOutpayments(41);
            assertThat(read).hasSize(1);
            SSOutpayment restored = read.get(0);
            assertThat(restored.getNumber()).isEqualTo(71);
            assertThat(restored.getText()).isEqualTo("Utbetalning L1");
            assertThat(restored.getRows()).hasSize(1);
            assertThat(restored.getRows().get(0).getInvoiceNr()).isEqualTo(501);
            assertThat(restored.getRows().get(0).getValue()).isEqualByComparingTo("260");

            assertThat(store.outpaymentSum(41, 501)).isEqualByComparingTo("260");
            assertThat(store.outpaymentSum(41, 9999)).isEqualByComparingTo("0");
            assertThat(store.nextOutpaymentNumber(41)).isEqualTo(72);
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:outpayment_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
