package org.fribok.bookkeeping.service.demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;
import org.fribok.bookkeeping.service.report.FinancialReportService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration coverage for deterministic demo creation and safe replacement. */
@Tag("integration")
class DemoCompanyServiceIntegrationTest {
    private static Connection connection;

    @BeforeAll
    static void startDatabase() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        connection = DriverManager.getConnection("jdbc:hsqldb:mem:demo_service_test", "sa", "");
        SSDB.getInstance().startupLocal(connection);
    }

    @AfterAll
    static void stopDatabase() {
        SSDB.getInstance().shutdown();
    }

    @Test
    void createsTwoYearDemoAndRecreatesOnlyRecognizedDemo() {
        SSDB database = SSDB.getInstance();
        SSNewCompany ordinary = new SSNewCompany();
        ordinary.setName("Ordinarie Företag AB");
        ordinary.setCorporateID("556000-0001");
        database.addCompany(ordinary);

        DemoCompanyService service = new DemoCompanyService(database);
        DemoCompanyResult result = service.recreate();

        assertThat(result.removedCompanies()).isEqualTo(1);
        assertThat(database.getCompanies()).extracting(SSNewCompany::getName)
                .containsExactlyInAnyOrder("Ordinarie Företag AB", DemoCompanyService.NAME);
        List<SSNewAccountingYear> years = database.getYearsForCompany(result.company()).stream()
                .sorted(Comparator.comparing(SSNewAccountingYear::getLocalFrom)).toList();
        assertThat(years).hasSize(2);
        assertThat(years.get(0).getLocalFrom()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(years.get(0).getLocalTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(years.get(1).getLocalFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(years.get(1).getLocalTo()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(result.vouchers()).isGreaterThanOrEqualTo(9);
        assertThat(result.invoices()).isEqualTo(2);

        SSNewAccountingYear current = years.get(1);
        database.setCurrentCompany(result.company());
        database.setCurrentYear(current);
        List<SSVoucher> currentVouchers = database.getVouchers(current);
        assertThat(currentVouchers).allSatisfy(voucher -> {
            assertThat(voucher.getRows()).allSatisfy(row -> assertThat(row.getAccountNr()).isNotNull());
            assertThat(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(voucher))
                    .isEqualByComparingTo(
                            se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(voucher));
        });
        SSVoucher bankFee = currentVouchers.stream().filter(voucher -> voucher.getNumber() == 2)
                .findFirst().orElseThrow();
        assertThat(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(bankFee))
                .isEqualByComparingTo("125");
        assertThat(new FinancialReportService(current)
                .accountBalance(1930, LocalDate.of(2027, 6, 30)).balance())
                .isEqualByComparingTo(new BigDecimal("64475"));

        DemoCompanyResult recreated = service.recreate();
        assertThat(recreated.removedCompanies()).isEqualTo(1);
        assertThat(service.findDemoCompanies()).hasSize(1);
        assertThat(database.getCompanies()).extracting(SSNewCompany::getName)
                .containsExactlyInAnyOrder("Ordinarie Företag AB", DemoCompanyService.NAME);
    }
}
