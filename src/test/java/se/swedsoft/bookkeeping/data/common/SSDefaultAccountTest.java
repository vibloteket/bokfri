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
    void incomingTaxDefaultsTo2641() {
        SSNewCompany company = new SSNewCompany();

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
