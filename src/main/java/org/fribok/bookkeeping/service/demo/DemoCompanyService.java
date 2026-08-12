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
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates the bundled, deterministic two-year demo company using Bokfri services. */
public final class DemoCompanyService {
    public static final String NAME = "Bokfri Demo AB";
    public static final String CORPORATE_ID = "559999-9999";
    public static final String LEGACY_NAME = "Demoföretaget";
    public static final String LEGACY_CORPORATE_ID = "969707-8567";

    private static final LocalDate FIRST_FROM = LocalDate.of(2025, 7, 1);
    private static final LocalDate FIRST_TO = LocalDate.of(2026, 6, 30);
    private static final LocalDate CURRENT_FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate CURRENT_TO = LocalDate.of(2027, 6, 30);

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
        SSNewCompany company = company();
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
        createVoucher(1, LocalDate.of(2025, 7, 1), "Insättning av aktiekapital",
                debit(1930, "25000"), credit(2081, "25000"));
        createVoucher(2, LocalDate.of(2025, 7, 8), "Dator och kontorsutrustning",
                debit(5410, "8000"), debit(2641, "2000"), credit(1930, "10000"));
        createVoucher(3, LocalDate.of(2025, 9, 30), "Konsultintäkt",
                debit(1930, "50000"), credit(3001, "40000"), credit(2611, "10000"));
        createVoucher(4, LocalDate.of(2026, 2, 2), "Företagsförsäkring",
                debit(6310, "4800"), credit(1930, "4800"));
        createVoucher(5, LocalDate.of(2026, 6, 30), "Bankavgifter",
                debit(6570, "600"), credit(1930, "600"));
        createVoucher(6, LocalDate.of(2026, 6, 30), "Omföring av årets resultat",
                debit(3001, "40000"), credit(5410, "8000"), credit(6310, "4800"),
                credit(6570, "600"), credit(2099, "26600"));

        database.setCurrentYear(current);
        database.init(false);
        new OpeningBalanceService(database).carryForward(first, current, true);
        createVoucher(1, LocalDate.of(2026, 7, 3), "Kontorshyra juli",
                debit(5010, "8000"), debit(2641, "2000"), credit(1930, "10000"));
        createVoucher(2, LocalDate.of(2026, 7, 31), "Bankavgift juli",
                debit(6570, "125"), credit(1930, "125"));

        SSCustomer firstCustomer = customer("1001", "Fjällglimt Hotell AB", "ekonomi@fjallglimt.example");
        SSCustomer secondCustomer = customer("1002", "Sundby Arkitekter AB", "faktura@sundby.example");
        CustomerService customers = new CustomerService(database);
        customers.create(firstCustomer);
        customers.create(secondCustomer);

        SSSupplier firstSupplier = supplier("2001", "Nordisk Kontorsservice AB", "faktura@kontorsservice.example");
        SSSupplier secondSupplier = supplier("2002", "Stadens Fastigheter AB", "hyra@stadensfastigheter.example");
        SupplierService suppliers = new SupplierService(database);
        suppliers.create(firstSupplier);
        suppliers.create(secondSupplier);

        SSProduct consulting = product("KONSULT", "Ekonomisk rådgivning, timme", "1200");
        SSProduct workshop = product("WORKSHOP", "Bokföringsworkshop", "7500");
        ProductService products = new ProductService(database);
        products.create(consulting);
        products.create(workshop);

        InvoiceService invoices = new InvoiceService(database);
        SSInvoice paid = invoice(invoices.nextNumber(), firstCustomer, consulting,
                LocalDate.of(2026, 7, 6), 10);
        invoices.create(paid);
        SSInvoice open = invoice(invoices.nextNumber(), secondCustomer, workshop,
                LocalDate.of(2026, 8, 3), 1);
        invoices.create(open);
        refreshCurrent(company, current);
        invoices.commitJournal(invoices.planJournal(CURRENT_FROM, LocalDate.of(2026, 8, 3)));
        paid = invoices.find(paid.getNumber()).orElseThrow();

        SSInpayment inpayment = new SSInpayment();
        inpayment.setNumber(1);
        inpayment.setLocalDate(LocalDate.of(2026, 8, 5));
        inpayment.setText("Betalning faktura " + paid.getNumber());
        SSInpaymentRow paymentRow = new SSInpaymentRow(paid);
        paymentRow.setValue(SSInvoiceMath.getSaldo(paid));
        paymentRow.setCurrencyRate(BigDecimal.ONE);
        inpayment.setRows(List.of(paymentRow));
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

    private SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName(NAME);
        company.setCorporateID(CORPORATE_ID);
        company.setVATNumber("SE559999999901");
        company.setTaxRegistered(true);
        company.setCurrency(new SSCurrency("SEK", "Svenska kronor"));
        company.setPaymentTerm(new SSPaymentTerm("30", "30 dagar netto"));
        company.setStandardUnit(new SSUnit("tim", "Timme"));
        company.setTaxrate1(new BigDecimal("25"));
        company.setTaxrate2(new BigDecimal("12"));
        company.setTaxrate3(new BigDecimal("6"));
        company.setContactPerson("Kim Demo");
        company.setPhone("08-555 01 00");
        company.setEMail("info@bokfri-demo.example");
        company.setBankGiroNumber("5555-0100");
        company.setAddress(new SSAddress(NAME, "Demogatan 1", "", "111 22", "Stockholm", "Sverige"));
        Map<SSDefaultAccount, Integer> defaults = new LinkedHashMap<>();
        for (SSDefaultAccount account : SSDefaultAccount.values()) {
            defaults.put(account, account.getDefaultAccountNumber());
        }
        company.setDefaultAccounts(defaults);
        return company;
    }

    private SSCustomer customer(String number, String name, String email) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(number);
        customer.setName(name);
        customer.setEMail(email);
        customer.setInvoiceCurrency(database.getCurrentCompany().getCurrency());
        customer.setPaymentTerm(database.getCurrentCompany().getPaymentTerm());
        return customer;
    }

    private SSSupplier supplier(String number, String name, String email) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(number);
        supplier.setName(name);
        supplier.setEMail(email);
        return supplier;
    }

    private SSProduct product(String number, String description, String price) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setDescription(description);
        product.setSellingPrice(new BigDecimal(price));
        product.setTaxCode(SSTaxCode.TAXRATE_1);
        product.setDefaultAccount(SSDefaultAccount.Sales, 3001);
        return product;
    }

    private SSInvoice invoice(int number, SSCustomer customer, SSProduct product, LocalDate date, int quantity) {
        SSInvoice invoice = new SSInvoice();
        invoice.setNumber(number);
        invoice.setCustomer(customer);
        invoice.setLocalDate(date);
        invoice.setLocalDueDate(date.plusDays(30));
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.setDefaultAccounts(database.getCurrentCompany().getDefaultAccounts());
        SSSaleRow row = new SSSaleRow(product);
        row.setQuantity(quantity);
        row.setAccountNr(3001);
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.setRows(List.of(row));
        return invoice;
    }

    private void createVoucher(int number, LocalDate date, String description, VoucherEntry... entries) {
        SSVoucher voucher = new SSVoucher(number);
        voucher.setLocalDate(date);
        voucher.setDescription(description);
        for (VoucherEntry entry : entries) {
            SSAccount account = database.getCurrentYear().getAccountPlan().getAccount(entry.account());
            if (account == null) {
                throw new IllegalStateException("Demo account is unavailable: " + entry.account());
            }
            voucher.addVoucherRow(new SSVoucherRow(account, entry.debit(), entry.credit()));
        }
        new VoucherService(database).create(voucher);
    }

    private static VoucherEntry debit(int account, String amount) {
        return new VoucherEntry(account, new BigDecimal(amount), null);
    }

    private static VoucherEntry credit(int account, String amount) {
        return new VoucherEntry(account, null, new BigDecimal(amount));
    }

    private static boolean isRecognizedDemo(SSNewCompany company) {
        return matches(company, NAME, CORPORATE_ID)
                || matches(company, LEGACY_NAME, LEGACY_CORPORATE_ID);
    }

    private static boolean matches(SSNewCompany company, String name, String corporateId) {
        return name.equals(company.getName()) && corporateId.equals(company.getCorporateID());
    }

    private record VoucherEntry(int account, BigDecimal debit, BigDecimal credit) {}
}
