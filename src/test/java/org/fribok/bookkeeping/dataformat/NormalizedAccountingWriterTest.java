package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedAccountingWriterTest {

    @Test
    void writesAndReadsBackTheFullAccountingCore() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            NormalizedAccountingReader reader = new NormalizedAccountingReader();

            SSNewCompany company = new SSNewCompany();
            company.setId(7);
            company.setName("Skriv AB");
            company.setCorporateID("556000-0007");
            company.setVATNumber("SE556000000701");
            writer.addCompany(connection, company);

            SSAccountPlan plan = new SSAccountPlan("Testplan");
            plan.setId(3);
            SSAccount bank = new SSAccount(1930);
            bank.setDescription("Bank");
            plan.addAccount(bank);
            SSAccount sales = new SSAccount(3010);
            plan.addAccount(sales);

            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setId(11);
            year.setLocalFrom(LocalDate.of(2026, 1, 1));
            year.setLocalTo(LocalDate.of(2026, 12, 31));
            year.setAccountPlan(plan);
            writer.addAccountingYear(connection, 7, year);

            SSVoucher voucher = new SSVoucher(1);
            voucher.setLocalDate(LocalDate.of(2026, 3, 15));
            voucher.setDescription("Test");
            SSVoucherRow row = new SSVoucherRow(bank, new BigDecimal("100.00"), null);
            row.setLocalEditedDate(LocalDateTime.of(2026, 3, 29, 2, 30));
            row.setEditedSignature("vb");
            voucher.addVoucherRow(row);
            voucher.addVoucherRow(new SSVoucherRow(sales, null, new BigDecimal("100.00")));
            writer.addVoucher(connection, 11, voucher);
            connection.commit();

            var readVouchers = reader.vouchers(connection, 11);
            assertThat(readVouchers).hasSize(1);
            SSVoucher read = readVouchers.get(0);
            assertThat(read.getNumber()).isEqualTo(1);
            assertThat(read.getRows()).hasSize(2);
            SSVoucherRow readRow = read.getRows().get(0);
            assertThat(readRow.getAccountNr()).isEqualTo(1930);
            assertThat(readRow.getDebet()).isEqualByComparingTo("100.00");
            assertThat(readRow.getLocalEditedDate())
                    .isEqualTo(LocalDateTime.of(2026, 3, 29, 3, 30));
            assertThat(readRow.getEditedSignature()).isEqualTo("vb");

            voucher.setDescription("Uppdaterad");
            voucher.getRows().clear();
            voucher.addVoucherRow(new SSVoucherRow(sales, null, new BigDecimal("50.00")));
            writer.updateVoucher(connection, 11, voucher);
            connection.commit();
            read = reader.vouchers(connection, 11).get(0);
            assertThat(read.getDescription()).isEqualTo("Uppdaterad");
            assertThat(read.getRows()).hasSize(1);

            writer.deleteVoucher(connection, 11, 1);
            connection.commit();
            assertThat(reader.vouchers(connection, 11)).isEmpty();

            company.setName("Uppdaterat AB");
            writer.updateCompany(connection, company);
            connection.commit();
            assertThat(reader.companies(connection).get(0).getName()).isEqualTo("Uppdaterat AB");

            writer.deleteCompany(connection, 7);
            connection.commit();
            assertThat(reader.companies(connection)).isEmpty();
        }
    }

    @Test
    void rejectsWritesAgainstMissingParents() throws Exception {
        try (Connection connection = connection()) {
            migrate(connection);
            NormalizedAccountingWriter writer =
                    new NormalizedAccountingWriter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SSNewCompany company = new SSNewCompany();
            company.setId(9);
            assertThatThrownBy(() -> writer.deleteCompany(connection, 9))
                    .isInstanceOf(java.sql.SQLException.class);
            SSVoucher voucher = new SSVoucher(1);
            assertThatThrownBy(() -> writer.addVoucher(connection, 99, voucher))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:writer_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
