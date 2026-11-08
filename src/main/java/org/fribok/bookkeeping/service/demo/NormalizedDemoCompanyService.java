package org.fribok.bookkeeping.service.demo;

import org.fribok.bookkeeping.dataformat.NormalizedAccountPlanCatalogStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInpaymentStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalanceService;
import org.fribok.bookkeeping.service.year.AccountingYearService;
import se.swedsoft.bookkeeping.calc.math.SSSaleMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Atomically replaces recognized demos using only explicit normalized stores and calculations. */
public final class NormalizedDemoCompanyService {
    private final Connection connection;

    /** Uses the caller-owned, non-auto-commit normalized connection. */
    public NormalizedDemoCompanyService(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Replaces only companies with a recognized name AND corporate ID, in one transaction.
     * No commits occur inside the stores; failures roll back both removals and the replacement.
     * The caller owns the connection, which must have auto-commit disabled and no pending work.
     * @return persisted two-year demo and counts
     * @throws SQLException if storage or the V19 catalogue is unavailable
     */
    public DemoCompanyResult recreate() throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("Demo recreation requires auto-commit disabled");
        }
        try (var store = new NormalizedAccountingStore(connection, java.time.Clock.systemUTC())) {
            List<SSNewCompany> companies = store.getCompanies();
            List<SSNewCompany> demos = companies.stream().filter(DemoCompanyService::isRecognizedDemo).toList();
            SSAccountPlan template = new NormalizedAccountPlanCatalogStore(connection).getAccountPlans().stream()
                    .filter(plan -> "BAS 2026 - Aktiebolag".equals(plan.getName())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("BAS 2026 - Aktiebolag is unavailable"));
            if (companies.stream().filter(company -> !DemoCompanyService.isRecognizedDemo(company))
                    .anyMatch(company -> DemoCompanyService.NAME.equals(company.getName()))) {
                throw new IllegalArgumentException("Company name already exists");
            }
            SSNewCompany company = DemoCompanyData.company();
            company.setId(Math.addExact(companies.stream().mapToInt(SSNewCompany::getId).max().orElse(-1), 1));
            int firstYearId = store.nextAccountingYearId();
            int currentYearId = Math.addExact(firstYearId, 1);
            // Prepare both independent plans and all required seed accounts before removing anything.
            SSNewAccountingYear first = AccountingYearService.prepare(DemoCompanyService.FIRST_FROM,
                    DemoCompanyService.FIRST_TO, template, List.of());
            SSNewAccountingYear current = AccountingYearService.prepare(DemoCompanyService.CURRENT_FROM,
                    DemoCompanyService.CURRENT_TO, template, List.of());
            first.setId(firstYearId);
            current.setId(currentYearId);
            first.setVouchers(new ArrayList<>());
            current.setVouchers(new ArrayList<>());
            List<SSVoucher> firstVouchers = DemoCompanyData.firstVouchers(first);
            List<SSVoucher> currentVouchers = DemoCompanyData.currentVouchers(current);
            for (int account : List.of(1510, 1930, 2611, 2621, 2631, 3001)) {
                if (current.getAccountPlan().getAccount(account) == null) {
                    throw new IllegalStateException("Demo account is unavailable: " + account);
                }
            }

            for (SSNewCompany demo : demos) {
                store.deleteCompany(demo);
            }
            store.addCompany(company);
            store.setCurrentCompany(company);
            store.addAccountingYear(first);
            store.addAccountingYear(current);
            store.setCurrentYear(first);
            for (SSVoucher voucher : firstVouchers) {
                store.addVoucher(voucher);
            }
            var opening = new OpeningBalanceService(store).carryForward(first, current, false);
            var balances = new HashMap<se.swedsoft.bookkeeping.data.SSAccount, java.math.BigDecimal>();
            for (var entry : opening.balances()) {
                balances.put(current.getAccountPlan().getAccount(entry.account()), entry.amount());
            }
            current.setInBalance(balances);
            store.replaceOpeningBalances(current);
            store.setCurrentYear(current);
            for (SSVoucher voucher : currentVouchers) {
                store.addVoucher(voucher);
            }

            var registers = new NormalizedRegisterStore(connection);
            var customers = DemoCompanyData.customers(company);
            var products = DemoCompanyData.products(company);
            for (var customer : customers) {
                registers.addCustomer(company.getId(), customer);
            }
            for (var supplier : DemoCompanyData.suppliers(company)) {
                registers.addSupplier(company.getId(), supplier);
            }
            for (var product : products) {
                registers.addProduct(company.getId(), product);
            }

            var invoices = new NormalizedInvoiceStore(connection);
            SSInvoice paid = DemoCompanyData.invoice(company, invoices.nextInvoiceNumber(company.getId()),
                    customers.get(0), products.get(0), LocalDate.of(2026, 7, 6), 10);
            SSVoucher paidVoucher = paid.generateVoucher(current.getAccountPlan(), company.isRoundingOff(),
                    List.of(), List.of());
            paid.setEntered();
            invoices.addInvoice(company.getId(), paid);
            SSInvoice open = DemoCompanyData.invoice(company, invoices.nextInvoiceNumber(company.getId()),
                    customers.get(1), products.get(1), LocalDate.of(2026, 8, 3), 1);
            SSVoucher openVoucher = open.generateVoucher(current.getAccountPlan(), company.isRoundingOff(),
                    List.of(), List.of());
            open.setEntered();
            invoices.addInvoice(company.getId(), open);
            store.addVoucher(journal(current.getAccountPlan(), 3, "Fakturajournal nr 1",
                    LocalDate.of(2026, 8, 3), paidVoucher, openVoucher));
            registers.bumpAutoIncrement(company.getId(), "invoicejournal");

            var inpayment = DemoCompanyData.inpayment(company, paid,
                    SSSaleMath.getTotalSum(paid, company.isRoundingOff()));
            SSVoucher paymentVoucher = inpayment.generateVoucher(current.getAccountPlan());
            inpayment.setEntered();
            new NormalizedInpaymentStore(connection).addInpayment(company.getId(), inpayment);
            store.addVoucher(journal(current.getAccountPlan(), 4, "Inbetalningsjournal nr 1",
                    inpayment.getLocalDate(), paymentVoucher));
            registers.bumpAutoIncrement(company.getId(), "inpaymentjournal");
            SSNewCompany saved = store.getCompanies().stream()
                    .filter(value -> value.getId().equals(company.getId())).findFirst().orElseThrow();
            connection.commit();
            return new DemoCompanyResult(saved, demos.size(), 2,
                    firstVouchers.size() + currentVouchers.size() + 2, 2, 2, 2, 2);
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        }
    }

    private static SSVoucher journal(SSAccountPlan plan, int number, String description, LocalDate date,
            SSVoucher... sources) {
        SSVoucher journal = new SSVoucher(number, date);
        journal.setDescription(description);
        for (SSVoucher source : sources) {
            for (SSVoucherRow row : source.getRows()) {
                journal.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return SSVoucherMath.compress(journal, plan.getAccounts(), List.of(), List.of());
    }
}
