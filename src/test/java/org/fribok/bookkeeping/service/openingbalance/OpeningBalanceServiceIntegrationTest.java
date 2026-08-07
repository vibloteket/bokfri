package org.fribok.bookkeeping.service.openingbalance;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.Tag; import se.swedsoft.bookkeeping.calc.math.SSAccountMath; import se.swedsoft.bookkeeping.data.*; import se.swedsoft.bookkeeping.data.system.*; import java.math.BigDecimal; import java.util.*; import static org.assertj.core.api.Assertions.assertThat;
@Tag("integration") class OpeningBalanceServiceIntegrationTest {
 @BeforeAll static void setup()throws Exception{SSDBTestFixture.setupOnce();}@BeforeEach void reset(){SSDBTestFixture.resetCaches();}@AfterEach void drain(){SSDBTestFixture.drainUncaughtExceptions();}
 @Test void balancedValuesCanBeReplaced(){var db=SSDB.getInstance();var y=db.getCurrentYear();List<SSAccount>a=SSAccountMath.getBalanceAccounts(y);OpeningBalanceService s=new OpeningBalanceService(db);Map<SSAccount,BigDecimal> old=new HashMap<>(y.getInBalance());try{OpeningBalancePlan p=s.replace(y,Map.of(a.get(0).getNumber(),new BigDecimal("10"),a.get(1).getNumber(),new BigDecimal("-10")));assertThat(p.difference()).isZero();assertThat(s.current(y).balances()).hasSize(2);}finally{y.setInBalance(old);db.updateAccountingYear(y);}}
}
