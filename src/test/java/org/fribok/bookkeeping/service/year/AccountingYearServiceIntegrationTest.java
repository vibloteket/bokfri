package org.fribok.bookkeeping.service.year;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.Tag; import se.swedsoft.bookkeeping.data.system.*; import static org.assertj.core.api.Assertions.assertThat;
@Tag("integration") class AccountingYearServiceIntegrationTest {
 @BeforeAll static void setup()throws Exception{SSDBTestFixture.setupOnce();}
 @BeforeEach void reset(){SSDBTestFixture.resetCaches();}
 @AfterEach void drain(){SSDBTestFixture.drainUncaughtExceptions();}
 @Test void accountPlansAreAvailable(){assertThat(new AccountingYearService(SSDB.getInstance()).accountPlans()).isNotEmpty();}
}
