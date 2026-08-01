package se.swedsoft.bookkeeping.data.common;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSNewCompany;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for standard account fallbacks and company overrides.
 */
class SSDefaultAccountTest {

    @Test
    void defaultsUseCurrentBasAccounts() {
        SSNewCompany company = new SSNewCompany();

        assertThat(company.getDefaultAccount(SSDefaultAccount.Sales)).isEqualTo(3001);
        assertThat(company.getDefaultAccount(SSDefaultAccount.Purchases)).isEqualTo(4010);
        assertThat(company.getDefaultAccount(SSDefaultAccount.InterestProfit)).isEqualTo(8310);
        assertThat(company.getDefaultAccount(SSDefaultAccount.IncommingTax)).isEqualTo(2641);
    }

    @Test
    void incomingTaxKeepsExplicitCompanyDefault() {
        SSNewCompany company = new SSNewCompany();
        Map<SSDefaultAccount, Integer> defaults = new HashMap<>();
        defaults.put(SSDefaultAccount.IncommingTax, 2645);
        company.setDefaultAccounts(defaults);

        assertThat(company.getDefaultAccount(SSDefaultAccount.IncommingTax)).isEqualTo(2645);
    }
}
