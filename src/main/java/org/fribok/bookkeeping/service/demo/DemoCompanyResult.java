package org.fribok.bookkeeping.service.demo;

import se.swedsoft.bookkeeping.data.SSNewCompany;

/** Result from creating or recreating the bundled demo company. */
public record DemoCompanyResult(SSNewCompany company, int removedCompanies, int accountingYears,
                                int vouchers, int customers, int suppliers, int products,
                                int invoices) {}
