package org.fribok.bookkeeping.service.vat;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.Tag; import se.swedsoft.bookkeeping.data.system.*; import static org.assertj.core.api.Assertions.assertThat;
@Tag("integration") class VatServiceIntegrationTest {
 @BeforeAll static void setup() throws Exception{SSDBTestFixture.setupOnce();}
 @BeforeEach void reset(){SSDBTestFixture.resetCaches();}
 @AfterEach void drain(){SSDBTestFixture.drainUncaughtExceptions();}
 @Test void reportContainsStandardBoxesAndBalancedSettlement(){var db=SSDB.getInstance();var y=db.getCurrentYear();VatService s=new VatService(db);VatReport r=s.report(y.getLocalFrom(),y.getLocalTo());assertThat(r.boxes()).extracting(VatReportBox::number).contains(5,10,11,12,48,49,50,60,61,62);VatSettlementPlan p=s.plan(y.getLocalFrom(),y.getLocalTo());assertThat(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(p.voucher())).isEqualByComparingTo(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(p.voucher()));}
}
