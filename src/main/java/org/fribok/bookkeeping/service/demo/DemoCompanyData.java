package org.fribok.bookkeeping.service.demo;

import se.swedsoft.bookkeeping.data.SSAccount;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared demo definition with explicit company/year defaults for both storage formats. */
final class DemoCompanyData {
    private DemoCompanyData() {}

    static List<SSVoucher> firstVouchers(SSNewAccountingYear year) {
        return List.of(
                voucher(year, 1, LocalDate.of(2025, 7, 1), "Insättning av aktiekapital",
                        debit(1930, "25000"), credit(2081, "25000")),
                voucher(year, 2, LocalDate.of(2025, 7, 8), "Dator och kontorsutrustning",
                        debit(5410, "8000"), debit(2641, "2000"), credit(1930, "10000")),
                voucher(year, 3, LocalDate.of(2025, 9, 30), "Konsultintäkt",
                        debit(1930, "50000"), credit(3001, "40000"), credit(2611, "10000")),
                voucher(year, 4, LocalDate.of(2026, 2, 2), "Företagsförsäkring",
                        debit(6310, "4800"), credit(1930, "4800")),
                voucher(year, 5, LocalDate.of(2026, 6, 30), "Bankavgifter",
                        debit(6570, "600"), credit(1930, "600")),
                voucher(year, 6, LocalDate.of(2026, 6, 30), "Omföring av årets resultat",
                        debit(3001, "40000"), credit(5410, "8000"), credit(6310, "4800"),
                        credit(6570, "600"), credit(2099, "26600")));
    }

    static List<SSVoucher> currentVouchers(SSNewAccountingYear year) {
        return List.of(
                voucher(year, 1, LocalDate.of(2026, 7, 3), "Kontorshyra juli",
                        debit(5010, "8000"), debit(2641, "2000"), credit(1930, "10000")),
                voucher(year, 2, LocalDate.of(2026, 7, 31), "Bankavgift juli",
                        debit(6570, "125"), credit(1930, "125")));
    }

    static SSNewCompany company() {
        SSNewCompany company = new SSNewCompany();
        company.setName(DemoCompanyService.NAME);
        company.setCorporateID(DemoCompanyService.CORPORATE_ID);
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
        company.setAddress(new SSAddress(DemoCompanyService.NAME, "Demogatan 1", "", "111 22", "Stockholm", "Sverige"));
        Map<SSDefaultAccount, Integer> defaults = new LinkedHashMap<>();
        for (SSDefaultAccount account : SSDefaultAccount.values()) {
            defaults.put(account, account.getDefaultAccountNumber());
        }
        company.setDefaultAccounts(defaults);
        return company;
    }

    static List<SSCustomer> customers(SSNewCompany company) {
        return List.of(customer(company, "1001", "Fjällglimt Hotell AB", "ekonomi@fjallglimt.example"),
                customer(company, "1002", "Sundby Arkitekter AB", "faktura@sundby.example"));
    }

    static List<SSSupplier> suppliers(SSNewCompany company) {
        return List.of(supplier(company, "2001", "Nordisk Kontorsservice AB", "faktura@kontorsservice.example"),
                supplier(company, "2002", "Stadens Fastigheter AB", "hyra@stadensfastigheter.example"));
    }

    static List<SSProduct> products(SSNewCompany company) {
        return List.of(product(company, "KONSULT", "Ekonomisk rådgivning, timme", "1200"),
                product(company, "WORKSHOP", "Bokföringsworkshop", "7500"));
    }

    private static SSCustomer customer(SSNewCompany company, String number, String name, String email) {
        SSCustomer customer = new SSCustomer();
        customer.setOurContactPerson(company.getContactPerson());
        customer.setDeliveryTerm(company.getDeliveryTerm());
        customer.setDeliveryWay(company.getDeliveryWay());
        customer.setNumber(number);
        customer.setName(name);
        customer.setEMail(email);
        customer.setInvoiceCurrency(company.getCurrency());
        customer.setPaymentTerm(company.getPaymentTerm());
        return customer;
    }

    private static SSSupplier supplier(SSNewCompany company, String number, String name, String email) {
        SSSupplier supplier = new SSSupplier();
        supplier.setOurContact(company.getContactPerson());
        supplier.setCurrency(company.getCurrency());
        supplier.setPaymentTerm(company.getPaymentTerm());
        supplier.setDeliveryTerm(company.getDeliveryTerm());
        supplier.setDeliveryWay(company.getDeliveryWay());
        supplier.setNumber(number);
        supplier.setName(name);
        supplier.setEMail(email);
        return supplier;
    }

    private static SSProduct product(SSNewCompany company, String number, String description, String price) {
        SSProduct product = new SSProduct();
        product.setUnit(company.getStandardUnit());
        product.setDefaultAccount(SSDefaultAccount.Purchases, company.getDefaultAccount(SSDefaultAccount.Purchases));
        product.setNumber(number);
        product.setDescription(description);
        product.setSellingPrice(new BigDecimal(price));
        product.setTaxCode(SSTaxCode.TAXRATE_1);
        product.setDefaultAccount(SSDefaultAccount.Sales, 3001);
        return product;
    }

    static SSInvoice invoice(SSNewCompany company, int number, SSCustomer customer, SSProduct product,
            LocalDate date, int quantity) {
        SSInvoice invoice = new SSInvoice();
        invoice.setCurrency(company.getCurrency());
        invoice.setPaymentTerm(company.getPaymentTerm());
        invoice.setDeliveryTerm(company.getDeliveryTerm());
        invoice.setDeliveryWay(company.getDeliveryWay());
        invoice.setNumber(number);
        invoice.setCustomer(customer);
        invoice.setLocalDate(date);
        invoice.setLocalDueDate(date.plusDays(30));
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.setDefaultAccounts(company.getDefaultAccounts());
        SSSaleRow row = new SSSaleRow(product);
        row.setQuantity(quantity);
        row.setAccountNr(3001);
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.setRows(List.of(row));
        return invoice;
    }

    static SSInpayment inpayment(SSNewCompany company, SSInvoice paid, BigDecimal amount) {
        SSInpayment inpayment = new SSInpayment();
        inpayment.setDefaultAccounts(new LinkedHashMap<>(company.getDefaultAccounts()));
        inpayment.setNumber(1);
        inpayment.setLocalDate(LocalDate.of(2026, 8, 5));
        inpayment.setText("Betalning faktura " + paid.getNumber());
        // Match the legacy empty difference snapshot after invoice journal voucher 3,
        // without deriving its number/date from SSDB's current year.
        SSVoucher difference = new SSVoucher(4, LocalDate.of(2026, 8, 3));
        inpayment.setDifference(difference);
        SSInpaymentRow row = new SSInpaymentRow();
        row.setInvoiceNr(paid.getNumber());
        row.setInvoiceCurrency(paid.getCurrency());
        row.setInvoiceCurrencyRate(paid.getCurrencyRate());
        row.setValue(amount);
        row.setCurrencyRate(BigDecimal.ONE);
        inpayment.setRows(List.of(row));
        return inpayment;
    }

    static SSVoucher voucher(SSNewAccountingYear year, int number, LocalDate date, String description,
            VoucherEntry... entries) {
        SSVoucher voucher = new SSVoucher(number, date);
        voucher.setDescription(description);
        for (VoucherEntry entry : entries) {
            SSAccount account = year.getAccountPlan().getAccount(entry.account());
            if (account == null) {
                throw new IllegalStateException("Demo account is unavailable: " + entry.account());
            }
            voucher.addVoucherRow(new SSVoucherRow(account, entry.debit(), entry.credit()));
        }
        return voucher;
    }

    private static VoucherEntry debit(int account, String amount) {
        return new VoucherEntry(account, new BigDecimal(amount), null);
    }

    private static VoucherEntry credit(int account, String amount) {
        return new VoucherEntry(account, null, new BigDecimal(amount));
    }

    private record VoucherEntry(int account, BigDecimal debit, BigDecimal credit) {}
}
