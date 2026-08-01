package se.swedsoft.bookkeeping.data.system;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SSDefaultAccountPlansIntegrationTest {

    @BeforeAll
    static void setUpDatabase() throws Exception {
        SSDBTestFixture.setupOnce();
    }

    @Test
    void importsAllGeneratedBas2026AccountPlans() {
        Map<String, SSAccountPlan> plans = SSDB.getInstance().getAccountPlans().stream()
                .collect(Collectors.toMap(SSAccountPlan::getName, Function.identity()));

        assertThat(plans).containsKeys(
                "BAS 2026 - Aktiebolag",
                "BAS 2026 - Ekonomisk förening",
                "BAS 2026 - Enskild firma K1",
                "BAS 2026 - Enskild firma, ej K1",
                "BAS 2026 - Handelsbolag och kommanditbolag",
                "BAS 2026 - Ideell förening, stiftelse och trossamfund");

        assertThat(plans.get("BAS 2026 - Aktiebolag").getAccounts()).hasSize(1282);
        assertThat(plans.get("BAS 2026 - Ekonomisk förening").getAccounts()).hasSize(1282);
        assertThat(plans.get("BAS 2026 - Enskild firma K1").getAccounts()).hasSize(25);
        assertThat(plans.get("BAS 2026 - Enskild firma, ej K1").getAccounts()).hasSize(1281);
        assertThat(plans.get("BAS 2026 - Handelsbolag och kommanditbolag").getAccounts()).hasSize(1281);
        assertThat(plans.get("BAS 2026 - Ideell förening, stiftelse och trossamfund").getAccounts()).hasSize(1281);
    }

    @Test
    void hardCodedDefaultsExistInStandardBas2026Plan() {
        SSAccountPlan limitedCompany = findPlan("BAS 2026 - Aktiebolag");

        for (SSDefaultAccount defaultAccount : SSDefaultAccount.values()) {
            assertThat(limitedCompany.getAccount(defaultAccount.getDefaultAccountNumber()))
                    .as(defaultAccount.name())
                    .isNotNull();
        }
    }

    @Test
    void usesOnlyModernVatSettlementAccounts() {
        SSAccountPlan limitedCompany = findPlan("BAS 2026 - Aktiebolag");

        assertThat(limitedCompany.getAccount(1480).getVATCode()).isBlank();
        assertThat(limitedCompany.getAccount(1650).getVATCode()).isEqualTo("R1");
        assertThat(limitedCompany.getAccount(2480).getVATCode()).isBlank();
        assertThat(limitedCompany.getAccount(2650).getVATCode()).isEqualTo("R2");
        assertThat(limitedCompany.getAccount(3740).getVATCode()).isEqualTo("A");
    }

    @Test
    void importingDefaultsAgainIsIdempotent() {
        int countBefore = SSDB.getInstance().getAccountPlans().size();

        SSDB.getInstance().checkImportDefaultAccountPlans();

        assertThat(SSDB.getInstance().getAccountPlans()).hasSize(countBefore);
    }

    @Test
    void keepsOrganizationSpecificAccount2087Separate() {
        SSAccountPlan limitedCompany = findPlan("BAS 2026 - Aktiebolag");
        SSAccountPlan economicAssociation = findPlan("BAS 2026 - Ekonomisk förening");

        assertThat(limitedCompany.getAccount(2087).getDescription())
                .isEqualTo("Bunden överkursfond");
        assertThat(economicAssociation.getAccount(2087).getDescription())
                .isEqualTo("Insatsemission");
    }

    @Test
    void importsK1VatSruAndReportCodes() {
        SSAccountPlan k1 = findPlan("BAS 2026 - Enskild firma K1");
        SSAccount account = k1.getAccount(3100);

        assertThat(account.getDescription()).isEqualTo("Momsfria intäkter");
        assertThat(account.getVATCode()).isEqualTo("MF");
        assertThat(account.getSRUCode()).isEqualTo("7401");
        assertThat(account.getReportCode()).isEqualTo("R2");
    }

    private static SSAccountPlan findPlan(String name) {
        return SSDB.getInstance().getAccountPlans().stream()
                .filter(plan -> name.equals(plan.getName()))
                .findFirst()
                .orElseThrow();
    }
}
