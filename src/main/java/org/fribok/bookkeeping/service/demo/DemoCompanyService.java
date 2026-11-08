package org.fribok.bookkeeping.service.demo;

import org.fribok.bookkeeping.service.company.CompanyService;
import org.fribok.bookkeeping.service.customer.CustomerService;
import org.fribok.bookkeeping.service.inpayment.InpaymentService;
import org.fribok.bookkeeping.service.invoice.InvoiceService;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalanceService;
import org.fribok.bookkeeping.service.product.ProductService;
import org.fribok.bookkeeping.service.supplier.SupplierService;
import org.fribok.bookkeeping.service.voucher.VoucherService;
import org.fribok.bookkeeping.service.year.AccountingYearService;
import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Creates the bundled, deterministic two-year demo company using Bokfri services. */
public final class DemoCompanyService {
    public static final String NAME = "Bokfri Demo AB";
    public static final String CORPORATE_ID = "559999-9999";
    public static final String LEGACY_NAME = "Demoföretaget";
    public static final String LEGACY_CORPORATE_ID = "969707-8567";

    static final LocalDate FIRST_FROM = LocalDate.of(2025, 7, 1);
    static final LocalDate FIRST_TO = LocalDate.of(2026, 6, 30);
    static final LocalDate CURRENT_FROM = LocalDate.of(2026, 7, 1);
    static final LocalDate CURRENT_TO = LocalDate.of(2027, 6, 30);

    private final SSDB database;

    public DemoCompanyService(SSDB database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Returns the companies that are safe for the recreate operation to replace. */
    public List<SSNewCompany> findDemoCompanies() {
        return database.getCompanies().stream().filter(DemoCompanyService::isRecognizedDemo).toList();
    }

    /** Creates a demo only when the database has no companies. */
    public DemoCompanyResult createIfDatabaseEmpty() {
        if (!database.getCompanies().isEmpty()) {
            return null;
        }
        database.dropTriggers();
        try {
            return create(0);
        } finally {
            database.createTriggers();
        }
    }

    /** Replaces recognized current and legacy demos, leaving every other company untouched. */
    public DemoCompanyResult recreate() {
        database.dropTriggers();
        try {
            List<SSNewCompany> demos = findDemoCompanies();
            SSNewCompany fallback = database.getCompanies().stream()
                    .filter(company -> !demos.contains(company)).findFirst().orElse(null);
            boolean temporaryFallback = fallback == null;
            if (temporaryFallback) {
                fallback = new SSNewCompany();
                fallback.setName("Bokfri demo replacement in progress");
                database.addCompany(fallback);
            }
            database.setCurrentCompany(fallback);
            for (SSNewCompany demo : demos) {
                database.deleteCompany(demo);
            }
            DemoCompanyResult result = create(demos.size());
            if (temporaryFallback) {
                database.deleteCompany(fallback);
            }
            return result;
        } finally {
            database.createTriggers();
        }
    }

    private DemoCompanyResult create(int removedCompanies) {
        SSNewCompany company = DemoCompanyData.company();
        new CompanyService(database).create(company);
        database.setCurrentCompany(company);

        SSAccountPlan sourcePlan = database.getAccountPlans().stream()
                .filter(plan -> "BAS 2026 - Aktiebolag".equals(plan.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BAS 2026 - Aktiebolag is unavailable"));
        AccountingYearService years = new AccountingYearService(database);
        SSNewAccountingYear first = years.create(FIRST_FROM, FIRST_TO, sourcePlan);
        SSNewAccountingYear current = years.create(CURRENT_FROM, CURRENT_TO, sourcePlan);

        database.setCurrentYear(first);
        for (SSVoucher voucher : DemoCompanyData.firstVouchers(first)) {
            new VoucherService(database).create(voucher);
        }

        database.setCurrentYear(current);
        database.init(false);
        new OpeningBalanceService(database).carryForward(first, current, true);
        for (SSVoucher voucher : DemoCompanyData.currentVouchers(current)) {
            new VoucherService(database).create(voucher);
        }

        List<SSCustomer> demoCustomers = DemoCompanyData.customers(company);
        CustomerService customers = new CustomerService(database);
        for (SSCustomer customer : demoCustomers) {
            customers.create(customer);
        }
        SSCustomer firstCustomer = demoCustomers.get(0);
        SSCustomer secondCustomer = demoCustomers.get(1);

        SupplierService suppliers = new SupplierService(database);
        for (SSSupplier supplier : DemoCompanyData.suppliers(company)) {
            suppliers.create(supplier);
        }

        List<SSProduct> demoProducts = DemoCompanyData.products(company);
        ProductService products = new ProductService(database);
        for (SSProduct product : demoProducts) {
            products.create(product);
        }
        SSProduct consulting = demoProducts.get(0);
        SSProduct workshop = demoProducts.get(1);

        InvoiceService invoices = new InvoiceService(database);
        SSInvoice paid = DemoCompanyData.invoice(company, invoices.nextNumber(), firstCustomer, consulting,
                LocalDate.of(2026, 7, 6), 10);
        invoices.create(paid);
        SSInvoice open = DemoCompanyData.invoice(company, invoices.nextNumber(), secondCustomer, workshop,
                LocalDate.of(2026, 8, 3), 1);
        invoices.create(open);
        refreshCurrent(company, current);
        invoices.commitJournal(invoices.planJournal(CURRENT_FROM, LocalDate.of(2026, 8, 3)));
        paid = invoices.find(paid.getNumber()).orElseThrow();

        SSInpayment inpayment = DemoCompanyData.inpayment(company, paid, SSInvoiceMath.getSaldo(paid));
        InpaymentService inpayments = new InpaymentService(database);
        inpayments.create(inpayment);
        refreshCurrent(company, current);
        inpayments.commitJournal(inpayments.planJournal(inpayment.getLocalDate(), inpayment.getLocalDate()));

        refreshCurrent(company, current);
        return new DemoCompanyResult(company, removedCompanies, 2, database.getVouchers(first).size()
                + database.getVouchers(current).size(), 2, 2, 2, 2);
    }

    private void refreshCurrent(SSNewCompany company, SSNewAccountingYear year) {
        try {
            Thread.sleep(200);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo creation was interrupted", exception);
        }
        database.setCurrentCompany(company);
        database.setCurrentYear(year);
        database.init(false);
    }

    /** Matches both identifying fields; a matching name or corporate ID alone is insufficient. */
    public static boolean isRecognizedDemo(SSNewCompany company) {
        return matches(company, NAME, CORPORATE_ID)
                || matches(company, LEGACY_NAME, LEGACY_CORPORATE_ID);
    }

    private static boolean matches(SSNewCompany company, String name, String corporateId) {
        return name.equals(company.getName()) && corporateId.equals(company.getCorporateID());
    }

}
