package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.sf.jasperreports.engine.JasperExportManager;
import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.DataMigrationService;
import org.fribok.bookkeeping.service.backup.BackupDetails;
import org.fribok.bookkeeping.service.backup.BackupRestorePlan;
import org.fribok.bookkeeping.service.backup.BackupService;
import org.fribok.bookkeeping.service.backup.BackupVerification;
import org.fribok.bookkeeping.service.company.CompanyService;
import org.fribok.bookkeeping.service.creditinvoice.CreditInvoiceJournalPlan;
import org.fribok.bookkeeping.service.creditinvoice.CreditInvoiceJournalResult;
import org.fribok.bookkeeping.service.creditinvoice.CreditInvoiceService;
import org.fribok.bookkeeping.service.customer.CustomerService;
import org.fribok.bookkeeping.service.customer.CustomerValidationIssue;
import org.fribok.bookkeeping.service.customer.CustomerValidationResult;
import org.fribok.bookkeeping.service.demo.DemoCompanyResult;
import org.fribok.bookkeeping.service.demo.DemoCompanyService;
import org.fribok.bookkeeping.service.inpayment.InpaymentJournalPlan;
import org.fribok.bookkeeping.service.inpayment.InpaymentJournalResult;
import org.fribok.bookkeeping.service.inpayment.InpaymentService;
import org.fribok.bookkeeping.service.inpayment.InpaymentValidationIssue;
import org.fribok.bookkeeping.service.inpayment.InpaymentValidationResult;
import org.fribok.bookkeeping.service.invoice.InvoiceJournalPlan;
import org.fribok.bookkeeping.service.invoice.InvoiceJournalResult;
import org.fribok.bookkeeping.service.invoice.InvoiceService;
import org.fribok.bookkeeping.service.invoice.InvoiceValidationIssue;
import org.fribok.bookkeeping.service.invoice.InvoiceValidationResult;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalancePlan;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalanceService;
import org.fribok.bookkeeping.service.outpayment.OutpaymentJournalPlan;
import org.fribok.bookkeeping.service.outpayment.OutpaymentJournalResult;
import org.fribok.bookkeeping.service.outpayment.OutpaymentService;
import org.fribok.bookkeeping.service.outpayment.OutpaymentValidationIssue;
import org.fribok.bookkeeping.service.outpayment.OutpaymentValidationResult;
import org.fribok.bookkeeping.service.product.ProductService;
import org.fribok.bookkeeping.service.report.FinancialReportService;
import org.fribok.bookkeeping.service.sie.SieExportService;
import org.fribok.bookkeeping.service.sie.SieImportPlan;
import org.fribok.bookkeeping.service.sie.SieImportService;
import org.fribok.bookkeeping.service.supplier.SupplierService;
import org.fribok.bookkeeping.service.supplier.SupplierValidationIssue;
import org.fribok.bookkeeping.service.supplier.SupplierValidationResult;
import org.fribok.bookkeeping.service.supplierinvoice.SupplierInvoiceJournalPlan;
import org.fribok.bookkeeping.service.supplierinvoice.SupplierInvoiceService;
import org.fribok.bookkeeping.service.supplierinvoice.SupplierInvoiceValidationIssue;
import org.fribok.bookkeeping.service.supplierinvoice.SupplierInvoiceValidationResult;
import org.fribok.bookkeeping.service.suppliercreditinvoice.SupplierCreditInvoiceJournalPlan;
import org.fribok.bookkeeping.service.suppliercreditinvoice.SupplierCreditInvoiceService;
import org.fribok.bookkeeping.service.product.ProductValidationIssue;
import org.fribok.bookkeeping.service.product.ProductValidationResult;
import org.fribok.bookkeeping.service.vat.VatService;
import org.fribok.bookkeeping.service.vat.VatSettlementPlan;
import org.fribok.bookkeeping.service.voucher.VoucherService;
import org.fribok.bookkeeping.service.year.AccountingYearService;
import org.fribok.bookkeeping.service.voucher.VoucherValidationIssue;
import org.fribok.bookkeeping.service.voucher.VoucherValidationResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import se.swedsoft.bookkeeping.calc.SSOCRNumber;
import se.swedsoft.bookkeeping.calc.math.SSSaleMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSOrder;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.system.SSDBConfig;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEType;
import se.swedsoft.bookkeeping.print.SSPrinter;
import se.swedsoft.bookkeeping.print.report.SSAccountsPayablePrinter;
import se.swedsoft.bookkeeping.print.report.SSAccountsRecievablePrinter;
import se.swedsoft.bookkeeping.print.report.SSAccountPlanPrinter;
import se.swedsoft.bookkeeping.print.report.SSBalancePrinter;
import se.swedsoft.bookkeeping.print.report.SSCreditInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSCustomerclaimPrinter;
import se.swedsoft.bookkeeping.print.report.SSCustomerListPrinter;
import se.swedsoft.bookkeeping.print.report.SSCustomerRevenuePrinter;
import se.swedsoft.bookkeeping.print.report.SSInpaymentListPrinter;
import se.swedsoft.bookkeeping.print.report.SSInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSMainBookPrinter;
import se.swedsoft.bookkeeping.print.report.SSOutpaymentListPrinter;
import se.swedsoft.bookkeeping.print.report.SSProductListPrinter;
import se.swedsoft.bookkeeping.print.report.SSProductRevenuePrinter;
import se.swedsoft.bookkeeping.print.report.SSResultPrinter;
import se.swedsoft.bookkeeping.print.report.SSSaleReportPrinter;
import se.swedsoft.bookkeeping.print.report.SSSalevaluesPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierCreditInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierInvoiceListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierRevenuePrinter;
import se.swedsoft.bookkeeping.print.report.SSVATControl2015Printer;
import se.swedsoft.bookkeeping.print.report.SSVATReport2015Printer;
import se.swedsoft.bookkeeping.print.report.SSVoucherListPrinter;
import se.swedsoft.bookkeeping.print.report.SSSupplierdebtPrinter;
import se.swedsoft.bookkeeping.print.report.SSVoucherPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSInpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSOutpaymentjournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSSupplierInvoicejournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSSuppliercreditinvoicejournalPrinter;
import se.swedsoft.bookkeeping.print.report.journals.SSInvoicejournalPrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSCreditinvoicePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSDeliverynotePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSOCRInvoicePrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSPickingslipPrinter;
import se.swedsoft.bookkeeping.print.report.sales.SSReminderPrinter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Headless command-line interface for Bokfri. */
@Command(mixinStandardHelpOptions = true, name = "bokfri",
        description = "Inspect and configure Bokfri without starting the Swing interface.",
        subcommands = {
            BokfriCli.VersionCommand.class,
            BokfriCli.StatusCommand.class,
            BokfriCli.DatabaseCommand.class,
            BokfriCli.CompanyCommand.class,
            BokfriCli.DemoCommand.class,
            BokfriCli.AccountPlanCommand.class,
            BokfriCli.YearCommand.class,
            BokfriCli.AccountCommand.class,
            BokfriCli.TrialBalanceCommand.class,
            BokfriCli.BalanceSheetCommand.class,
            BokfriCli.IncomeStatementCommand.class,
            BokfriCli.GeneralLedgerCommand.class,
            BokfriCli.AccountsReceivableCommand.class,
            BokfriCli.CustomerClaimsCommand.class,
            BokfriCli.AccountsPayableCommand.class,
            BokfriCli.SupplierDebtsCommand.class,
            BokfriCli.CustomerRevenueCommand.class,
            BokfriCli.ProductRevenueCommand.class,
            BokfriCli.SaleValuesCommand.class,
            BokfriCli.SupplierRevenueCommand.class,
            BokfriCli.SalesReportCommand.class,
            BokfriCli.OpeningBalanceCommand.class,
            BokfriCli.BackupCommand.class,
            BokfriCli.SieCommand.class,
            BokfriCli.CustomerCommand.class,
            BokfriCli.ProductCommand.class,
            BokfriCli.SupplierCommand.class,
            BokfriCli.SupplierInvoiceCommand.class,
            BokfriCli.SupplierCreditInvoiceCommand.class,
            BokfriCli.InvoiceCommand.class,
            BokfriCli.CreditInvoiceCommand.class,
            BokfriCli.InpaymentCommand.class,
            BokfriCli.OutpaymentCommand.class,
            BokfriCli.VatCommand.class,
            BokfriCli.VoucherCommand.class
        })
public class BokfriCli implements Runnable {
    enum OutputFormat { text, json }

    @Option(names = "--config", scope = CommandLine.ScopeType.INHERIT, hidden = true,
            description = "Override the shared GUI/CLI selection file")
    java.nio.file.Path configPath;

    @Option(names = "--data-dir", scope = CommandLine.ScopeType.INHERIT,
            description = "Override the Bokfri data directory")
    java.nio.file.Path dataDir;

    @Option(names = "--company-id", scope = CommandLine.ScopeType.INHERIT,
            description = "Override the company id")
    Integer companyId;

    @Option(names = "--year-id", scope = CommandLine.ScopeType.INHERIT,
            description = "Override the accounting year id")
    Integer yearId;

    @Option(names = "--format", scope = CommandLine.ScopeType.INHERIT, defaultValue = "text",
            description = "Output format: ${COMPLETION-CANDIDATES}")
    OutputFormat format;

    @Option(names = "--verbose", scope = CommandLine.ScopeType.INHERIT,
            description = "Print diagnostic details for unexpected errors")
    boolean verbose;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "A command is required");
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    File selectionFile() {
        return (configPath != null ? configPath
                : Path.get(Path.USER_CONF).toPath().resolve("database.config")).toFile();
    }

    ResolvedContext resolveContext(boolean requireCompany, boolean requireYear) {
        Integer selectedCompany = companyId != null
                ? companyId : SSDBConfig.getCompanyId(selectionFile());
        Integer selectedYear = yearId;
        if (selectedYear == null && selectedCompany != null) {
            selectedYear = SSDBConfig.getYearId(selectionFile(), selectedCompany);
            if (selectedYear == null && companyId == null) {
                selectedYear = SSDBConfig.getYearId(selectionFile());
            }
        }
        java.nio.file.Path selectedDataDir = (dataDir != null
                ? dataDir : Path.get(Path.USER_DATA).toPath()).toAbsolutePath().normalize();

        if (requireCompany && selectedCompany == null) {
            throw new CliException("COMPANY_REQUIRED",
                    "Select a company with 'company use' or provide --company-id");
        }
        if (requireYear && selectedYear == null) {
            throw new CliException("YEAR_REQUIRED",
                    "Select an accounting year with 'year use' or provide --year-id");
        }
        return new ResolvedContext(selectedDataDir, selectedCompany, selectedYear);
    }

    BokfriRuntime openRuntime(java.nio.file.Path selectedDataDir) throws Exception {
        return BokfriRuntime.open(selectedDataDir);
    }

    static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    void output(Object value, String text) {
        if (format == OutputFormat.json) {
            try {
                spec.commandLine().getOut().println(jsonMapper().writeValueAsString(value));
            } catch (JsonProcessingException exception) {
                throw new CliException("OUTPUT_FAILED", "Could not encode JSON output", exception);
            }
        } else {
            spec.commandLine().getOut().println(text);
        }
    }

    record ResolvedContext(java.nio.file.Path dataDir, Integer companyId, Integer yearId) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataDir", dataDir.toString());
            result.put("companyId", companyId);
            result.put("yearId", yearId);
            return result;
        }
    }

    abstract static class CliCommand {
        @CommandLine.ParentCommand
        BokfriCli parent;
    }

    @Command(mixinStandardHelpOptions = true, name = "version", description = "Print version and build information")
    static class VersionCommand extends CliCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            Map<String, Object> result = Map.of(
                    "title", Version.APP_TITLE,
                    "version", Version.APP_VERSION,
                    "build", Version.APP_BUILD);
            parent.output(result, Version.APP_TITLE + " " + Version.APP_VERSION
                    + " (built " + Version.APP_BUILD + ")");
            return 0;
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "status",
            description = "Show Bokfri's database and current selection")
    static class StatusCommand extends CliCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            ResolvedContext context = parent.resolveContext(false, false);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("application", Map.of("version", Version.APP_VERSION, "build", Version.APP_BUILD));
            result.put("paths", Map.of("dataDir", context.dataDir().toString(),
                    "selectionConfig", parent.selectionFile().toString(),
                    "cliLog", CliLog.file(context.dataDir()).toString()));

            String state = "ready";
            String problem = null;
            String databaseText = "unavailable";
            Map<String, Object> companyResult = null;
            Map<String, Object> yearResult = null;
            try {
                DataFormatManager.DataFormatStatus database = DataFormatManager.inspect(context.dataDir());
                result.put("database", databaseStatus(database));
                databaseText = database.exists()
                        ? "format " + database.format()
                                + (database.migrationRequired() ? " (migration required)" : "")
                        : "not created";
                if (!database.exists()) {
                    state = "incomplete";
                    problem = "Database has not been created";
                } else if (database.migrationRequired()) {
                    state = "broken";
                    problem = "Database migration is required";
                } else if (context.companyId() == null) {
                    state = "incomplete";
                    problem = "No company is selected";
                } else {
                    try (BokfriRuntime runtime = parent.openRuntime(context.dataDir())) {
                        SSNewCompany company = runtime.selectCompany(context.companyId());
                        companyResult = Map.of("id", company.getId(), "name", company.getName());
                        if (context.yearId() == null) {
                            state = "incomplete";
                            problem = "No accounting year is selected";
                        } else {
                            SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                            yearResult = Map.of("id", year.getId(), "from", year.getLocalFrom(),
                                    "to", year.getLocalTo());
                        }
                    }
                }
            } catch (CliException exception) {
                state = "broken";
                problem = exception.getMessage();
            } catch (Exception exception) {
                state = "broken";
                problem = databaseFailure(exception).getMessage();
            }

            Map<String, Object> selection = new LinkedHashMap<>();
            selection.put("companyId", context.companyId());
            selection.put("company", companyResult);
            selection.put("yearId", context.yearId());
            selection.put("year", yearResult);
            result.put("selection", selection);
            result.put("status", state);
            if (problem != null) {
                result.put("problem", problem);
            }

            String text = "Bokfri " + Version.APP_VERSION + " (built " + Version.APP_BUILD + ")"
                    + "\nData directory: " + context.dataDir()
                    + "\nCLI log: " + CliLog.file(context.dataDir())
                    + "\nDatabase: " + databaseText
                    + "\nCompany: " + selectionText(context.companyId(), companyResult)
                    + "\nAccounting year: " + selectionText(context.yearId(), yearResult)
                    + "\nStatus: " + state
                    + (problem == null ? "" : "\nProblem: " + problem);
            parent.output(result, text);
            return "ready".equals(state) ? 0 : 1;
        }

        private static Map<String, Object> databaseStatus(DataFormatManager.DataFormatStatus status) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", status.exists());
            result.put("format", status.format());
            result.put("supportedFormat", status.supportedFormat());
            result.put("migrationRequired", status.migrationRequired());
            return result;
        }

        private static String selectionText(Integer id, Map<String, Object> resolved) {
            if (id == null) {
                return "not selected";
            }
            if (resolved == null) {
                return id + " - not found";
            }
            if (resolved.containsKey("name")) {
                return id + " - " + resolved.get("name");
            }
            return id + " - " + resolved.get("from") + " - " + resolved.get("to");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "database", description = "Inspect and migrate the database format",
            subcommands = {DatabaseStatus.class, DatabaseMigrate.class})
    static class DatabaseCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A database command is required");
        }
    }

    abstract static class DatabaseSubcommand implements Callable<Integer> {
        @CommandLine.ParentCommand DatabaseCommand command;
        BokfriCli root() { return command.parent; }
    }

    @Command(mixinStandardHelpOptions = true, name = "status", description = "Show the database format and migration status")
    static class DatabaseStatus extends DatabaseSubcommand {
        @Override public Integer call() {
            BokfriCli root = root();
            java.nio.file.Path data = root.resolveContext(false, false).dataDir();
            try {
                DataFormatManager.DataFormatStatus status = DataFormatManager.inspect(data);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("exists", status.exists());
                result.put("format", status.format());
                result.put("supportedFormat", status.supportedFormat());
                result.put("migrationRequired", status.migrationRequired());
                String text = "Database: " + (status.exists() ? "exists" : "not created")
                        + "\nDatabase format: " + status.format()
                        + "\nSupported format: " + status.supportedFormat()
                        + "\nMigration required: " + (status.migrationRequired() ? "yes" : "no");
                root.output(result, text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "migrate", description = "Back up and migrate an older database")
    static class DatabaseMigrate extends DatabaseSubcommand {
        @Override public Integer call() {
            BokfriCli root = root();
            java.nio.file.Path data = root.resolveContext(false, false).dataDir();
            try {
                DataFormatManager.DataFormatStatus before = DataFormatManager.inspect(data);
                if (!before.exists()) {
                    throw new CliException("DATABASE_NOT_FOUND",
                            "No database exists in " + data);
                }
                if (!before.migrationRequired()) {
                    Map<String, Object> result = Map.of("migrated", false,
                            "format", before.format(), "supportedFormat", before.supportedFormat());
                    root.output(result, "Database already uses format " + before.format()
                            + "; no migration was needed");
                    return 0;
                }
                java.nio.file.Path backup = DataMigrationService.migrate(data);
                DataFormatManager.DataFormatStatus after = DataFormatManager.inspect(data);
                if (after.migrationRequired()
                        || after.format() != DataFormatManager.CURRENT_DATA_FORMAT_VERSION) {
                    throw new CliException("DATABASE_MIGRATION_FAILED",
                            "Database migration did not produce format "
                                    + DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("migrated", true);
                result.put("fromFormat", before.format());
                result.put("toFormat", after.format());
                result.put("backup", backup.toString());
                root.output(result, "Database migrated from format " + before.format()
                        + " to format " + after.format() + "\nBackup: " + backup);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "company", description = "Inspect, create, and select companies",
            subcommands = {CompanyList.class, CompanyUse.class, CompanyCreate.class,
                    CliInputSchemas.Company.class})
    static class CompanyCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A company command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "use", description = "Select the company shared with the graphical interface")
    static class CompanyUse implements Callable<Integer> {
        @CommandLine.ParentCommand CompanyCommand command;
        @Parameters(index = "0", description = "Company id") int id;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(id);
                Integer selectedYear = SSDBConfig.getYearId(root.selectionFile(), id);
                SSDBConfig.setCompanyId(root.selectionFile(), id);
                SSDBConfig.setYearId(root.selectionFile(), id, selectedYear);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", id);
                result.put("name", company.getName());
                result.put("yearId", selectedYear);
                root.output(result, "Current company is now " + id + " - " + company.getName()
                        + (selectedYear == null ? "" : " (year " + selectedYear + ")"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name="create",description="Create a company from JSON") static class CompanyCreate implements Callable<Integer>{@CommandLine.ParentCommand CompanyCommand command;@Option(names="--file",required=true)String file;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(false,false);CompanyInput in=readCompanyInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=new SSNewCompany();co.setName(normalized(in.getName()));co.setCorporateID(normalized(in.getCorporateId()));co.setVATNumber(normalized(in.getVatNumber()));co.setEMail(normalized(in.getEmail()));co.setPhone(normalized(in.getPhone()));co.setContactPerson(normalized(in.getContactPerson()));co.setBankGiroNumber(normalized(in.getBankgiro()));co.setVatPeriod(in.getVatPeriod());co.setReminderfee(in.getReminderFee());co.setDelayInterest(in.getDelayInterest());Map<SSDefaultAccount,Integer> defaultAccounts=new LinkedHashMap<>();for(SSDefaultAccount account:SSDefaultAccount.values()){defaultAccounts.put(account,account.getDefaultAccountNumber());}co.setDefaultAccounts(defaultAccounts);co.setCurrency(java.util.stream.Stream.concat(r.database().getCurrencies().stream(),se.swedsoft.bookkeeping.data.common.SSCurrency.getDefaultCurrencies().stream()).filter(x->in.getCurrency().equalsIgnoreCase(x.getName())).findFirst().orElseThrow(()->new CliException("COMPANY_CURRENCY_NOT_FOUND","No currency has code "+in.getCurrency())));co.setPaymentTerm(java.util.stream.Stream.concat(r.database().getPaymentTerms().stream(),se.swedsoft.bookkeeping.data.common.SSPaymentTerm.getDefaultPaymentTerms().stream()).filter(x->in.getPaymentTerms().equals(x.getName())).findFirst().orElseThrow(()->new CliException("COMPANY_PAYMENT_TERMS_NOT_FOUND","No payment terms have code "+in.getPaymentTerms())));co.setStandardUnit(java.util.stream.Stream.concat(r.database().getUnits().stream(),se.swedsoft.bookkeeping.data.common.SSUnit.getDefaultUnits().stream()).filter(x->in.getStandardUnit().equals(x.getName())).findFirst().orElseThrow(()->new CliException("COMPANY_UNIT_NOT_FOUND","No unit has code "+in.getStandardUnit())));new CompanyService(r.database()).create(co);root.output(Map.of("id",co.getId(),"name",co.getName()),"Created company "+co.getId()+" - "+co.getName());return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List companies")
    static class CompanyList implements Callable<Integer> {
        @CommandLine.ParentCommand CompanyCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                List<Map<String, Object>> companies = runtime.database().getCompanies().stream().map(company -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("selected", Objects.equals(context.companyId(), company.getId()));
                    item.put("id", company.getId());
                    item.put("name", company.getName());
                    item.put("corporateId", company.getCorporateID());
                    return item;
                }).toList();
                String text = selectionTable(companies, "No companies found",
                        left("Id", "id"), left("Name", "name"),
                        left("Corporate id", "corporateId"));
                root.output(Map.of("selection", context.asMap(), "companies", companies), text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "demo", description = "Manage the bundled demo company",
            subcommands = DemoRecreate.class)
    static class DemoCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A demo command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "recreate", description = "Replace the bundled demo company")
    static class DemoRecreate implements Callable<Integer> {
        @CommandLine.ParentCommand DemoCommand command;
        @Option(names = "--commit", description = "Apply the replacement") boolean commit;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                DemoCompanyService service = new DemoCompanyService(runtime.database());
                List<SSNewCompany> matches = service.findDemoCompanies();
                boolean selectedDemoWillBeRemoved = context.companyId() != null
                        && matches.stream().anyMatch(company -> company.getId().equals(context.companyId()));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("commit", commit);
                result.put("remove", matches.stream().map(company -> Map.of(
                        "id", company.getId(), "name", company.getName(),
                        "corporateId", company.getCorporateID())).toList());
                result.put("create", Map.of("name", DemoCompanyService.NAME,
                        "corporateId", DemoCompanyService.CORPORATE_ID,
                        "accountingYears", 2));
                if (!commit) {
                    root.output(result, "Would remove " + matches.size()
                            + " recognized demo company/companies and create "
                            + DemoCompanyService.NAME + ". Use --commit to apply.");
                    return 0;
                }
                DemoCompanyResult created = service.recreate();
                Integer selectedYearId = runtime.database().getCurrentYear() == null
                        ? null : runtime.database().getCurrentYear().getId();
                if (selectedDemoWillBeRemoved && root.companyId == null) {
                    SSDBConfig.setCompanyId(root.selectionFile(), created.company().getId());
                    SSDBConfig.setYearId(root.selectionFile(), created.company().getId(), selectedYearId);
                }
                result.put("companyId", created.company().getId());
                result.put("yearId", selectedYearId);
                result.put("selectionUpdated", selectedDemoWillBeRemoved && root.companyId == null);
                result.put("removedCompanies", created.removedCompanies());
                result.put("vouchers", created.vouchers());
                result.put("invoices", created.invoices());
                root.output(result, "Recreated " + created.company().getName() + " (company "
                        + created.company().getId() + ") with " + created.accountingYears()
                        + " accounting years");
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name="account-plan",description="Inspect available account plans",subcommands={AccountPlanList.class,AccountPlanPdf.class})
    static class AccountPlanCommand extends CliCommand implements Runnable{@CommandLine.Spec CommandLine.Model.CommandSpec spec;public void run(){throw new CommandLine.ParameterException(spec.commandLine(),"An account-plan command is required");}}
    @Command(mixinStandardHelpOptions = true, name="list") static class AccountPlanList implements Callable<Integer>{@CommandLine.ParentCommand AccountPlanCommand command;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(false,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){List<Map<String,Object>> plans=r.database().getAccountPlans().stream().map(p->Map.<String,Object>of("id",p.getId(),"name",p.getName(),"assessmentYear",p.getAssessementYear()==null?"":p.getAssessementYear(),"accountCount",p.getAccounts().size())).toList();root.output(Map.of("accountPlans",plans,"count",plans.size()),table(plans,"No account plans found",right("Id","id"),left("Name","name"),right("Assessment year","assessmentYear"),right("Accounts","accountCount")));return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name="pdf", description="Generate the selected accounting year's account plan as PDF")
    static class AccountPlanPdf implements Callable<Integer> {
        @CommandLine.ParentCommand AccountPlanCommand command;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSAccountPlanPrinter(y.getAccountPlan()),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("accountPlan",y.getAccountPlan().getName());x.put("selection",selectedContext(c,co,y));root.output(x,"Created account-plan PDF "+pdf);return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "year", description = "Inspect, create, and select accounting years",
            subcommands = {YearList.class, YearUse.class, YearCreate.class,
                    CliInputSchemas.Year.class})
    static class YearCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A year command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "use", description = "Select the accounting year shared with the graphical interface")
    static class YearUse implements Callable<Integer> {
        @CommandLine.ParentCommand YearCommand command;
        @Parameters(index = "0", description = "Accounting year id") int id;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, id);
                SSDBConfig.setYearId(root.selectionFile(), company.getId(), id);
                root.output(Map.of("id", id, "companyId", company.getId(),
                                "from", year.getLocalFrom(), "to", year.getLocalTo()),
                        "Current accounting year is now " + year.getLocalFrom()
                                + " - " + year.getLocalTo());
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name="create",description="Create an accounting year from JSON") static class YearCreate implements Callable<Integer>{@CommandLine.ParentCommand YearCommand command;@Option(names="--file",required=true)String file;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);AccountingYearInput in=readAccountingYearInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());AccountingYearService s=new AccountingYearService(r.database());List<SSAccountPlan> matches=s.accountPlans().stream().filter(p->in.getAccountPlanId()!=null&&in.getAccountPlanId().equals(p.getId())||in.getAccountPlanName()!=null&&in.getAccountPlanName().equals(p.getName())).toList();if(matches.size()!=1)throw new CliException("ACCOUNT_PLAN_NOT_FOUND","Account plan must match exactly one plan");SSNewAccountingYear y=s.create(in.getFrom(),in.getTo(),matches.get(0));Map<String,Object>x=new LinkedHashMap<>();x.put("id",y.getId());x.put("from",y.getLocalFrom());x.put("to",y.getLocalTo());x.put("accountPlan",y.getAccountPlan().getName());x.put("companyId",co.getId());root.output(x,"Created accounting year "+y.toRenderString());return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List accounting years for the selected company")
    static class YearList implements Callable<Integer> {
        @CommandLine.ParentCommand YearCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                List<Map<String, Object>> years = runtime.database().getYearsForCompany(company).stream()
                        .sorted(Comparator.comparing(SSNewAccountingYear::getLocalFrom)
                                .thenComparing(SSNewAccountingYear::getLocalTo)
                                .thenComparing(SSNewAccountingYear::getId)
                                .reversed())
                        .map(year -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("selected", Objects.equals(context.yearId(), year.getId()));
                            item.put("id", year.getId());
                            item.put("from", year.getLocalFrom().toString());
                            item.put("to", year.getLocalTo().toString());
                            return item;
                        }).toList();
                String text = selectionTable(years, "No accounting years found",
                        left("Id", "id"), left("From", "from"), left("To", "to"));
                Map<String, Object> selected = context.asMap();
                selected.put("companyName", company.getName());
                root.output(Map.of("selection", selected, "years", years), text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "account", description = "Inspect accounts",
            subcommands = {AccountList.class, AccountBalanceCommand.class})
    static class AccountCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An account command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List accounts for the selected year")
    static class AccountList implements Callable<Integer> {
        @CommandLine.ParentCommand AccountCommand command;
        @Option(names = "--filter", description = "Filter by account number or description")
        String filter;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                String normalizedFilter = filter == null ? null : filter.strip().toLowerCase(Locale.ROOT);
                List<Map<String, Object>> accounts = runtime.database().getAccounts().stream()
                        .filter(account -> normalizedFilter == null || normalizedFilter.isEmpty()
                                || Integer.toString(account.getNumber()).contains(normalizedFilter)
                                || Objects.toString(account.getDescription(), "")
                                        .toLowerCase(Locale.ROOT).contains(normalizedFilter))
                        .map(account -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("number", account.getNumber());
                            item.put("description", account.getDescription());
                            item.put("active", account.isActive());
                            item.put("balanceAccount", se.swedsoft.bookkeeping.calc.math.SSAccountMath
                                    .isBalanceAccount(account, year));
                            return item;
                        }).toList();
                String text = table(accounts, filter == null || filter.isBlank()
                                ? "No accounts found" : "No accounts found for \"" + filter + "\"",
                        right("Number", "number"), left("Description", "description"));
                Map<String, Object> selected = context.asMap();
                selected.put("companyName", company.getName());
                selected.put("yearFrom", year.getLocalFrom().toString());
                selected.put("yearTo", year.getLocalTo().toString());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selected);
                result.put("filter", filter);
                result.put("accounts", accounts);
                root.output(result, text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "trial-balance", description = "Show account opening, movement, and closing balances")
    static class TrialBalanceCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        public Integer call() {
            return withReport(root, (service, context, year) -> {
                ReportPeriod period = reportPeriod(year, from, to);
                var report = service.trialBalance(period.from(), period.to());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("from", period.from());
                result.put("to", period.to());
                result.put("rows", trialBalanceRows(report.rows()));
                result.put("openingTotal", money(report.openingTotal()));
                result.put("debitTotal", money(report.debitTotal()));
                result.put("creditTotal", money(report.creditTotal()));
                result.put("closingTotal", money(report.closingTotal()));
                result.put("difference", money(report.debitTotal().subtract(report.creditTotal())));
                result.put("selection", context);
                return result;
            }, BokfriCli::trialBalanceText);
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "accounts-receivable",
            description = "Generate the customer accounts-receivable report")
    static class AccountsReceivableCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--date", required = true) java.time.LocalDate date;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSAccountsRecievablePrinter(date), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", date);
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created accounts-receivable PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "customer-claims",
            description = "Generate the outstanding customer-claims report")
    static class CustomerClaimsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--date", required = true) java.time.LocalDate date;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSCustomerclaimPrinter(date), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", date);
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created customer-claims PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "accounts-payable",
            description = "Generate the supplier accounts-payable report")
    static class AccountsPayableCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--date", required = true) java.time.LocalDate date;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSAccountsPayablePrinter(date), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", date); result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created accounts-payable PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "supplier-debts",
            description = "Generate the outstanding supplier-debts report")
    static class SupplierDebtsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--date", required = true) java.time.LocalDate date;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSSupplierdebtPrinter(date), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", date); result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created supplier-debts PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "customer-revenue",
            description = "Generate customer revenue for a period")
    static class CustomerRevenueCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names="--from",required=true) java.time.LocalDate from;
        @Option(names="--to",required=true) java.time.LocalDate to;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSCustomerRevenuePrinter(new java.util.ArrayList<>(r.database().getCustomers()),from,to),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("from",from);x.put("to",to);x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("selection",selectedContext(c,co,y));root.output(x,"Created customer-revenue PDF "+pdf);return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "product-revenue",
            description = "Generate product revenue for a period")
    static class ProductRevenueCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names="--from",required=true) java.time.LocalDate from;
        @Option(names="--to",required=true) java.time.LocalDate to;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSProductRevenuePrinter(new java.util.ArrayList<>(r.database().getProducts()),from,to),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("from",from);x.put("to",to);x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("selection",selectedContext(c,co,y));root.output(x,"Created product-revenue PDF "+pdf);return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "sale-values",
            description = "Generate monthly sales values")
    static class SaleValuesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names="--from",required=true) java.time.LocalDate from;
        @Option(names="--to",required=true) java.time.LocalDate to;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSSalevaluesPrinter(from,to),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("from",from);x.put("to",to);x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("selection",selectedContext(c,co,y));root.output(x,"Created sale-values PDF "+pdf);return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "supplier-revenue",
            description = "Generate supplier revenue for a period")
    static class SupplierRevenueCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names="--from",required=true) java.time.LocalDate from;
        @Option(names="--to",required=true) java.time.LocalDate to;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSSupplierRevenuePrinter(new java.util.ArrayList<>(r.database().getSuppliers()),from,to),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("from",from);x.put("to",to);x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("selection",selectedContext(c,co,y));root.output(x,"Created supplier-revenue PDF "+pdf);return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "sales-report",
            description = "Generate the sales report for a period")
    static class SalesReportCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--sort", defaultValue = "Product") SSSaleReportPrinter.SortingMode sorting;
        @Option(names = "--descending") boolean descending;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;

        @Override public Integer call() {
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSSaleReportPrinter(from, to,
                        sorting, !descending), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("from", from);
                result.put("to", to);
                result.put("sorting", sorting.name());
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created sales report PDF " + pdf);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "balance-sheet", description = "Show balance accounts and current result at a date")
    static class BalanceSheetCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--date") java.time.LocalDate date;
        @Option(names = "--output", description = "Write the GUI balance report as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;
        public Integer call() {
            return withReport(root, (service, context, year) -> {
                java.time.LocalDate selectedDate = date == null ? year.getLocalTo() : date;
                var report = service.balanceSheet(selectedDate);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", selectedDate);
                result.put("rows", balanceSheetRows(report.rows()));
                result.put("assets", money(report.assets()));
                result.put("liabilitiesAndEquity", money(report.liabilitiesAndEquity()));
                result.put("currentResult", money(report.currentResult()));
                result.put("difference", money(report.difference()));
                result.put("selection", context);
                if (output != null) {
                    addPdf(result, exportPdf(new SSBalancePrinter(year, year.getLocalFrom(),
                            selectedDate), output, overwrite));
                }
                return result;
            }, BokfriCli::balanceSheetText);
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "income-statement", description = "Show income, expenses, and result for a period")
    static class IncomeStatementCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        @Option(names = "--output", description = "Write the GUI income statement as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;
        public Integer call() {
            return withReport(root, (service, context, year) -> {
                ReportPeriod period = reportPeriod(year, from, to);
                var report = service.incomeStatement(period.from(), period.to());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("from", period.from());
                result.put("to", period.to());
                result.put("rows", incomeStatementRows(report.rows()));
                result.put("result", money(report.result()));
                result.put("selection", context);
                if (output != null) {
                    addPdf(result, exportPdf(new SSResultPrinter(year, period.from(), period.to(),
                            false, false), output, overwrite));
                }
                return result;
            }, BokfriCli::incomeStatementText);
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "general-ledger", description = "Show transactions and running balance for an account")
    static class GeneralLedgerCommand implements Callable<Integer> {
        @CommandLine.ParentCommand BokfriCli root;
        @Option(names = "--account", required = true) int account;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        @Option(names = "--output", description = "Write the GUI general ledger as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;
        public Integer call() {
            return withReport(root, (service, context, year) -> {
                ReportPeriod period = reportPeriod(year, from, to);
                var report = service.accountLedger(account, period.from(), period.to());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("account", account);
                result.put("description", report.description());
                result.put("from", period.from());
                result.put("to", period.to());
                result.put("opening", money(report.opening()));
                result.put("rows", ledgerRows(report.rows()));
                result.put("closing", money(report.closing()));
                result.put("selection", context);
                if (output != null) {
                    SSAccount selectedAccount = year.getAccounts().stream()
                            .filter(item -> item.getNumber() == account).findFirst()
                            .orElseThrow(() -> new CliException("ACCOUNT_NOT_FOUND",
                                    "No account has number " + account));
                    addPdf(result, exportPdf(new SSMainBookPrinter(year, selectedAccount,
                            selectedAccount, period.from(), period.to(), null, null),
                            output, overwrite));
                }
                return result;
            }, BokfriCli::generalLedgerText);
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "balance", description = "Show an account balance at a date")
    static class AccountBalanceCommand implements Callable<Integer> {
        @CommandLine.ParentCommand AccountCommand command;
        @Parameters(index = "0") int account;
        @Option(names = "--date") java.time.LocalDate date;
        public Integer call() {
            BokfriCli root = command.parent;
            return withReport(root, (service, context, year) -> {
                java.time.LocalDate selectedDate = date == null ? year.getLocalTo() : date;
                var report = service.accountBalance(account, selectedDate);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("account", account);
                result.put("description", report.description());
                result.put("date", selectedDate);
                result.put("balance", money(report.balance()));
                result.put("selection", context);
                return result;
            }, BokfriCli::accountBalanceText);
        }
    }

    record ReportPeriod(java.time.LocalDate from, java.time.LocalDate to) {}

    private static ReportPeriod reportPeriod(SSNewAccountingYear year, java.time.LocalDate from,
            java.time.LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException(
                    "Provide both --from and --to, or omit both to report the full accounting year");
        }
        return from == null ? new ReportPeriod(year.getLocalFrom(), year.getLocalTo())
                : new ReportPeriod(from, to);
    }

    @FunctionalInterface
    interface ReportOperation {
        Map<String, Object> run(FinancialReportService service, Map<String, Object> context,
                SSNewAccountingYear year) throws Exception;
    }

    @FunctionalInterface
    interface ReportTextFormatter {
        String format(Map<String, Object> result);
    }

    private static int withReport(BokfriCli root, ReportOperation operation,
            ReportTextFormatter textFormatter) {
        ResolvedContext context = root.resolveContext(true, true);
        try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
            SSNewCompany company = runtime.selectCompany(context.companyId());
            SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
            Map<String, Object> result = operation.run(new FinancialReportService(year),
                    selectedContext(context, company, year), year);
            root.output(result, textFormatter.format(result));
            return 0;
        } catch (IllegalArgumentException exception) {
            throw new CliException("REPORT_INVALID", exception.getMessage(), exception);
        } catch (Exception exception) {
            throw databaseFailure(exception);
        }
    }

    private static String trialBalanceText(Map<String, Object> result) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(reportMaps(result));
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("description", "TOTAL");
        total.put("opening", result.get("openingTotal"));
        total.put("debit", result.get("debitTotal"));
        total.put("credit", result.get("creditTotal"));
        total.put("closing", result.get("closingTotal"));
        rows.add(total);
        return "Trial balance " + result.get("from") + " - " + result.get("to") + "\n"
                + table(rows, "No accounts found", right("Account", "account"),
                        left("Description", "description"), right("Opening", "opening"),
                        right("Debit", "debit"), right("Credit", "credit"),
                        right("Closing", "closing"))
                + "\nDifference: " + result.get("difference");
    }

    private static String balanceSheetText(Map<String, Object> result) {
        return "Balance sheet " + result.get("date") + "\n"
                + table(reportMaps(result), "No accounts found", right("Account", "account"),
                        left("Description", "description"), right("Balance", "balance"))
                + "\nAssets: " + result.get("assets")
                + "\nLiabilities and equity: " + result.get("liabilitiesAndEquity")
                + "\nCurrent result: " + result.get("currentResult")
                + "\nDifference: " + result.get("difference");
    }

    private static String incomeStatementText(Map<String, Object> result) {
        return "Income statement " + result.get("from") + " - " + result.get("to") + "\n"
                + table(reportMaps(result), "No accounts found", right("Account", "account"),
                        left("Description", "description"), right("Amount", "amount"))
                + "\nResult: " + result.get("result");
    }

    private static String generalLedgerText(Map<String, Object> result) {
        return result.get("account") + "  " + result.get("description")
                + "\nPeriod: " + result.get("from") + " - " + result.get("to")
                + "\nOpening: " + result.get("opening") + "\n"
                + table(reportMaps(result), "No transactions found",
                        right("Voucher", "voucherNumber"), left("Date", "date"),
                        left("Description", "description"), right("Debit", "debit"),
                        right("Credit", "credit"), right("Balance", "balance"))
                + "\nClosing: " + result.get("closing");
    }

    private static String accountBalanceText(Map<String, Object> result) {
        return table(List.of(result), "", right("Account", "account"),
                left("Description", "description"), left("Date", "date"),
                right("Balance", "balance"));
    }

    private static java.nio.file.Path exportPdf(SSPrinter printer, java.nio.file.Path output,
            boolean overwrite) throws Exception {
        java.nio.file.Path resolved = output.toAbsolutePath().normalize();
        if (Files.exists(resolved) && !overwrite) {
            throw new java.nio.file.FileAlreadyExistsException(resolved.toString());
        }
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        printer.generateReport();
        JasperExportManager.exportReportToPdfFile(printer.getPrinter(), resolved.toString());
        return resolved;
    }

    private static void addPdf(Map<String, Object> result, java.nio.file.Path output)
            throws IOException {
        result.put("output", output.toString());
        result.put("bytes", Files.size(output));
    }

    private static List<SSVoucher> filteredVouchers(List<SSVoucher> vouchers,
            java.time.LocalDate from, java.time.LocalDate to, Integer limit) {
        java.util.stream.Stream<SSVoucher> filtered = vouchers.stream()
                .filter(voucher -> from == null || !voucher.getLocalDate().isBefore(from))
                .filter(voucher -> to == null || !voucher.getLocalDate().isAfter(to));
        if (limit != null) {
            filtered = filtered.limit(limit);
        }
        return filtered.toList();
    }

    private static String table(List<? extends Map<String, ?>> rows, String emptyMessage,
            CliTableFormatter.Column... columns) {
        return CliTableFormatter.format(rows, emptyMessage, columns);
    }

    static String selectionTable(List<? extends Map<String, ?>> rows, String emptyMessage,
            CliTableFormatter.Column... columns) {
        if (rows.isEmpty()) {
            return emptyMessage;
        }
        String[] lines = table(rows, emptyMessage, columns).split("\\R", -1);
        StringBuilder result = new StringBuilder("  ").append(lines[0]);
        for (int index = 0; index < rows.size(); index++) {
            result.append('\n')
                    .append(Boolean.TRUE.equals(rows.get(index).get("selected")) ? "* " : "  ")
                    .append(lines[index + 1]);
        }
        return result.toString();
    }

    private static CliTableFormatter.Column left(String heading, String key) {
        return CliTableFormatter.Column.left(heading, key);
    }

    private static CliTableFormatter.Column right(String heading, String key) {
        return CliTableFormatter.Column.right(heading, key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reportMaps(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    private static List<Map<String, Object>> trialBalanceRows(
            List<FinancialReportService.TrialBalanceRow> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.account());
            item.put("description", row.description());
            item.put("opening", money(row.opening()));
            item.put("debit", money(row.debit()));
            item.put("credit", money(row.credit()));
            item.put("closing", money(row.closing()));
            return item;
        }).toList();
    }

    private static List<Map<String, Object>> balanceSheetRows(
            List<FinancialReportService.BalanceSheetRow> rows) {
        return rows.stream().map(row -> Map.<String, Object>of(
                "account", row.account(), "description", row.description(),
                "balance", money(row.balance()))).toList();
    }

    private static List<Map<String, Object>> incomeStatementRows(
            List<FinancialReportService.IncomeStatementRow> rows) {
        return rows.stream().map(row -> Map.<String, Object>of(
                "account", row.account(), "description", row.description(),
                "amount", money(row.amount()))).toList();
    }

    private static List<Map<String, Object>> ledgerRows(
            List<FinancialReportService.LedgerRow> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("voucherNumber", row.voucherNumber());
            item.put("date", row.date());
            item.put("description", row.description());
            item.put("debit", money(row.debit()));
            item.put("credit", money(row.credit()));
            item.put("balance", money(row.balance()));
            return item;
        }).toList();
    }

    @Command(mixinStandardHelpOptions = true, name="opening-balance",description="Inspect and manage opening balances",subcommands={OpeningBalanceShow.class,OpeningBalanceValidate.class,OpeningBalanceSet.class,OpeningBalanceCarryForward.class,CliInputSchemas.OpeningBalance.class})
    static class OpeningBalanceCommand extends CliCommand implements Runnable{@CommandLine.Spec CommandLine.Model.CommandSpec spec;public void run(){throw new CommandLine.ParameterException(spec.commandLine(),"An opening-balance command is required");}}
    @Command(mixinStandardHelpOptions = true, name="show") static class OpeningBalanceShow implements Callable<Integer>{@CommandLine.ParentCommand OpeningBalanceCommand command;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());OpeningBalancePlan p=new OpeningBalanceService(r.database()).current(y);Map<String,Object>x=openingBalanceDetails(p);x.put("selection",selectedContext(c,co,y));root.output(x,"Opening balance\nDebit: "+money(p.debitTotal())+"\nCredit: "+money(p.creditTotal()));return 0;}catch(Exception e){throw databaseFailure(e);}}}
    abstract static class OpeningBalanceFileCommand implements Callable<Integer>{@CommandLine.ParentCommand OpeningBalanceCommand command;@Option(names="--file",required=true)String file;abstract boolean persist();public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);OpeningBalanceInput in=readOpeningBalanceInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());Map<Integer,java.math.BigDecimal> values=new LinkedHashMap<>();for(var row:in.getBalances()){if(values.put(row.getAccount(),row.getAmount())!=null)throw new CliException("OPENING_BALANCE_INVALID","Duplicate account: "+row.getAccount());}OpeningBalanceService s=new OpeningBalanceService(r.database());OpeningBalancePlan p=persist()?s.replace(y,values):s.validate(y,values);Map<String,Object>x=openingBalanceDetails(p);x.put("written",persist());x.put("selection",selectedContext(c,co,y));root.output(x,persist()?"Opening balance updated":"Opening balance is valid; no changes written");return 0;}catch(IllegalArgumentException e){throw new CliException("OPENING_BALANCE_INVALID",e.getMessage(),e);}catch(Exception e){throw databaseFailure(e);}}}
    @Command(mixinStandardHelpOptions = true, name="validate") static class OpeningBalanceValidate extends OpeningBalanceFileCommand{boolean persist(){return false;}}
    @Command(mixinStandardHelpOptions = true, name="set") static class OpeningBalanceSet extends OpeningBalanceFileCommand{@Option(names="--dry-run")boolean dryRun;boolean persist(){return !dryRun;}}
    @Command(mixinStandardHelpOptions = true, name="carry-forward") static class OpeningBalanceCarryForward implements Callable<Integer>{@CommandLine.ParentCommand OpeningBalanceCommand command;@Option(names="--from-year-id",required=true)int fromYearId;@Option(names="--commit")boolean commit;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear to=r.selectYear(co,c.yearId());SSNewAccountingYear from=r.database().getYearsForCompany(co).stream().filter(y->y.getId()==fromYearId).findFirst().orElseThrow(()->new CliException("YEAR_NOT_FOUND","No source year has id "+fromYearId));OpeningBalancePlan p=new OpeningBalanceService(r.database()).carryForward(from,to,commit);Map<String,Object>x=openingBalanceDetails(p);x.put("fromYearId",fromYearId);x.put("toYearId",to.getId());x.put("committed",commit);x.put("selection",selectedContext(c,co,to));String adjustment=p.adjustment()==null?"":"\nAdjusted account "+p.adjustment().account()+" by "+money(p.adjustment().amount())+" to preserve balance";root.output(x,(commit?"Opening balances carried forward":"Carry-forward preview; no changes written")+adjustment);return 0;}catch(IllegalArgumentException e){throw new CliException("OPENING_BALANCE_INVALID",e.getMessage(),e);}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "backup", description = "Create, list, verify, and restore full backups",
            subcommands = {BackupCreate.class, BackupList.class, BackupVerify.class,
                    BackupRestore.class})
    static class BackupCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A backup command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create a full backup archive")
    static class BackupCreate implements Callable<Integer> {
        @CommandLine.ParentCommand BackupCommand command;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try {
                try (BokfriRuntime ignored = root.openRuntime(context.dataDir())) {
                    // A clean shutdown makes the on-disk HSQLDB snapshot self-contained.
                }
                BackupDetails backup = new BackupService(context.dataDir()).create(output, overwrite);
                root.output(backupDetails(backup), "Created backup " + backup.path()
                        + " (" + backup.size() + " bytes)");
                return 0;
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw new CliException("OUTPUT_EXISTS",
                        "Output file already exists: " + exception.getFile(), exception);
            } catch (Exception exception) {
                throw new CliException("BACKUP_CREATE_FAILED", exception.getMessage(), exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List backups created by this data directory")
    static class BackupList implements Callable<Integer> {
        @CommandLine.ParentCommand BackupCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try {
                List<BackupDetails> backups = new BackupService(context.dataDir()).list();
                List<Map<String, Object>> rows = backups.stream().map(BokfriCli::backupDetails).toList();
                String text = table(rows, "No backups found", left("Created", "createdAt"),
                        right("Size", "size"), left("Exists", "exists"), left("Path", "path"));
                root.output(Map.of("backups", rows, "count", rows.size()), text);
                return 0;
            } catch (Exception exception) {
                throw new CliException("BACKUP_LIST_FAILED", exception.getMessage(), exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "verify", description = "Verify a full backup archive")
    static class BackupVerify implements Callable<Integer> {
        @CommandLine.ParentCommand BackupCommand command;
        @Option(names = "--file", required = true) java.nio.file.Path file;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try {
                BackupVerification verification = new BackupService(context.dataDir()).verify(file);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", true);
                result.put("path", verification.path().toString());
                result.put("createdAt", verification.createdAt());
                result.put("size", verification.size());
                result.put("legacy", verification.legacy());
                result.put("backupFormatVersion", verification.backupFormatVersion());
                result.put("dataFormatVersion", verification.dataFormatVersion());
                result.put("applicationVersion", verification.applicationVersion());
                result.put("entries", verification.entries());
                root.output(result, "Backup is valid: " + verification.path());
                return 0;
            } catch (Exception exception) {
                throw new CliException("BACKUP_INVALID", exception.getMessage(), exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "restore", description = "Preview or restore a full backup to a data directory")
    static class BackupRestore implements Callable<Integer> {
        @CommandLine.ParentCommand BackupCommand command;
        @Option(names = "--file", required = true) java.nio.file.Path file;
        @Option(names = "--target-data-dir", required = true) java.nio.file.Path targetDataDirectory;
        @Option(names = "--overwrite", description = "Replace an existing target database") boolean overwrite;
        @Option(names = "--commit", description = "Perform the restore") boolean commit;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try {
                BackupRestorePlan plan = new BackupService(context.dataDir()).restore(file,
                        targetDataDirectory, overwrite, commit);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("archive", plan.archive().toString());
                result.put("targetDataDirectory", plan.targetDataDirectory().toString());
                result.put("createdAt", plan.createdAt());
                result.put("databaseFiles", plan.databaseFiles());
                result.put("replacesExistingDatabase", plan.replacesExistingDatabase());
                result.put("committed", plan.committed());
                root.output(result, commit ? "Backup restored to " + plan.targetDataDirectory()
                        : "Backup restore preview; no changes written");
                return 0;
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw new CliException("BACKUP_RESTORE_TARGET_EXISTS",
                        "Target database already exists; use --overwrite to replace it", exception);
            } catch (Exception exception) {
                throw new CliException("BACKUP_RESTORE_FAILED", exception.getMessage(), exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "sie", description = "Import and export Swedish SIE files",
            subcommands = {SieExport.class, SieImport.class})
    static class SieCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A SIE command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "export", description = "Export the selected accounting year")
    static class SieExport implements Callable<Integer> {
        @CommandLine.ParentCommand SieCommand command;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--type", defaultValue = "4E", description = "SIE type: 1, 2, 3, or 4E")
        String type;
        @Option(names = "--comment", description = "Optional SIE comment") String comment;
        @Option(names = "--overwrite", description = "Replace an existing output file") boolean overwrite;
        @Option(names = "--allow-rounding-adjustments",
                description = "Add disclosed SIE-only rounding rows when two-decimal output needs them")
        boolean allowRoundingAdjustments;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            SIEType sieType = parseSieType(type);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                var exported = new SieExportService().export(output, sieType, comment, overwrite,
                        allowRoundingAdjustments);
                java.nio.file.Path file = exported.path();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("output", file.toString());
                result.put("size", Files.size(file));
                result.put("type", type.toUpperCase(java.util.Locale.ROOT));
                result.put("roundingAdjustments", exported.adjustments().stream().map(adjustment ->
                        Map.<String, Object>of("voucherNumber", adjustment.voucherNumber(),
                                "account", adjustment.account(),
                                "amount", money(adjustment.amount()))).toList());
                result.put("selection", selectedContext(context, company, year));
                String adjustmentText = exported.adjustments().isEmpty() ? ""
                        : "\nAdded " + exported.adjustments().size()
                                + " SIE-only rounding adjustment(s)";
                root.output(result, "Created SIE export " + file + " (" + Files.size(file)
                        + " bytes)" + adjustmentText);
                return 0;
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw new CliException("OUTPUT_EXISTS",
                        "Output file already exists: " + exception.getFile(), exception);
            } catch (Exception exception) {
                throw new CliException("SIE_EXPORT_FAILED", exception.getMessage(), exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "import", description = "Preview or import into the selected accounting year")
    static class SieImport implements Callable<Integer> {
        @CommandLine.ParentCommand SieCommand command;
        @Option(names = "--file", required = true) java.nio.file.Path file;
        @Option(names = "--commit", description = "Apply the import; preview is the default") boolean commit;
        @Option(names = "--vouchers-only", description = "Import only SIE type 4 vouchers")
        boolean vouchersOnly;
        @Option(names = "--allow-already-imported",
                description = "Allow a file marked with #FLAGGA 1") boolean allowAlreadyImported;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                SieImportService service = new SieImportService(context.dataDir());
                SieImportPlan plan = service.inspect(file, vouchersOnly);
                if (plan.previouslyImported() && !allowAlreadyImported) {
                    throw new CliException("SIE_ALREADY_IMPORTED",
                            "Identical SIE content was already imported (SHA-256 " + plan.sha256() + ")");
                }
                if (commit) {
                    plan = service.importFile(file, vouchersOnly, allowAlreadyImported);
                }
                Map<String, Object> result = sieImportDetails(plan);
                result.put("committed", commit);
                result.put("selection", selectedContext(context, company, year));
                if (commit) {
                    result.put("voucherCountAfter", runtime.database().getVouchers().size());
                    result.put("accountCountAfter", year.getAccountPlan().getAccounts().size());
                }
                root.output(result, commit ? "SIE import completed" : "SIE import preview; no changes written");
                return 0;
            } catch (CliException exception) {
                throw exception;
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                throw new CliException("SIE_IMPORT_FAILED", message, exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "customer", description = "Inspect and create customers",
            subcommands = {CustomerList.class, CustomerShow.class, CustomerValidate.class,
                    CustomerCreate.class, CliInputSchemas.Customer.class})
    static class CustomerCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A customer command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List customers")
    static class CustomerList implements Callable<Integer> {
        @CommandLine.ParentCommand CustomerCommand command;
        @Option(names = "--output", description = "Write the GUI customer list as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                CustomerService service = new CustomerService(runtime.database());
                List<SSCustomer> listedCustomers = service.list();
                List<Map<String, Object>> customers = listedCustomers.stream()
                        .map(BokfriCli::customerSummary).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selectedCompanyContext(context, company));
                result.put("count", customers.size());
                result.put("customers", customers);
                if (output != null) {
                    addPdf(result, exportPdf(new SSCustomerListPrinter(
                            new java.util.ArrayList<>(listedCustomers)), output, overwrite));
                }
                root.output(result, table(customers, "No customers found",
                        left("Number", "number"), left("Name", "name"), left("Email", "email")));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one customer by number")
    static class CustomerShow implements Callable<Integer> {
        @CommandLine.ParentCommand CustomerCommand command;
        @Parameters(index = "0") String number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSCustomer customer = new CustomerService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("CUSTOMER_NOT_FOUND",
                                "No customer has number " + number));
                Map<String, Object> result = customerDetails(customer);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, customer.getNumber() + "\t" + customer.getName()
                        + "\nEmail: " + customer.getEMail() + "\nVAT number: " + customer.getVATNumber());
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    abstract static class CustomerOperation implements Callable<Integer> {
        @CommandLine.ParentCommand CustomerCommand command;
        @Option(names = "--file", required = true, description = "Customer JSON file, or - for stdin")
        String file;

        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            CustomerInput input = readCustomerInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSCustomer customer = toCustomer(input, runtime);
                CustomerService service = new CustomerService(runtime.database());
                CustomerValidationResult validation = service.validate(customer);
                if (!validation.valid()) {
                    throw customerValidationFailure(validation);
                }
                if (persist()) {
                    service.create(customer);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", true);
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("selection", selectedCompanyContext(context, company));
                result.put("customer", customerDetails(customer));
                root.output(result, persist()
                        ? "Created customer " + customer.getNumber() + " - " + customer.getName()
                        : "Customer is valid; no changes written\n" + customer.getNumber()
                                + "\t" + customer.getName());
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate customer JSON without writing")
    static class CustomerValidate extends CustomerOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create a customer from JSON")
    static class CustomerCreate extends CustomerOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name = "product", description = "Inspect and create products",
            subcommands = {ProductList.class, ProductShow.class, ProductValidate.class,
                    ProductCreate.class, CliInputSchemas.Product.class})
    static class ProductCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A product command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List products")
    static class ProductList implements Callable<Integer> {
        @CommandLine.ParentCommand ProductCommand command;
        @Option(names = "--output", description = "Write the product list as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                ProductService service = new ProductService(runtime.database());
                List<SSProduct> listedProducts = service.list();
                List<Map<String, Object>> products = listedProducts.stream()
                        .map(BokfriCli::productDetails).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selectedCompanyContext(context, company));
                result.put("count", products.size()); result.put("products", products);
                if (output != null) {
                    addPdf(result, exportPdf(new SSProductListPrinter(
                            new java.util.ArrayList<>(listedProducts)), output, overwrite));
                }
                root.output(result,
                        table(products, "No products found", left("Number", "number"),
                                left("Description", "description"), right("Selling price", "sellingPrice")));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one product by number")
    static class ProductShow implements Callable<Integer> {
        @CommandLine.ParentCommand ProductCommand command;
        @Parameters(index = "0") String number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSProduct product = new ProductService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("PRODUCT_NOT_FOUND",
                                "No product has number " + number));
                Map<String, Object> result = productDetails(product);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, product.getNumber() + "\t" + product.getDescription()
                        + "\nSelling price: " + product.getSellingPrice());
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    abstract static class ProductOperation implements Callable<Integer> {
        @CommandLine.ParentCommand ProductCommand command;
        @Option(names = "--file", required = true, description = "Product JSON file, or - for stdin")
        String file;

        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            ProductInput input = readProductInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                SSProduct product = toProduct(input, runtime);
                ProductService service = new ProductService(runtime.database());
                ProductValidationResult validation = service.validate(product);
                if (!validation.valid()) {
                    throw productValidationFailure(validation);
                }
                if (persist()) {
                    service.create(product);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", true);
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("selection", selectedContext(context, company, year));
                result.put("product", productDetails(product));
                root.output(result, persist()
                        ? "Created product " + product.getNumber() + " - " + product.getDescription()
                        : "Product is valid; no changes written\n" + product.getNumber()
                                + "\t" + product.getDescription());
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate product JSON without writing")
    static class ProductValidate extends ProductOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create a product from JSON")
    static class ProductCreate extends ProductOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name = "supplier", description = "Inspect and create suppliers",
            subcommands = {SupplierList.class, SupplierShow.class, SupplierValidate.class,
                    SupplierCreate.class, CliInputSchemas.Supplier.class})
    static class SupplierCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A supplier command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List suppliers")
    static class SupplierList implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCommand command;
        @Option(names = "--output", description = "Write the supplier list as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                List<SSSupplier> listedSuppliers = new SupplierService(runtime.database()).list();
                List<Map<String, Object>> suppliers = listedSuppliers.stream()
                        .map(BokfriCli::supplierDetails).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selectedCompanyContext(context, company));
                result.put("count", suppliers.size());
                result.put("suppliers", suppliers);
                if (output != null) {
                    addPdf(result, exportPdf(new SSSupplierListPrinter(
                            new java.util.ArrayList<>(listedSuppliers)), output, overwrite));
                }
                root.output(result,
                        table(suppliers, "No suppliers found", left("Number", "number"),
                                left("Name", "name"), left("Email", "email")));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one supplier by number")
    static class SupplierShow implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCommand command;
        @Parameters(index = "0") String number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSSupplier supplier = new SupplierService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("SUPPLIER_NOT_FOUND",
                                "No supplier has number " + number));
                Map<String, Object> result = supplierDetails(supplier);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, supplier.getNumber() + "\t" + supplier.getName()
                        + "\nEmail: " + supplier.getEMail());
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    abstract static class SupplierOperation implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCommand command;
        @Option(names = "--file", required = true, description = "Supplier JSON file, or - for stdin")
        String file;
        abstract boolean persist();
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            SupplierInput input = readSupplierInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SupplierService service = new SupplierService(runtime.database());
                SSSupplier supplier = toSupplier(input, runtime, service);
                SupplierValidationResult validation = service.validate(supplier);
                if (!validation.valid()) { throw supplierValidationFailure(validation); }
                if (persist()) { service.create(supplier); }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", true);
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("supplier", supplierDetails(supplier));
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, persist()
                        ? "Created supplier " + supplier.getNumber() + " - " + supplier.getName()
                        : "Supplier is valid; no changes written\n" + supplier.getNumber()
                                + "\t" + supplier.getName());
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate supplier JSON without writing")
    static class SupplierValidate extends SupplierOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create a supplier from JSON")
    static class SupplierCreate extends SupplierOperation {
        @Option(names = "--dry-run") boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name="supplier-invoice",description="Inspect, create, and book supplier invoices",subcommands={SupplierInvoiceList.class,SupplierInvoiceShow.class,SupplierInvoiceJournal.class,SupplierInvoiceValidate.class,SupplierInvoiceCreate.class,CliInputSchemas.SupplierInvoice.class})
    static class SupplierInvoiceCommand extends CliCommand implements Runnable {@CommandLine.Spec CommandLine.Model.CommandSpec spec;public void run(){throw new CommandLine.ParameterException(spec.commandLine(),"A supplier-invoice command is required");}}
    @Command(mixinStandardHelpOptions = true, name="list") static class SupplierInvoiceList implements Callable<Integer>{@CommandLine.ParentCommand SupplierInvoiceCommand command;@Option(names="--output")java.nio.file.Path output;@Option(names="--overwrite")boolean overwrite;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);List<SSSupplierInvoice> items=new SupplierInvoiceService(r.database()).list();List<Map<String,Object>> x=items.stream().map(BokfriCli::supplierInvoiceDetails).toList();Map<String,Object> result=new LinkedHashMap<>();result.put("selection",selectedCompanyContext(c,co));result.put("count",x.size());result.put("supplierInvoices",x);if(output!=null)addPdf(result,exportPdf(new SSSupplierInvoiceListPrinter(new java.util.ArrayList<>(items)),output,overwrite));root.output(result,table(x,"No supplier invoices found",right("Number","number"),left("Date","date"),left("Supplier","supplierName"),right("Total","total")));return 0;}catch(Exception e){throw databaseFailure(e);}}}
    @Command(mixinStandardHelpOptions = true, name="show") static class SupplierInvoiceShow implements Callable<Integer>{@CommandLine.ParentCommand SupplierInvoiceCommand command;@Parameters(index="0")int number;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);SSSupplierInvoice i=new SupplierInvoiceService(r.database()).find(number).orElseThrow(()->new CliException("SUPPLIER_INVOICE_NOT_FOUND","No supplier invoice has number "+number));Map<String,Object>x=supplierInvoiceDetails(i);x.put("selection",selectedCompanyContext(c,co));root.output(x,"Supplier invoice "+number+"\nSupplier: "+i.getSupplierName()+"\nTotal: "+x.get("total"));return 0;}catch(Exception e){throw databaseFailure(e);}}}
    abstract static class SupplierInvoiceOperation implements Callable<Integer>{@CommandLine.ParentCommand SupplierInvoiceCommand command;@Option(names="--file",required=true)String file;abstract boolean persist();public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);SupplierInvoiceInput input=readSupplierInvoiceInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);SSSupplierInvoice i=toSupplierInvoice(input,r);SupplierInvoiceService s=new SupplierInvoiceService(r.database());var v=s.validate(i);if(!v.valid())throw supplierInvoiceValidationFailure(v);Map<String,Object>x=supplierInvoiceDetails(i);x.put("number",s.nextNumber());x.put("dryRun",!persist());x.put("created",persist());x.put("selection",selectedContext(c,co,y));if(persist()){s.create(i);x.put("number",i.getNumber());}root.output(x,persist()?"Created supplier invoice "+i.getNumber():"Supplier invoice is valid; no changes written");return 0;}catch(Exception e){throw databaseFailure(e);}}}
    @Command(mixinStandardHelpOptions = true, name="validate") static class SupplierInvoiceValidate extends SupplierInvoiceOperation{boolean persist(){return false;}}
    @Command(mixinStandardHelpOptions = true, name="create") static class SupplierInvoiceCreate extends SupplierInvoiceOperation{@Option(names="--dry-run")boolean dryRun;boolean persist(){return !dryRun;}}
    @Command(mixinStandardHelpOptions = true, name="journal") static class SupplierInvoiceJournal implements Callable<Integer>{@CommandLine.ParentCommand SupplierInvoiceCommand command;@Option(names="--from",required=true)java.time.LocalDate from;@Option(names="--to",required=true)java.time.LocalDate to;@Option(names="--commit")boolean commit;@Option(names="--output")java.nio.file.Path output;@Option(names="--overwrite")boolean overwrite;public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);SupplierInvoiceService s=new SupplierInvoiceService(r.database());SupplierInvoiceJournalPlan p=s.planJournal(from,to);if(p.invoices().isEmpty())throw new CliException("SUPPLIER_INVOICE_JOURNAL_EMPTY","No unbooked supplier invoices exist in the selected period");Map<String,Object>x=supplierInvoiceJournalDetails(p);x.put("committed",commit);x.put("selection",selectedContext(c,co,y));if(output!=null)addPdf(x,exportPdf(new SSSupplierInvoicejournalPrinter(new java.util.ArrayList<>(p.invoices()),p.journalNumber(),p.to()),output,overwrite));if(commit)x.put("voucherNumber",s.commitJournal(p).voucherNumber());root.output(x,commit?"Committed supplier invoice journal "+p.journalNumber():"Supplier invoice journal preview; no changes written");return 0;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "supplier-credit-invoice", description = "Credit booked supplier invoices",
            subcommands = {SupplierCreditInvoiceList.class, SupplierCreditInvoiceShow.class,
                    SupplierCreditInvoiceValidate.class, SupplierCreditInvoiceCreate.class,
                    SupplierCreditInvoiceJournal.class, CliInputSchemas.SupplierCreditInvoice.class})
    static class SupplierCreditInvoiceCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        public void run() { throw new CommandLine.ParameterException(spec.commandLine(),
                "A supplier-credit-invoice command is required"); }
    }

    @Command(mixinStandardHelpOptions = true, name = "list")
    static class SupplierCreditInvoiceList implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCreditInvoiceCommand command;
        @Option(names="--output") java.nio.file.Path output; @Option(names="--overwrite") boolean overwrite; public Integer call() { BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);List<SSSupplierCreditInvoice> items=new SupplierCreditInvoiceService(r.database()).list();List<Map<String,Object>> rows=items.stream().map(BokfriCli::supplierCreditInvoiceDetails).toList();Map<String,Object> result=new LinkedHashMap<>();result.put("selection",selectedCompanyContext(c,co));result.put("supplierCreditInvoices",rows);result.put("count",rows.size());if(output!=null)addPdf(result,exportPdf(new SSSupplierCreditInvoiceListPrinter(new java.util.ArrayList<>(items)),output,overwrite));root.output(result,table(rows,"No supplier credit invoices found",right("Number","number"),left("Date","date"),left("Supplier","supplierName"),right("Total","total")));return 0;}catch(Exception e){throw databaseFailure(e);} }
    }

    @Command(mixinStandardHelpOptions = true, name = "show")
    static class SupplierCreditInvoiceShow implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCreditInvoiceCommand command; @Parameters(index="0") int number;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);SSSupplierCreditInvoice invoice=new SupplierCreditInvoiceService(r.database()).find(number).orElseThrow(()->new CliException("SUPPLIER_CREDIT_INVOICE_NOT_FOUND","No supplier credit invoice has number "+number));Map<String,Object>x=supplierCreditInvoiceDetails(invoice);x.put("selection",selectedCompanyContext(c,co));root.output(x,"Supplier credit invoice "+number);return 0;}catch(Exception e){throw databaseFailure(e);} }
    }

    abstract static class SupplierCreditInvoiceOperation implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCreditInvoiceCommand command;
        @Option(names="--file",required=true) String file;
        abstract boolean persist();
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);SupplierCreditInvoiceInput input=readSupplierCreditInvoiceInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);SupplierCreditInvoiceService service=new SupplierCreditInvoiceService(r.database());SSSupplierInvoice original=new SupplierInvoiceService(r.database()).find(input.getSupplierInvoiceNumber()).orElseThrow(()->new CliException("SUPPLIER_INVOICE_NOT_FOUND","No supplier invoice has number "+input.getSupplierInvoiceNumber()));SSSupplierCreditInvoice credit=persist()?service.create(original,input.getDate(),input.getAmount()):service.preview(original,input.getDate(),input.getAmount());Map<String,Object>x=supplierCreditInvoiceDetails(credit);x.put("created",persist());x.put("dryRun",!persist());x.put("selection",selectedContext(c,co,y));root.output(x,persist()?"Created supplier credit invoice "+credit.getNumber():"Supplier credit invoice is valid; no changes written");return 0;}catch(CliException e){throw e;}catch(IllegalArgumentException e){throw new CliException("SUPPLIER_CREDIT_INVOICE_INVALID",e.getMessage(),e);}catch(Exception e){throw databaseFailure(e);} }
    }

    @Command(mixinStandardHelpOptions = true, name="validate") static class SupplierCreditInvoiceValidate extends SupplierCreditInvoiceOperation {boolean persist(){return false;}}
    @Command(mixinStandardHelpOptions = true, name="create") static class SupplierCreditInvoiceCreate extends SupplierCreditInvoiceOperation {@Option(names="--dry-run")boolean dryRun;boolean persist(){return !dryRun;}}

    @Command(mixinStandardHelpOptions = true, name="journal")
    static class SupplierCreditInvoiceJournal implements Callable<Integer> {
        @CommandLine.ParentCommand SupplierCreditInvoiceCommand command;
        @Option(names="--from",required=true) java.time.LocalDate from;
        @Option(names="--to",required=true) java.time.LocalDate to;
        @Option(names="--commit") boolean commit;
        @Option(names="--output") java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);SupplierCreditInvoiceService service=new SupplierCreditInvoiceService(r.database());SupplierCreditInvoiceJournalPlan plan=service.planJournal(from,to);if(plan.invoices().isEmpty())throw new CliException("SUPPLIER_CREDIT_INVOICE_JOURNAL_EMPTY","No unbooked supplier credit invoices exist in the selected period");Map<String,Object>x=new LinkedHashMap<>();x.put("journalNumber",plan.journalNumber());x.put("supplierCreditInvoiceNumbers",plan.invoices().stream().map(SSSupplierCreditInvoice::getNumber).toList());x.put("debitTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(plan.voucher())));x.put("creditTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(plan.voucher())));x.put("committed",commit);if(output!=null)addPdf(x,exportPdf(new SSSuppliercreditinvoicejournalPrinter(new java.util.ArrayList<>(plan.invoices()),plan.journalNumber(),plan.to()),output,overwrite));if(commit)x.put("voucherNumber",service.commitJournal(plan).voucherNumber());x.put("selection",selectedContext(c,co,y));root.output(x,commit?"Supplier credit-invoice journal committed":"Supplier credit-invoice journal preview; no changes written");return 0;}catch(Exception e){throw databaseFailure(e);} }
    }

    @Command(mixinStandardHelpOptions = true, name = "invoice", description = "Inspect and create customer invoices",
            subcommands = {InvoiceList.class, InvoiceShow.class, InvoicePdf.class,
                    InvoiceReminder.class, InvoiceDeliveryNote.class, InvoicePickingList.class, InvoiceJournal.class,
                    InvoiceValidate.class, InvoiceCreate.class, CliInputSchemas.Invoice.class})
    static class InvoiceCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An invoice command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List customer invoices")
    static class InvoiceList implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        @Option(names = "--output", description = "Write the invoice list as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                List<SSInvoice> listedInvoices = new InvoiceService(runtime.database()).list(from, to);
                List<Map<String, Object>> invoices = listedInvoices.stream()
                        .map(BokfriCli::invoiceSummary).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selectedCompanyContext(context, company));
                result.put("count", invoices.size());
                result.put("invoices", invoices);
                if (output != null) {
                    addPdf(result, exportPdf(new SSInvoiceListPrinter(
                            new java.util.ArrayList<>(listedInvoices)), output, overwrite));
                }
                root.output(result,
                        table(invoices, "No invoices found", right("Number", "number"),
                                left("Date", "date"), left("Customer", "customerName"),
                                right("Total", "total")));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one customer invoice by number")
    static class InvoiceShow implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0") int number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSInvoice invoice = new InvoiceService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INVOICE_NOT_FOUND",
                                "No invoice has number " + number));
                Map<String, Object> result = invoiceDetails(invoice);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, "Invoice " + invoice.getNumber() + "\nCustomer: "
                        + invoice.getCustomerName() + "\nTotal: " + SSSaleMath.getTotalSum(invoice));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "journal", description = "Preview or commit an invoice journal for a period")
    static class InvoiceJournal implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--commit", description = "Persist the voucher and mark invoices entered")
        boolean commit;
        @Option(names = "--output", description = "Write the invoice journal as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                InvoiceService service = new InvoiceService(runtime.database());
                InvoiceJournalPlan plan = service.planJournal(from, to);
                if (plan.invoices().isEmpty()) {
                    throw new CliException("INVOICE_JOURNAL_EMPTY",
                            "No unbooked invoices exist in the selected period");
                }
                Map<String, Object> result = invoiceJournalDetails(plan);
                result.put("committed", commit);
                result.put("selection", selectedContext(context, company, year));
                if (output != null) {
                    addPdf(result, exportPdf(new SSInvoicejournalPrinter(
                            new java.util.ArrayList<>(plan.invoices()), plan.journalNumber(), plan.to()),
                            output, overwrite));
                }
                if (commit) {
                    InvoiceJournalResult committed = service.commitJournal(plan);
                    result.put("voucherNumber", committed.voucherNumber());
                }
                root.output(result, commit
                        ? "Committed invoice journal " + plan.journalNumber() + " with voucher "
                                + result.get("voucherNumber") + " for " + plan.invoices().size() + " invoices"
                        : "Invoice journal " + plan.journalNumber() + " preview\nInvoices: "
                                + plan.invoices().size() + "\nDebit: "
                                + money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(plan.voucher()))
                                + "\nCredit: " + money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(plan.voucher()))
                                + "\nNo changes written");
                return 0;
            } catch (IllegalArgumentException exception) {
                throw new CliException("INVOICE_JOURNAL_INVALID", exception.getMessage(), exception);
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "pdf", description = "Generate a PDF for an existing invoice")
    static class InvoicePdf implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0", description = "Invoice number") int number;
        @Option(names = "--output", required = true, description = "Destination PDF file")
        java.nio.file.Path output;
        @Option(names = "--language", defaultValue = "sv-SE", description = "Invoice language")
        String language;
        @Option(names = "--ocr", description = "Generate the OCR invoice variant")
        boolean ocr;
        @Option(names = "--ocr-background", description = "Include the OCR form background")
        boolean ocrBackground;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                InvoiceService service = new InvoiceService(runtime.database());
                SSInvoice invoice = service.find(number).orElseThrow(() ->
                        new CliException("INVOICE_NOT_FOUND", "No invoice has number " + number));
                java.nio.file.Path pdf;
                if (ocr) {
                    invoice.setOCRNumber(SSOCRNumber.getOCRNumber(invoice));
                    pdf = exportPdf(new SSOCRInvoicePrinter(invoice,
                            java.util.Locale.forLanguageTag(language), ocrBackground), output, overwrite);
                } else {
                    pdf = service.exportPdf(invoice, output,
                            java.util.Locale.forLanguageTag(language), overwrite);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("invoiceNumber", number);
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("language", language);
                result.put("ocr", ocr);
                if (ocr) result.put("ocrNumber", invoice.getOCRNumber());
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, "Created invoice PDF " + pdf + " (" + Files.size(pdf) + " bytes)");
                return 0;
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw new CliException("OUTPUT_EXISTS",
                        "Output file already exists: " + exception.getFile(), exception);
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "reminder",
            description = "Generate a payment reminder for an outstanding invoice")
    static class InvoiceReminder implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0") int number;
        @Option(names = "--date", required = true) java.time.LocalDate date;
        @Option(names = "--language", defaultValue = "sv-SE") String language;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                SSInvoice invoice = new InvoiceService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INVOICE_NOT_FOUND",
                                "No invoice has number " + number));
                java.math.BigDecimal balance = se.swedsoft.bookkeeping.calc.math.SSInvoiceMath
                        .getSaldo(invoice);
                if (!invoice.isEntered()) {
                    throw new CliException("REMINDER_INVOICE_NOT_ENTERED",
                            "Invoice " + number + " must be entered before a reminder is generated");
                }
                if (balance.signum() <= 0) {
                    throw new CliException("REMINDER_INVOICE_SETTLED",
                            "Invoice " + number + " has no outstanding balance");
                }
                if (invoice.getLocalDueDate() == null || !date.isAfter(invoice.getLocalDueDate())) {
                    throw new CliException("REMINDER_INVOICE_NOT_OVERDUE",
                            "Reminder date must be after the invoice due date");
                }
                SSCustomer customer = runtime.database().getCustomer(invoice.getCustomerNr())
                        .orElseThrow(() -> new CliException("INVOICE_CUSTOMER_NOT_FOUND",
                                "No customer has number " + invoice.getCustomerNr()));
                java.nio.file.Path pdf = exportPdf(new SSReminderPrinter(List.of(invoice), customer,
                        java.util.Locale.forLanguageTag(language), date), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("invoiceNumber", number); result.put("date", date);
                result.put("dueDate", invoice.getLocalDueDate()); result.put("balance", money(balance));
                result.put("output", pdf.toString()); result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company,
                        runtime.database().getCurrentYear()));
                root.output(result, "Created payment-reminder PDF " + pdf);
                return 0;
            } catch (CliException exception) { throw exception; }
            catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    abstract static class InvoiceSalesDocument implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0", description = "Invoice number") int number;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--language", defaultValue = "sv-SE") String language;
        @Option(names = "--overwrite") boolean overwrite;

        abstract SSPrinter printer(SSOrder order, java.util.Locale locale);
        abstract String documentName();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSInvoice invoice = new InvoiceService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INVOICE_NOT_FOUND",
                                "No invoice has number " + number));
                SSOrder order = new SSOrder(invoice);
                java.nio.file.Path pdf = exportPdf(printer(order,
                        java.util.Locale.forLanguageTag(language)), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("invoiceNumber", number);
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, "Created " + documentName() + " PDF " + pdf);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "delivery-note",
            description = "Generate a delivery note from an invoice")
    static class InvoiceDeliveryNote extends InvoiceSalesDocument {
        @Option(names = "--hide-unit-price", description = "Use the delivery-note layout without prices")
        boolean hideUnitPrice;
        @Override SSPrinter printer(SSOrder order, java.util.Locale locale) {
            order.setHideUnitprice(hideUnitPrice);
            return new SSDeliverynotePrinter(order, locale);
        }
        @Override String documentName() { return "delivery note"; }
    }

    @Command(mixinStandardHelpOptions = true, name = "picking-list",
            description = "Generate a picking list from an invoice")
    static class InvoicePickingList extends InvoiceSalesDocument {
        @Override SSPrinter printer(SSOrder order, java.util.Locale locale) {
            return new SSPickingslipPrinter(order, locale);
        }
        @Override String documentName() { return "picking list"; }
    }

    abstract static class InvoiceOperation implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--file", required = true, description = "Invoice JSON file, or - for stdin")
        String file;

        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            InvoiceInput input = readInvoiceInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                SSInvoice invoice = toInvoice(input, runtime);
                InvoiceService service = new InvoiceService(runtime.database());
                InvoiceValidationResult validation = service.validate(invoice);
                if (!validation.valid()) {
                    throw invoiceValidationFailure(validation);
                }
                Map<String, Object> result = invoiceDetails(invoice);
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("number", service.nextNumber());
                result.put("selection", selectedCompanyContext(context, company));
                if (persist()) {
                    service.create(invoice);
                    result.put("number", invoice.getNumber());
                }
                root.output(result, persist()
                        ? "Created invoice " + invoice.getNumber() + " for " + invoice.getCustomerName()
                        : "Invoice is valid; no changes written\nNumber: " + result.get("number")
                                + "\nTotal: " + result.get("total"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate invoice JSON without writing")
    static class InvoiceValidate extends InvoiceOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create an unbooked customer invoice from JSON")
    static class InvoiceCreate extends InvoiceOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name = "credit-invoice", description = "Credit booked customer invoices",
            subcommands = {CreditInvoiceList.class, CreditInvoiceShow.class, CreditInvoicePdf.class,
                    CreditInvoiceValidate.class, CreditInvoiceCreate.class, CreditInvoiceJournal.class,
                    CliInputSchemas.CreditInvoice.class})
    static class CreditInvoiceCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() { throw new CommandLine.ParameterException(spec.commandLine(), "A credit-invoice command is required"); }
    }

    @Command(mixinStandardHelpOptions = true, name = "list") static class CreditInvoiceList implements Callable<Integer> {
        @CommandLine.ParentCommand CreditInvoiceCommand command;
        @Option(names="--output") java.nio.file.Path output; @Option(names="--overwrite") boolean overwrite; public Integer call() { BokfriCli root=command.parent; ResolvedContext c=root.resolveContext(true,false); try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);List<SSCreditInvoice> items=new CreditInvoiceService(r.database()).list();List<Map<String,Object>> rows=items.stream().map(BokfriCli::creditInvoiceDetails).toList();Map<String,Object> result=new LinkedHashMap<>();result.put("selection",selectedCompanyContext(c,co));result.put("creditInvoices",rows);result.put("count",rows.size());if(output!=null)addPdf(result,exportPdf(new SSCreditInvoiceListPrinter(new java.util.ArrayList<>(items)),output,overwrite));root.output(result,table(rows,"No credit invoices found",right("Number","number"),left("Date","date"),left("Customer","customerName"),right("Total","total")));return 0;}catch(Exception e){throw databaseFailure(e);}}
    }

    @Command(mixinStandardHelpOptions = true, name = "show") static class CreditInvoiceShow implements Callable<Integer> {
        @CommandLine.ParentCommand CreditInvoiceCommand command; @Parameters(index="0") int number;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,false);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());r.database().init(false);SSCreditInvoice i=new CreditInvoiceService(r.database()).find(number).orElseThrow(()->new CliException("CREDIT_INVOICE_NOT_FOUND","No credit invoice has number "+number));Map<String,Object>x=creditInvoiceDetails(i);x.put("selection",selectedCompanyContext(c,co));root.output(x,"Credit invoice "+number+" for invoice "+i.getCreditingNr());return 0;}catch(CliException e){throw e;}catch(Exception e){throw databaseFailure(e);}}
    }

    @Command(mixinStandardHelpOptions = true, name = "pdf",
            description = "Generate a PDF for an existing credit invoice")
    static class CreditInvoicePdf implements Callable<Integer> {
        @CommandLine.ParentCommand CreditInvoiceCommand command;
        @Parameters(index = "0", description = "Credit invoice number") int number;
        @Option(names = "--output", required = true, description = "Destination PDF file")
        java.nio.file.Path output;
        @Option(names = "--language", defaultValue = "sv-SE", description = "Document language")
        String language;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSCreditInvoice invoice = new CreditInvoiceService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("CREDIT_INVOICE_NOT_FOUND",
                                "No credit invoice has number " + number));
                Map<String, Object> result = creditInvoiceDetails(invoice);
                result.put("selection", selectedCompanyContext(context, company));
                addPdf(result, exportPdf(new SSCreditinvoicePrinter(invoice,
                        java.util.Locale.forLanguageTag(language)), output, overwrite));
                root.output(result, "Created credit invoice PDF " + result.get("output"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    abstract static class CreditInvoiceOperation implements Callable<Integer> {
        @CommandLine.ParentCommand CreditInvoiceCommand command; @Option(names="--file",required=true) String file; abstract boolean persist();
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);CreditInvoiceInput input=readCreditInvoiceInput(file);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);CreditInvoiceService s=new CreditInvoiceService(r.database());SSInvoice original=new InvoiceService(r.database()).find(input.getInvoiceNumber()).orElseThrow(()->new CliException("INVOICE_NOT_FOUND","No invoice has number "+input.getInvoiceNumber()));SSCreditInvoice credit=persist()?s.create(original,input.getDate(),input.getAmount()):s.preview(original,input.getDate(),input.getAmount());Map<String,Object>x=creditInvoiceDetails(credit);x.put("created",persist());x.put("dryRun",!persist());x.put("selection",selectedContext(c,co,y));root.output(x,persist()?"Created credit invoice "+credit.getNumber():"Credit invoice is valid; no changes written");return 0;}catch(CliException e){throw e;}catch(IllegalArgumentException e){throw new CliException("CREDIT_INVOICE_INVALID",e.getMessage(),e);}catch(Exception e){throw databaseFailure(e);}}
    }
    @Command(mixinStandardHelpOptions = true, name="validate") static class CreditInvoiceValidate extends CreditInvoiceOperation {boolean persist(){return false;}}
    @Command(mixinStandardHelpOptions = true, name="create") static class CreditInvoiceCreate extends CreditInvoiceOperation {@Option(names="--dry-run")boolean dryRun;boolean persist(){return !dryRun;}}

    @Command(mixinStandardHelpOptions = true, name="journal") static class CreditInvoiceJournal implements Callable<Integer> {
        @CommandLine.ParentCommand CreditInvoiceCommand command;@Option(names="--from",required=true)java.time.LocalDate from;@Option(names="--to",required=true)java.time.LocalDate to;@Option(names="--commit")boolean commit;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());r.database().init(false);CreditInvoiceService s=new CreditInvoiceService(r.database());CreditInvoiceJournalPlan p=s.planJournal(from,to);Map<String,Object>x=new LinkedHashMap<>();x.put("journalNumber",p.journalNumber());x.put("from",from);x.put("to",to);x.put("creditInvoiceNumbers",p.invoices().stream().map(SSCreditInvoice::getNumber).toList());x.put("invoiceCount",p.invoices().size());x.put("rows",voucherRows(p.voucher()));x.put("debitTotal",money(voucherDebit(p.voucher())));x.put("creditTotal",money(voucherCredit(p.voucher())));x.put("committed",commit);if(commit){CreditInvoiceJournalResult done=s.commitJournal(p);x.put("voucherNumber",done.voucherNumber());}x.put("selection",selectedContext(c,co,y));root.output(x,commit?"Credit invoice journal committed":"Credit invoice journal preview; no changes written");return 0;}catch(IllegalArgumentException e){throw new CliException("CREDIT_INVOICE_JOURNAL_EMPTY",e.getMessage(),e);}catch(Exception e){throw databaseFailure(e);}}
    }

    @Command(mixinStandardHelpOptions = true, name = "inpayment", description = "Inspect, create, and book customer inpayments",
            subcommands = {InpaymentList.class, InpaymentShow.class, InpaymentJournal.class,
                    InpaymentPdfList.class, InpaymentValidate.class, InpaymentCreate.class,
                    CliInputSchemas.Inpayment.class})
    static class InpaymentCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An inpayment command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List customer inpayments")
    static class InpaymentList implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                List<Map<String, Object>> items = new InpaymentService(runtime.database()).list()
                        .stream().map(BokfriCli::inpaymentDetails).toList();
                root.output(Map.of("selection", selectedCompanyContext(context, company),
                                "count", items.size(), "inpayments", items),
                        table(items, "No inpayments found", right("Number", "number"),
                                left("Date", "date"), left("Text", "text"), right("Total", "total")));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "pdf-list",
            description = "Generate the customer inpayment list as PDF")
    static class InpaymentPdfList implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSInpaymentListPrinter(), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created inpayment-list PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one customer inpayment")
    static class InpaymentShow implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Parameters(index = "0") int number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSInpayment item = new InpaymentService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INPAYMENT_NOT_FOUND",
                                "No inpayment has number " + number));
                Map<String, Object> result = inpaymentDetails(item);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, "Inpayment " + number + "\nDate: " + item.getLocalDate()
                        + "\nText: " + item.getText() + "\nTotal: " + result.get("total"));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    abstract static class InpaymentOperation implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Option(names = "--file", required = true, description = "Inpayment JSON file, or - for stdin")
        String file;
        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            InpaymentInput input = readInpaymentInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                SSInpayment item = toInpayment(input, runtime);
                InpaymentService service = new InpaymentService(runtime.database());
                InpaymentValidationResult validation = service.validate(item);
                if (!validation.valid()) { throw inpaymentValidationFailure(validation); }
                Map<String, Object> result = inpaymentDetails(item);
                result.put("number", service.nextNumber());
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("selection", selectedContext(context, company, year));
                if (persist()) {
                    service.create(item);
                    result.put("number", item.getNumber());
                }
                root.output(result, persist()
                        ? "Created inpayment " + item.getNumber() + " - " + item.getText()
                        : "Inpayment is valid; no changes written\nNumber: " + result.get("number")
                                + "\nTotal: " + result.get("total"));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate inpayment JSON without writing")
    static class InpaymentValidate extends InpaymentOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create an unbooked customer inpayment")
    static class InpaymentCreate extends InpaymentOperation {
        @Option(names = "--dry-run") boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name = "journal", description = "Preview or commit an inpayment journal")
    static class InpaymentJournal implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--commit") boolean commit;
        @Option(names = "--output", description = "Write the inpayment journal as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                InpaymentService service = new InpaymentService(runtime.database());
                InpaymentJournalPlan plan = service.planJournal(from, to);
                if (plan.inpayments().isEmpty()) {
                    throw new CliException("INPAYMENT_JOURNAL_EMPTY",
                            "No unbooked inpayments exist in the selected period");
                }
                Map<String, Object> result = inpaymentJournalDetails(plan);
                result.put("committed", commit);
                result.put("selection", selectedContext(context, company, year));
                if (output != null) {
                    addPdf(result, exportPdf(new SSInpaymentjournalPrinter(
                            new java.util.ArrayList<>(plan.inpayments()), plan.journalNumber(), plan.to()),
                            output, overwrite));
                }
                if (commit) {
                    InpaymentJournalResult committed = service.commitJournal(plan);
                    result.put("voucherNumber", committed.voucherNumber());
                }
                root.output(result, commit
                        ? "Committed inpayment journal " + plan.journalNumber() + " with voucher "
                                + result.get("voucherNumber") + " for " + plan.inpayments().size()
                                + " inpayments"
                        : "Inpayment journal " + plan.journalNumber() + " preview\nInpayments: "
                                + plan.inpayments().size() + "\nNo changes written");
                return 0;
            } catch (IllegalArgumentException exception) {
                throw new CliException("INPAYMENT_JOURNAL_INVALID", exception.getMessage(), exception);
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "outpayment", description = "Inspect, create, and book supplier outpayments",
            subcommands = {OutpaymentList.class, OutpaymentShow.class, OutpaymentJournal.class,
                    OutpaymentPdfList.class, OutpaymentValidate.class, OutpaymentCreate.class,
                    CliInputSchemas.Outpayment.class})
    static class OutpaymentCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An outpayment command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List supplier outpayments")
    static class OutpaymentList implements Callable<Integer> {
        @CommandLine.ParentCommand OutpaymentCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                List<Map<String, Object>> items = new OutpaymentService(runtime.database()).list()
                        .stream().map(BokfriCli::outpaymentDetails).toList();
                root.output(Map.of("selection", selectedCompanyContext(context, company),
                                "count", items.size(), "outpayments", items),
                        table(items, "No outpayments found", right("Number", "number"),
                                left("Date", "date"), left("Text", "text"), right("Total", "total")));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "pdf-list",
            description = "Generate the supplier outpayment list as PDF")
    static class OutpaymentPdfList implements Callable<Integer> {
        @CommandLine.ParentCommand OutpaymentCommand command;
        @Option(names = "--output", required = true) java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                java.nio.file.Path pdf = exportPdf(new SSOutpaymentListPrinter(), output, overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("output", pdf.toString()); result.put("bytes", Files.size(pdf));
                result.put("selection", selectedContext(context, company, year));
                root.output(result, "Created outpayment-list PDF " + pdf);
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one supplier outpayment")
    static class OutpaymentShow implements Callable<Integer> {
        @CommandLine.ParentCommand OutpaymentCommand command;
        @Parameters(index = "0") int number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSOutpayment item = new OutpaymentService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INPAYMENT_NOT_FOUND",
                                "No outpayment has number " + number));
                Map<String, Object> result = outpaymentDetails(item);
                result.put("selection", selectedCompanyContext(context, company));
                root.output(result, "Outpayment " + number + "\nDate: " + item.getLocalDate()
                        + "\nText: " + item.getText() + "\nTotal: " + result.get("total"));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    abstract static class OutpaymentOperation implements Callable<Integer> {
        @CommandLine.ParentCommand OutpaymentCommand command;
        @Option(names = "--file", required = true, description = "Outpayment JSON file, or - for stdin")
        String file;
        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            OutpaymentInput input = readOutpaymentInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                SSOutpayment item = toOutpayment(input, runtime);
                OutpaymentService service = new OutpaymentService(runtime.database());
                OutpaymentValidationResult validation = service.validate(item);
                if (!validation.valid()) { throw outpaymentValidationFailure(validation); }
                Map<String, Object> result = outpaymentDetails(item);
                result.put("number", service.nextNumber());
                result.put("dryRun", !persist());
                result.put("created", persist());
                result.put("selection", selectedContext(context, company, year));
                if (persist()) {
                    service.create(item);
                    result.put("number", item.getNumber());
                }
                root.output(result, persist()
                        ? "Created outpayment " + item.getNumber() + " - " + item.getText()
                        : "Outpayment is valid; no changes written\nNumber: " + result.get("number")
                                + "\nTotal: " + result.get("total"));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate outpayment JSON without writing")
    static class OutpaymentValidate extends OutpaymentOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create an unbooked supplier outpayment")
    static class OutpaymentCreate extends OutpaymentOperation {
        @Option(names = "--dry-run") boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(mixinStandardHelpOptions = true, name = "journal", description = "Preview or commit an outpayment journal")
    static class OutpaymentJournal implements Callable<Integer> {
        @CommandLine.ParentCommand OutpaymentCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--commit") boolean commit;
        @Option(names = "--output") java.nio.file.Path output;
        @Option(names = "--overwrite") boolean overwrite;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                OutpaymentService service = new OutpaymentService(runtime.database());
                OutpaymentJournalPlan plan = service.planJournal(from, to);
                if (plan.outpayments().isEmpty()) {
                    throw new CliException("INPAYMENT_JOURNAL_EMPTY",
                            "No unbooked outpayments exist in the selected period");
                }
                Map<String, Object> result = outpaymentJournalDetails(plan);
                result.put("committed", commit);
                result.put("selection", selectedContext(context, company, year));
                if (output != null) {
                    addPdf(result, exportPdf(new SSOutpaymentjournalPrinter(
                            new java.util.ArrayList<>(plan.outpayments()), plan.journalNumber(), plan.to()),
                            output, overwrite));
                }
                if (commit) {
                    OutpaymentJournalResult committed = service.commitJournal(plan);
                    result.put("voucherNumber", committed.voucherNumber());
                }
                root.output(result, commit
                        ? "Committed outpayment journal " + plan.journalNumber() + " with voucher "
                                + result.get("voucherNumber") + " for " + plan.outpayments().size()
                                + " outpayments"
                        : "Outpayment journal " + plan.journalNumber() + " preview\nOutpayments: "
                                + plan.outpayments().size() + "\nNo changes written");
                return 0;
            } catch (IllegalArgumentException exception) {
                throw new CliException("INPAYMENT_JOURNAL_INVALID", exception.getMessage(), exception);
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "vat", description = "Calculate and settle VAT",
            subcommands = {VatReportCommand.class, VatControlCommand.class, VatSettle.class})
    static class VatCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A VAT command is required");
        }
    }

    abstract static class VatPeriodCommand implements Callable<Integer> {
        @CommandLine.ParentCommand VatCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        BokfriCli root() { return command.parent; }
    }

    @Command(mixinStandardHelpOptions = true, name = "report", description = "Calculate VAT for a period or the selected accounting year")
    static class VatReportCommand implements Callable<Integer> {
        @CommandLine.ParentCommand VatCommand command;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        @Option(names = "--output", description = "Write the VAT report as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            if ((from == null) != (to == null)) {
                throw new CliException("VAT_REPORT_PERIOD_INVALID",
                        "Provide both --from and --to, or omit both to report the full accounting year");
            }
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                java.time.LocalDate selectedFrom = from == null ? year.getLocalFrom() : from;
                java.time.LocalDate selectedTo = to == null ? year.getLocalTo() : to;
                if (selectedFrom.isBefore(year.getLocalFrom()) || selectedTo.isAfter(year.getLocalTo())) {
                    throw new CliException("VAT_REPORT_PERIOD_INVALID",
                            "VAT report period must be within the selected accounting year "
                                    + year.getLocalFrom() + " - " + year.getLocalTo());
                }
                runtime.database().init(false);
                var report = new VatService(runtime.database()).report(selectedFrom, selectedTo);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("from", selectedFrom);
                result.put("to", selectedTo);
                result.put("boxes", vatBoxes(report.boxes()));
                result.put("vatToPayOrRefund", money(report.vatToPayOrRefund()));
                result.put("selection", selectedContext(context, company, year));
                if (output != null) {
                    addPdf(result, exportPdf(new SSVATReport2015Printer(
                            year, selectedFrom, selectedTo, 1), output, overwrite));
                }
                root.output(result, "VAT report " + selectedFrom + " - " + selectedTo
                        + "\nVAT to pay/refund: " + report.vatToPayOrRefund());
                return 0;
            } catch (CliException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                throw new CliException("VAT_REPORT_PERIOD_INVALID", exception.getMessage(), exception);
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "control",
            description = "Generate the VAT control report")
    static class VatControlCommand implements Callable<Integer> {
        @CommandLine.ParentCommand VatCommand command;
        @Option(names="--from") java.time.LocalDate from;
        @Option(names="--to") java.time.LocalDate to;
        @Option(names="--start-voucher",defaultValue="1") int startVoucher;
        @Option(names="--output",required=true) java.nio.file.Path output;
        @Option(names="--overwrite") boolean overwrite;
        public Integer call(){BokfriCli root=command.parent;ResolvedContext c=root.resolveContext(true,true);try(BokfriRuntime r=root.openRuntime(c.dataDir())){SSNewCompany co=r.selectCompany(c.companyId());SSNewAccountingYear y=r.selectYear(co,c.yearId());java.time.LocalDate selectedFrom=from==null?y.getLocalFrom():from;java.time.LocalDate selectedTo=to==null?y.getLocalTo():to;if((from==null)!=(to==null))throw new CliException("VAT_CONTROL_PERIOD_INVALID","Provide both --from and --to, or omit both");r.database().init(false);java.nio.file.Path pdf=exportPdf(new SSVATControl2015Printer(y,selectedFrom,selectedTo,startVoucher),output,overwrite);Map<String,Object>x=new LinkedHashMap<>();x.put("from",selectedFrom);x.put("to",selectedTo);x.put("startVoucher",startVoucher);x.put("output",pdf.toString());x.put("bytes",Files.size(pdf));x.put("selection",selectedContext(c,co,y));root.output(x,"Created VAT control PDF "+pdf);return 0;}catch(CliException e){throw e;}catch(Exception e){throw databaseFailure(e);}}}

    @Command(mixinStandardHelpOptions = true, name = "settle")
    static class VatSettle extends VatPeriodCommand {
        @Option(names = "--commit") boolean commit;
        public Integer call() {
            BokfriCli root = root();
            ResolvedContext c = root.resolveContext(true, true);
            try (BokfriRuntime r = root.openRuntime(c.dataDir())) {
                SSNewCompany co = r.selectCompany(c.companyId());
                SSNewAccountingYear y = r.selectYear(co, c.yearId());
                r.database().init(false);
                VatService s = new VatService(r.database());
                VatSettlementPlan p = s.plan(from, to);
                Map<String, Object> x = vatSettlementDetails(p);
                x.put("committed", commit);
                x.put("selection", selectedContext(c, co, y));
                if (commit) x.put("voucherNumber", s.commit(p).voucherNumber());
                root.output(x, commit ? "Committed VAT settlement voucher " + x.get("voucherNumber")
                        : "VAT settlement preview; no changes written");
                return 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new CliException("VAT_SETTLEMENT_INVALID", e.getMessage(), e);
            } catch (Exception e) {
                throw databaseFailure(e);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "voucher", description = "Inspect, validate, and create manual vouchers",
            subcommands = {VoucherList.class, VoucherShow.class,
                    VoucherValidate.class, VoucherCreate.class, CliInputSchemas.Voucher.class})
    static class VoucherCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A voucher command is required");
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "list", description = "List vouchers in the selected accounting year")
    static class VoucherList implements Callable<Integer> {
        @CommandLine.ParentCommand VoucherCommand command;
        @Option(names = "--from", description = "Only vouchers on or after this date")
        java.time.LocalDate from;
        @Option(names = "--to", description = "Only vouchers on or before this date")
        java.time.LocalDate to;
        @Option(names = "--limit", description = "Maximum results; by default all are returned")
        Integer limit;
        @Option(names = "--output", description = "Write the GUI voucher list as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        @Override public Integer call() {
            if (limit != null && limit < 1) {
                throw new CliException("LIMIT_INVALID", "--limit must be at least 1");
            }
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                VoucherService service = new VoucherService(runtime.database());
                java.util.stream.Stream<SSVoucher> filtered = service.list().stream()
                        .filter(voucher -> from == null || !voucher.getLocalDate().isBefore(from))
                        .filter(voucher -> to == null || !voucher.getLocalDate().isAfter(to));
                if (limit != null) {
                    filtered = filtered.limit(limit);
                }
                List<Map<String, Object>> vouchers = filtered.map(BokfriCli::voucherSummary).toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("selection", selectedContext(context, company, year));
                result.put("count", vouchers.size());
                result.put("vouchers", vouchers);
                if (output != null) {
                    List<SSVoucher> selectedVouchers = filteredVouchers(service.list(), from, to, limit);
                    SSVoucherListPrinter printer = new SSVoucherListPrinter(
                            new java.util.ArrayList<>(selectedVouchers));
                    addPdf(result, exportPdf(printer, output, overwrite));
                }
                String text = table(vouchers, "No vouchers found", right("Number", "number"),
                        left("Date", "date"), left("Description", "description"),
                        right("Debit", "debitTotal"), right("Credit", "creditTotal"));
                root.output(result, text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "show", description = "Show one voucher by number")
    static class VoucherShow implements Callable<Integer> {
        @CommandLine.ParentCommand VoucherCommand command;
        @Parameters(index = "0", description = "Voucher number")
        int number;
        @Option(names = "--output", description = "Write the GUI voucher as PDF")
        java.nio.file.Path output;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                VoucherService service = new VoucherService(runtime.database());
                SSVoucher voucher = service.find(number).orElseThrow(() ->
                        new CliException("VOUCHER_NOT_FOUND", "No voucher has number " + number));
                Map<String, Object> result = voucherDetails(voucher);
                result.put("selection", selectedContext(context, company, year));
                if (output != null) {
                    addPdf(result, exportPdf(new SSVoucherPrinter(voucher,
                            se.swedsoft.bookkeeping.gui.util.SSBundle.getBundle()
                                    .getString("voucherreport.title")), output, overwrite));
                }
                root.output(result, voucherDetailsText(voucher));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    abstract static class VoucherOperation implements Callable<Integer> {
        @CommandLine.ParentCommand VoucherCommand command;
        @Option(names = "--file", required = true, description = "Voucher JSON file, or - for stdin")
        String file;

        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            VoucherInput input = readVoucherInput(file);
            try (BokfriRuntime runtime = root.openRuntime(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                runtime.database().init(false);
                SSVoucher voucher = toVoucher(input, runtime);
                VoucherService service = new VoucherService(runtime.database());
                VoucherValidationResult validation = service.validate(voucher);
                Map<String, Object> result = voucherResult(context, company, year, voucher,
                        validation, persist(), service.nextNumber());
                if (!validation.valid()) {
                    throw validationFailure(validation);
                }
                if (persist()) {
                    service.create(voucher);
                    result.put("number", voucher.getNumber());
                    result.put("created", true);
                }
                root.output(result, voucherText(result, validation));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, name = "validate", description = "Validate a voucher JSON file without writing")
    static class VoucherValidate extends VoucherOperation {
        @Override boolean persist() { return false; }
    }

    @Command(mixinStandardHelpOptions = true, name = "create", description = "Create a validated manual voucher")
    static class VoucherCreate extends VoucherOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    private static OutpaymentInput readOutpaymentInput(String file) {
        try {
            OutpaymentInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, OutpaymentInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), OutpaymentInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported outpayment schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) { throw exception; }
        catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read outpayment JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSOutpayment toOutpayment(OutpaymentInput input, BokfriRuntime runtime) {
        SSOutpayment item = new SSOutpayment();
        item.setLocalDate(input.getDate());
        item.setText(normalized(input.getText()));
        List<SSOutpaymentRow> rows = new java.util.ArrayList<>();
        for (OutpaymentInput.Row inputRow : input.getRows()) {
            SSSupplierInvoice invoice = new SupplierInvoiceService(runtime.database()).find(inputRow.getInvoiceNumber())
                    .orElseThrow(() -> new CliException("OUTPAYMENT_INVOICE_NOT_FOUND",
                            "No invoice has number " + inputRow.getInvoiceNumber()));
            SSOutpaymentRow row = new SSOutpaymentRow(invoice);
            row.setValue(inputRow.getAmount());
            if (inputRow.getCurrencyRate() != null) {
                row.setCurrencyRate(inputRow.getCurrencyRate());
            }
            rows.add(row);
        }
        item.setRows(rows);
        item.generateVoucher();
        return item;
    }

    private static CliException outpaymentValidationFailure(OutpaymentValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        OutpaymentValidationIssue first = validation.issues().get(0);
        return new CliException("OUTPAYMENT_INVALID", first.message(), details);
    }

    private static Map<String, Object> outpaymentDetails(SSOutpayment item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", item.getNumber());
        result.put("date", item.getLocalDate());
        result.put("text", item.getText());
        result.put("entered", item.isEntered());
        result.put("total", money(se.swedsoft.bookkeeping.calc.math.SSOutpaymentMath.getSum(item)));
        result.put("rows", item.getRows().stream().map(row -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("invoiceNumber", row.getInvoiceNr());
            value.put("amount", money(row.getValue()));
            value.put("currencyRate", decimal(row.getCurrencyRate()));
            return value;
        }).toList());
        return result;
    }

    private static Map<String, Object> outpaymentJournalDetails(OutpaymentJournalPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journalNumber", plan.journalNumber());
        result.put("from", plan.from());
        result.put("to", plan.to());
        result.put("outpaymentNumbers", plan.outpayments().stream().map(SSOutpayment::getNumber).toList());
        result.put("outpaymentCount", plan.outpayments().size());
        result.put("debitTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(plan.voucher())));
        result.put("creditTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(plan.voucher())));
        return result;
    }

    private static Map<String, Object> backupDetails(BackupDetails backup) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", backup.path().toString());
        result.put("createdAt", backup.createdAt());
        result.put("size", backup.size());
        result.put("exists", backup.exists());
        return result;
    }

    private static Map<String, Object> sieImportDetails(SieImportPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", plan.file().toString());
        result.put("type", plan.type());
        result.put("sourceMarkedImported", plan.sourceMarkedImported());
        result.put("previouslyImported", plan.previouslyImported());
        result.put("sha256", plan.sha256());
        result.put("accounts", plan.accounts());
        result.put("vouchers", plan.vouchers());
        result.put("transactions", plan.transactions());
        result.put("vouchersOnly", plan.vouchersOnly());
        return result;
    }

    private static SIEType parseSieType(String value) {
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "1" -> SIEType.SIE_1;
            case "2" -> SIEType.SIE_2;
            case "3" -> SIEType.SIE_3;
            case "4E", "4" -> SIEType.SIE_4E;
            default -> throw new CliException("SIE_TYPE_INVALID", "SIE type must be 1, 2, 3, or 4E");
        };
    }

    private static OpeningBalanceInput readOpeningBalanceInput(String file){try{OpeningBalanceInput i="-".equals(file)?jsonMapper().readValue(System.in,OpeningBalanceInput.class):jsonMapper().readValue(Paths.get(file).toFile(),OpeningBalanceInput.class);if(i.getSchemaVersion()!=1)throw new CliException("INPUT_SCHEMA_UNSUPPORTED","Unsupported opening balance schemaVersion: "+i.getSchemaVersion());return CliInputValidator.validate(i);}catch(CliException e){throw e;}catch(IOException e){throw new CliException("INPUT_INVALID","Could not read opening balance JSON: "+e.getMessage(),e);}}
    private static Map<String,Object> openingBalanceDetails(OpeningBalancePlan p){Map<String,Object>x=new LinkedHashMap<>();x.put("balances",p.balances().stream().map(b->Map.<String,Object>of("account",b.account(),"description",b.description(),"amount",money(b.amount()))).toList());x.put("debitTotal",money(p.debitTotal()));x.put("creditTotal",money(p.creditTotal()));x.put("difference",money(p.difference()));x.put("adjustment",p.adjustment()==null?null:Map.of("account",p.adjustment().account(),"description",p.adjustment().description(),"before",money(p.adjustment().before()),"after",money(p.adjustment().after()),"amount",money(p.adjustment().amount())));return x;}

    private static CompanyInput readCompanyInput(String file){try{CompanyInput i="-".equals(file)?jsonMapper().readValue(System.in,CompanyInput.class):jsonMapper().readValue(Paths.get(file).toFile(),CompanyInput.class);if(i.getSchemaVersion()!=1)throw new CliException("INPUT_SCHEMA_UNSUPPORTED","Unsupported company schemaVersion: "+i.getSchemaVersion());return CliInputValidator.validate(i);}catch(CliException e){throw e;}catch(IOException e){throw new CliException("INPUT_INVALID","Could not read company JSON: "+e.getMessage(),e);}}
    private static AccountingYearInput readAccountingYearInput(String file){try{AccountingYearInput i="-".equals(file)?jsonMapper().readValue(System.in,AccountingYearInput.class):jsonMapper().readValue(Paths.get(file).toFile(),AccountingYearInput.class);if(i.getSchemaVersion()!=1)throw new CliException("INPUT_SCHEMA_UNSUPPORTED","Unsupported accounting year schemaVersion: "+i.getSchemaVersion());return CliInputValidator.validate(i);}catch(CliException e){throw e;}catch(IOException e){throw new CliException("INPUT_INVALID","Could not read accounting year JSON: "+e.getMessage(),e);}}

    private static SupplierInvoiceInput readSupplierInvoiceInput(String file){try{SupplierInvoiceInput i="-".equals(file)?jsonMapper().readValue(System.in,SupplierInvoiceInput.class):jsonMapper().readValue(Paths.get(file).toFile(),SupplierInvoiceInput.class);if(i.getSchemaVersion()!=1)throw new CliException("INPUT_SCHEMA_UNSUPPORTED","Unsupported supplier invoice schemaVersion: "+i.getSchemaVersion());return CliInputValidator.validate(i);}catch(CliException e){throw e;}catch(IOException e){throw new CliException("INPUT_INVALID","Could not read supplier invoice JSON: "+e.getMessage(),e);}}
    private static SupplierCreditInvoiceInput readSupplierCreditInvoiceInput(String file){try{SupplierCreditInvoiceInput i="-".equals(file)?jsonMapper().readValue(System.in,SupplierCreditInvoiceInput.class):jsonMapper().readValue(Paths.get(file).toFile(),SupplierCreditInvoiceInput.class);if(i.getSchemaVersion()!=1)throw new CliException("INPUT_SCHEMA_UNSUPPORTED","Unsupported supplier credit invoice schemaVersion: "+i.getSchemaVersion());if(i.getSupplierInvoiceNumber()==null)throw new CliException("SUPPLIER_CREDIT_INVOICE_INVALID","supplierInvoiceNumber is required");return CliInputValidator.validate(i);}catch(CliException e){throw e;}catch(IOException e){throw new CliException("INPUT_INVALID","Could not read supplier credit invoice JSON: "+e.getMessage(),e);}}
    private static SSSupplierInvoice toSupplierInvoice(SupplierInvoiceInput in,BokfriRuntime r){SSSupplierInvoice i=new SSSupplierInvoice();SSSupplier s=new SupplierService(r.database()).find(in.getSupplierNumber()).orElseThrow(()->new CliException("SUPPLIER_INVOICE_SUPPLIER_NOT_FOUND","No supplier has number "+in.getSupplierNumber()));i.setSupplier(s);i.setPaymentTerm(s.getPaymentTerm());i.setLocalDate(in.getDate());if(in.getDueDate()!=null)i.setLocalDueDate(in.getDueDate());else i.setDueDate();i.setReferencenumber(normalized(in.getReference()));i.setTaxSum(in.getVat()==null?java.math.BigDecimal.ZERO:in.getVat());i.setRoundingSum(in.getRounding()==null?java.math.BigDecimal.ZERO:in.getRounding());i.setCurrencyRate(i.getCurrency()==null?java.math.BigDecimal.ONE:i.getCurrency().getExchangeRate());List<SSSupplierInvoiceRow> rows=new java.util.ArrayList<>();for(var x:in.getRows()){SSSupplierInvoiceRow row=new SSSupplierInvoiceRow();if(x.getProductNumber()!=null)row.setProduct(r.database().getProduct(x.getProductNumber()).orElseThrow(()->new CliException("SUPPLIER_INVOICE_PRODUCT_NOT_FOUND","No product has number "+x.getProductNumber())));if(x.getDescription()!=null)row.setDescription(normalized(x.getDescription()));if(x.getQuantity()!=null)row.setQuantity(x.getQuantity());else if(row.getQuantity()==null)row.setQuantity(1);if(x.getUnitPrice()!=null)row.setUnitprice(x.getUnitPrice());if(x.getFreight()!=null)row.setUnitFreight(x.getFreight());if(x.getAccount()!=null)row.setAccount(r.database().getAccounts().stream().filter(a->x.getAccount().equals(a.getNumber())).findFirst().orElseThrow(()->new CliException("SUPPLIER_INVOICE_ACCOUNT_NOT_FOUND","No account has number "+x.getAccount())));rows.add(row);}i.setRows(rows);i.generateVoucher();return i;}
    private static CliException supplierInvoiceValidationFailure(SupplierInvoiceValidationResult v){Map<String,Object>d=new LinkedHashMap<>();d.put("valid",false);d.put("issues",v.issues());SupplierInvoiceValidationIssue f=v.issues().get(0);return new CliException("SUPPLIER_INVOICE_INVALID",f.message(),d);}

    private static SupplierInput readSupplierInput(String file) {
        try {
            SupplierInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, SupplierInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), SupplierInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported supplier schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) { throw exception; }
        catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read supplier JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSSupplier toSupplier(SupplierInput input, BokfriRuntime runtime,
            SupplierService service) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(normalized(input.getNumber()));
        supplier.setName(normalized(input.getName()));
        supplier.setRegistrationNumber(normalized(input.getRegistrationNumber()));
        supplier.setEMail(normalized(input.getEmail()));
        supplier.setPhone1(normalized(input.getPhone()));
        supplier.setHomepage(normalized(input.getHomepage()));
        if (input.getOurContact() != null) { supplier.setOurContact(normalized(input.getOurContact())); }
        supplier.setYourContact(normalized(input.getYourContact()));
        supplier.setOurCustomerNr(normalized(input.getOurCustomerNumber()));
        supplier.setBankGiro(normalized(input.getBankgiro()));
        supplier.setPlusGiro(normalized(input.getPlusgiro()));
        supplier.setComment(normalized(input.getComment()));
        supplier.setOutpaymentNumber(input.getOutpaymentNumber() == null
                ? service.nextOutpaymentNumber() : input.getOutpaymentNumber());
        if (input.getAddress() != null) {
            SSAddress address = toAddress(input.getAddress());
            if (address.getName().isBlank()) { address.setName(orEmpty(input.getName())); }
            supplier.setAddress(address);
        }
        if (input.getCurrency() != null) {
            supplier.setCurrency(runtime.database().getCurrencies().stream()
                    .filter(item -> input.getCurrency().equalsIgnoreCase(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("SUPPLIER_CURRENCY_NOT_FOUND",
                            "No currency has code " + input.getCurrency())));
        }
        if (input.getPaymentTerms() != null) {
            supplier.setPaymentTerm(runtime.database().getPaymentTerms().stream()
                    .filter(item -> input.getPaymentTerms().equals(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("SUPPLIER_PAYMENT_TERMS_NOT_FOUND",
                            "No payment terms have code " + input.getPaymentTerms())));
        }
        return supplier;
    }

    private static CliException supplierValidationFailure(SupplierValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        SupplierValidationIssue first = validation.issues().get(0);
        return new CliException("SUPPLIER_INVALID", first.message(), details);
    }

    private static InpaymentInput readInpaymentInput(String file) {
        try {
            InpaymentInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, InpaymentInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), InpaymentInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported inpayment schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) { throw exception; }
        catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read inpayment JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSInpayment toInpayment(InpaymentInput input, BokfriRuntime runtime) {
        SSInpayment item = new SSInpayment();
        item.setLocalDate(input.getDate());
        item.setText(normalized(input.getText()));
        List<SSInpaymentRow> rows = new java.util.ArrayList<>();
        for (InpaymentInput.Row inputRow : input.getRows()) {
            SSInvoice invoice = new InvoiceService(runtime.database()).find(inputRow.getInvoiceNumber())
                    .orElseThrow(() -> new CliException("OUTPAYMENT_INVOICE_NOT_FOUND",
                            "No invoice has number " + inputRow.getInvoiceNumber()));
            SSInpaymentRow row = new SSInpaymentRow(invoice);
            row.setValue(inputRow.getAmount());
            if (inputRow.getCurrencyRate() != null) {
                row.setCurrencyRate(inputRow.getCurrencyRate());
            }
            rows.add(row);
        }
        item.setRows(rows);
        item.generateVoucher();
        return item;
    }

    private static CliException inpaymentValidationFailure(InpaymentValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        InpaymentValidationIssue first = validation.issues().get(0);
        return new CliException("OUTPAYMENT_INVALID", first.message(), details);
    }

    private static ProductInput readProductInput(String file) {
        try {
            ProductInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, ProductInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), ProductInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported product schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read product JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSProduct toProduct(ProductInput input, BokfriRuntime runtime) {
        SSProduct product = new SSProduct();
        product.setNumber(normalized(input.getNumber()));
        product.setDescription(normalized(input.getDescription()));
        if (input.getSellingPrice() != null) {
            product.setSellingPrice(input.getSellingPrice());
        }
        if (input.getVatRate() != null) {
            product.setTaxCode(taxCode(input.getVatRate(), runtime.database().getCurrentCompany()));
        }
        if (input.getUnit() != null) {
            product.setUnit(runtime.database().getUnits().stream()
                    .filter(item -> input.getUnit().equals(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("PRODUCT_UNIT_NOT_FOUND",
                            "No unit has code " + input.getUnit())));
        }
        if (input.getSalesAccount() != null) {
            SSAccount account = runtime.database().getAccounts().stream()
                    .filter(item -> input.getSalesAccount().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("PRODUCT_ACCOUNT_NOT_FOUND",
                            "No account has number " + input.getSalesAccount()));
            product.setDefaultAccount(SSDefaultAccount.Sales, account);
        }
        if (input.getProject() != null) {
            product.setProject(runtime.database().getProjects().stream()
                    .filter(item -> input.getProject().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("PRODUCT_PROJECT_NOT_FOUND",
                            "No project has number " + input.getProject())));
        }
        if (input.getResultUnit() != null) {
            product.setResultUnit(runtime.database().getResultUnits().stream()
                    .filter(item -> input.getResultUnit().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("PRODUCT_RESULT_UNIT_NOT_FOUND",
                            "No result unit has number " + input.getResultUnit())));
        }
        if (input.getStockProduct() != null) {
            product.setStockProduct(input.getStockProduct());
        }
        if (input.getExpired() != null) {
            product.setExpired(input.getExpired());
        }
        if (input.getWeight() != null) {
            product.setWeight(input.getWeight());
        }
        if (input.getVolume() != null) {
            product.setVolume(input.getVolume());
        }
        return product;
    }

    private static CliException productValidationFailure(ProductValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        ProductValidationIssue first = validation.issues().get(0);
        return new CliException("PRODUCT_INVALID", first.message(), details);
    }

    private static CreditInvoiceInput readCreditInvoiceInput(String file) {
        try {
            CreditInvoiceInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, CreditInvoiceInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), CreditInvoiceInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported credit invoice schemaVersion: " + input.getSchemaVersion());
            }
            if (input.getInvoiceNumber() == null) {
                throw new CliException("CREDIT_INVOICE_INVALID", "invoiceNumber is required");
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CliException("INPUT_INVALID", exception.getMessage(), exception);
        }
    }

    private static InvoiceInput readInvoiceInput(String file) {
        try {
            InvoiceInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, InvoiceInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), InvoiceInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported invoice schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read invoice JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSInvoice toInvoice(InvoiceInput input, BokfriRuntime runtime) {
        SSInvoice invoice = new SSInvoice(se.swedsoft.bookkeeping.data.common.SSInvoiceType.NORMAL);
        SSCustomer customer = runtime.database().getCustomer(input.getCustomerNumber())
                .orElseThrow(() -> new CliException("INVOICE_CUSTOMER_NOT_FOUND",
                        "No customer has number " + input.getCustomerNumber()));
        invoice.setCustomer(customer);
        invoice.setLocalDate(input.getDate());
        if (input.getDueDate() != null) {
            invoice.setLocalDueDate(input.getDueDate());
        } else if (input.getDate() != null) {
            invoice.setDueDate();
        }
        invoice.setYourOrderNumber(normalized(input.getYourOrderNumber()));
        if (input.getText() != null) {
            invoice.setText(normalized(input.getText()));
        }
        invoice.setCurrencyRate(invoice.getCurrency() == null
                ? java.math.BigDecimal.ONE : invoice.getCurrency().getExchangeRate());
        List<SSSaleRow> rows = new java.util.ArrayList<>();
        for (int index = 0; index < input.getRows().size(); index++) {
            rows.add(toInvoiceRow(input.getRows().get(index), index + 1, runtime));
        }
        invoice.setRows(rows);
        invoice.generateVoucher();
        return invoice;
    }

    private static SSSaleRow toInvoiceRow(InvoiceInput.Row input, int rowNumber,
            BokfriRuntime runtime) {
        SSSaleRow row = new SSSaleRow();
        if (input.getProductNumber() != null) {
            SSProduct product = runtime.database().getProduct(input.getProductNumber())
                    .orElseThrow(() -> new CliException("INVOICE_PRODUCT_NOT_FOUND",
                            "No product has number " + input.getProductNumber()));
            row.setProduct(product);
        }
        if (input.getDescription() != null) {
            row.setDescription(normalized(input.getDescription()));
        }
        if (input.getQuantity() != null) {
            row.setQuantity(input.getQuantity());
        } else if (row.getQuantity() == null) {
            row.setQuantity(1);
        }
        if (input.getUnitPrice() != null) {
            row.setUnitprice(input.getUnitPrice());
        }
        if (input.getDiscount() != null) {
            row.setDiscount(input.getDiscount());
        }
        if (input.getSalesAccount() != null) {
            SSAccount account = runtime.database().getAccounts().stream()
                    .filter(item -> input.getSalesAccount().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("INVOICE_ACCOUNT_NOT_FOUND",
                            "No account has number " + input.getSalesAccount()));
            row.setAccount(account);
        }
        if (input.getVatRate() != null) {
            row.setTaxCode(taxCode(input.getVatRate(), runtime.database().getCurrentCompany()));
        }
        if (input.getUnit() != null) {
            se.swedsoft.bookkeeping.data.common.SSUnit unit = runtime.database().getUnits().stream()
                    .filter(item -> input.getUnit().equals(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("INVOICE_UNIT_NOT_FOUND",
                            "No unit has code " + input.getUnit()));
            row.setUnit(unit);
        }
        if (input.getProject() != null) {
            SSNewProject project = runtime.database().getProjects().stream()
                    .filter(item -> input.getProject().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("INVOICE_PROJECT_NOT_FOUND",
                            "No project has number " + input.getProject()));
            row.setProject(project);
        }
        if (input.getResultUnit() != null) {
            SSNewResultUnit resultUnit = runtime.database().getResultUnits().stream()
                    .filter(item -> input.getResultUnit().equals(item.getNumber()))
                    .findFirst().orElseThrow(() -> new CliException("INVOICE_RESULT_UNIT_NOT_FOUND",
                            "No result unit has number " + input.getResultUnit()));
            row.setResultUnit(resultUnit);
        }
        if (row.getDescription() == null && input.getProductNumber() == null) {
            throw new CliException("INVOICE_ROW_INVALID",
                    "Invoice row " + rowNumber + " needs productNumber or description");
        }
        return row;
    }

    private static se.swedsoft.bookkeeping.data.common.SSTaxCode taxCode(
            java.math.BigDecimal rate, SSNewCompany company) {
        if (rate.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_0;
        }
        if (rate.compareTo(company.getTaxRate1()) == 0) {
            return se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_1;
        }
        if (rate.compareTo(company.getTaxRate2()) == 0) {
            return se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_2;
        }
        if (rate.compareTo(company.getTaxRate3()) == 0) {
            return se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_3;
        }
        throw new CliException("INVOICE_VAT_RATE_NOT_FOUND",
                "Company has no VAT rate " + rate.toPlainString());
    }

    private static CliException invoiceValidationFailure(InvoiceValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        InvoiceValidationIssue first = validation.issues().get(0);
        return new CliException("INVOICE_INVALID", first.message(), details);
    }

    private static CustomerInput readCustomerInput(String file) {
        try {
            CustomerInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, CustomerInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), CustomerInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported customer schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read customer JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSCustomer toCustomer(CustomerInput input, BokfriRuntime runtime) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(normalized(input.getNumber()));
        customer.setName(normalized(input.getName()));
        customer.setRegistrationNumber(normalized(input.getRegistrationNumber()));
        customer.setVATNumber(normalized(input.getVatNumber()));
        customer.setEMail(normalized(input.getEmail()));
        customer.setPhone1(normalized(input.getPhone()));
        if (input.getOurContact() != null) {
            customer.setOurContactPerson(normalized(input.getOurContact()));
        }
        customer.setYourContactPerson(normalized(input.getYourContact()));
        customer.setComment(normalized(input.getComment()));
        customer.setDiscount(input.getDiscount());
        customer.setTaxFree(Boolean.TRUE.equals(input.getTaxFree()));
        if (input.getInvoiceAddress() != null) {
            customer.setInvoiceAddress(toAddress(input.getInvoiceAddress()));
        }
        if (input.getDeliveryAddress() != null) {
            customer.setDeliveryAddress(toAddress(input.getDeliveryAddress()));
        }
        if (input.getCurrency() != null) {
            SSCurrency currency = runtime.database().getCurrencies().stream()
                    .filter(item -> input.getCurrency().equalsIgnoreCase(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("CUSTOMER_CURRENCY_NOT_FOUND",
                            "No currency has code " + input.getCurrency()));
            customer.setInvoiceCurrency(currency);
        }
        if (input.getPaymentTerms() != null) {
            SSPaymentTerm paymentTerm = runtime.database().getPaymentTerms().stream()
                    .filter(item -> input.getPaymentTerms().equals(item.getName()))
                    .findFirst().orElseThrow(() -> new CliException("CUSTOMER_PAYMENT_TERMS_NOT_FOUND",
                            "No payment terms have code " + input.getPaymentTerms()));
            customer.setPaymentTerm(paymentTerm);
        }
        return customer;
    }

    private static SSAddress toAddress(CustomerInput.Address input) {
        SSAddress address = new SSAddress();
        address.setName(orEmpty(input.getName()));
        address.setAddress1(orEmpty(input.getAddress1()));
        address.setAddress2(orEmpty(input.getAddress2()));
        address.setZipCode(orEmpty(input.getPostalCode()));
        address.setCity(orEmpty(input.getCity()));
        address.setCountry(orEmpty(input.getCountry()));
        return address;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orEmpty(String value) {
        String normalized = normalized(value);
        return normalized == null ? "" : normalized;
    }

    private static CliException customerValidationFailure(CustomerValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        CustomerValidationIssue first = validation.issues().get(0);
        return new CliException("CUSTOMER_INVALID", first.message(), details);
    }

    private static VoucherInput readVoucherInput(String file) {
        try {
            VoucherInput input = "-".equals(file)
                    ? jsonMapper().readValue(System.in, VoucherInput.class)
                    : jsonMapper().readValue(Paths.get(file).toFile(), VoucherInput.class);
            if (input.getSchemaVersion() != 1) {
                throw new CliException("INPUT_SCHEMA_UNSUPPORTED",
                        "Unsupported voucher schemaVersion: " + input.getSchemaVersion());
            }
            return CliInputValidator.validate(input);
        } catch (CliException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CliException("INPUT_INVALID", "Could not read voucher JSON: "
                    + exception.getMessage(), exception);
        }
    }

    private static SSVoucher toVoucher(VoucherInput input, BokfriRuntime runtime) {
        SSVoucher voucher = new SSVoucher(0);
        voucher.setLocalDate(input.getDate());
        voucher.setDescription(input.getDescription());
        List<SSAccount> accounts = runtime.database().getAccounts();
        List<SSNewProject> projects = runtime.database().getProjects();
        List<SSNewResultUnit> resultUnits = runtime.database().getResultUnits();
        for (VoucherInput.Row inputRow : input.getRows()) {
            SSVoucherRow row = new SSVoucherRow();
            row.setAccount(accounts.stream()
                    .filter(account -> account.getNumber().equals(inputRow.getAccount()))
                    .findFirst().orElse(null));
            row.setDebet(inputRow.getDebit());
            row.setCredit(inputRow.getCredit());
            if (inputRow.getProject() != null) {
                row.setProject(projects.stream()
                        .filter(project -> inputRow.getProject().equals(project.getNumber()))
                        .findFirst().orElse(null));
            }
            if (inputRow.getResultUnit() != null) {
                row.setResultUnit(resultUnits.stream()
                        .filter(unit -> inputRow.getResultUnit().equals(unit.getNumber()))
                        .findFirst().orElse(null));
            }
            voucher.addVoucherRow(row);
        }
        return voucher;
    }

    private static Map<String, Object> selectedCompanyContext(ResolvedContext context,
            SSNewCompany company) {
        Map<String, Object> selected = context.asMap();
        selected.put("companyName", company.getName());
        return selected;
    }

    private static Map<String, Object> customerSummary(SSCustomer customer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", customer.getNumber());
        result.put("name", customer.getName());
        result.put("email", customer.getEMail());
        result.put("registrationNumber", customer.getRegistrationNumber());
        result.put("vatNumber", customer.getVATNumber());
        return result;
    }

    private static Map<String, Object> customerDetails(SSCustomer customer) {
        Map<String, Object> result = customerSummary(customer);
        result.put("phone", customer.getPhone1());
        result.put("ourContact", customer.getOurContactPerson());
        result.put("yourContact", customer.getYourContactPerson());
        result.put("discount", decimal(customer.getDiscount()));
        result.put("currency", customer.getInvoiceCurrency() == null
                ? null : customer.getInvoiceCurrency().getName());
        result.put("comment", customer.getComment());
        result.put("invoiceAddress", customer.getInvoiceAddress());
        result.put("deliveryAddress", customer.getDeliveryAddress());
        return result;
    }

    private static Map<String, Object> productDetails(SSProduct product) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", product.getNumber());
        result.put("description", product.getDescription());
        result.put("sellingPrice", money(product.getSellingPrice()));
        result.put("taxCode", product.getTaxCode() == null ? null : product.getTaxCode().name());
        result.put("taxRate", product.getTaxRate().map(BokfriCli::decimal).orElse(null));
        result.put("unit", product.getUnit() == null ? null : product.getUnit().getName());
        result.put("salesAccount", product.getDefaultAccount(SSDefaultAccount.Sales));
        result.put("project", product.getProjectNr());
        result.put("resultUnit", product.getResultUnitNr());
        result.put("expired", product.isExpired());
        return result;
    }

    private static Map<String,Object> supplierInvoiceDetails(SSSupplierInvoice i){Map<String,Object>x=new LinkedHashMap<>();x.put("number",i.getNumber());x.put("date",i.getLocalDate());x.put("dueDate",i.getLocalDueDate());x.put("supplierNumber",i.getSupplierNr());x.put("supplierName",i.getSupplierName());x.put("reference",i.getReferencenumber());x.put("entered",i.isEntered());x.put("net",money(se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getNetSum(i)));x.put("vat",money(i.getTaxSum()));x.put("total",money(se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getTotalSum(i)));x.put("balance",i.getNumber()==null?null:money(se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getSaldo(i)));x.put("rows",i.getRows().stream().map(r->Map.of("description",r.getDescription(),"quantity",r.getQuantity(),"unitPrice",money(r.getUnitprice()),"account",r.getAccountNr())).toList());return x;}
    private static Map<String,Object> supplierInvoiceJournalDetails(SupplierInvoiceJournalPlan p){Map<String,Object>x=new LinkedHashMap<>();x.put("journalNumber",p.journalNumber());x.put("invoiceNumbers",p.invoices().stream().map(SSSupplierInvoice::getNumber).toList());x.put("debitTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(p.voucher())));x.put("creditTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(p.voucher())));return x;}
    private static Map<String,Object> supplierCreditInvoiceDetails(SSSupplierCreditInvoice i){Map<String,Object>x=supplierInvoiceDetails(i);x.put("creditingSupplierInvoiceNumber",i.getCreditingNr());return x;}

    private static Map<String, Object> supplierDetails(SSSupplier supplier) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", supplier.getNumber());
        result.put("name", supplier.getName());
        result.put("registrationNumber", supplier.getRegistrationNumber());
        result.put("email", supplier.getEMail());
        result.put("phone", supplier.getPhone1());
        result.put("homepage", supplier.getHomepage());
        result.put("ourContact", supplier.getOurContact());
        result.put("yourContact", supplier.getYourContact());
        result.put("ourCustomerNumber", supplier.getOurCustomerNr());
        result.put("bankgiro", supplier.getBankgiro());
        result.put("plusgiro", supplier.getPlusgiro());
        result.put("outpaymentNumber", supplier.getOutpaymentNumber());
        result.put("currency", supplier.getCurrency() == null ? null : supplier.getCurrency().getName());
        result.put("paymentTerms", supplier.getPaymentTerm() == null
                ? null : supplier.getPaymentTerm().getName());
        result.put("comment", supplier.getComment());
        result.put("address", supplier.getAddress());
        return result;
    }

    private static Map<String, Object> inpaymentDetails(SSInpayment item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", item.getNumber());
        result.put("date", item.getLocalDate());
        result.put("text", item.getText());
        result.put("entered", item.isEntered());
        result.put("total", money(se.swedsoft.bookkeeping.calc.math.SSInpaymentMath.getSum(item)));
        result.put("rows", item.getRows().stream().map(row -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("invoiceNumber", row.getInvoiceNr());
            value.put("amount", money(row.getValue()));
            value.put("currencyRate", decimal(row.getCurrencyRate()));
            return value;
        }).toList());
        return result;
    }

    private static Map<String, Object> inpaymentJournalDetails(InpaymentJournalPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journalNumber", plan.journalNumber());
        result.put("from", plan.from());
        result.put("to", plan.to());
        result.put("inpaymentNumbers", plan.inpayments().stream().map(SSInpayment::getNumber).toList());
        result.put("inpaymentCount", plan.inpayments().size());
        result.put("debitTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(plan.voucher())));
        result.put("creditTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(plan.voucher())));
        return result;
    }

    private static Map<String, Object> invoiceJournalDetails(InvoiceJournalPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journalNumber", plan.journalNumber());
        result.put("from", plan.from());
        result.put("to", plan.to());
        result.put("invoiceNumbers", plan.invoices().stream().map(SSInvoice::getNumber).toList());
        result.put("invoiceCount", plan.invoices().size());
        result.put("debitTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(plan.voucher())));
        result.put("creditTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(plan.voucher())));
        result.put("voucherRows", plan.voucher().getRows().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.getAccountNr());
            item.put("debit", money(row.getDebet()));
            item.put("credit", money(row.getCredit()));
            item.put("project", row.getProjectNr());
            item.put("resultUnit", row.getResultUnitNr());
            return item;
        }).toList());
        return result;
    }

    private static Map<String, Object> invoiceSummary(SSInvoice invoice) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", invoice.getNumber());
        result.put("date", invoice.getLocalDate());
        result.put("dueDate", invoice.getLocalDueDate());
        result.put("customerNumber", invoice.getCustomerNr());
        result.put("customerName", invoice.getCustomerName());
        result.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getName());
        result.put("type", invoice.getType().name());
        result.put("entered", invoice.isEntered());
        result.put("printed", invoice.isPrinted());
        result.put("net", money(SSSaleMath.getNetSum(invoice)));
        result.put("vat", money(SSSaleMath.getTotalTaxSum(invoice)));
        result.put("total", money(SSSaleMath.getTotalSum(invoice)));
        result.put("balance", invoice.getNumber() == null
                ? null : money(se.swedsoft.bookkeeping.calc.math.SSInvoiceMath.getSaldo(invoice)));
        result.put("rowCount", invoice.getRows().size());
        return result;
    }

    private static Map<String, Object> creditInvoiceDetails(SSCreditInvoice invoice) {
        Map<String, Object> result = invoiceDetails(invoice);
        result.put("creditingInvoiceNumber", invoice.getCreditingNr());
        result.put("voucher", voucherRows(invoice.getVoucher()));
        return result;
    }

    private static List<Map<String, Object>> voucherRows(SSVoucher voucher) {
        return voucher.getRows().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.getAccountNr());
            item.put("debit", money(row.getDebet()));
            item.put("credit", money(row.getCredit()));
            item.put("project", row.getProjectNr());
            item.put("resultUnit", row.getResultUnitNr());
            return item;
        }).toList();
    }

    private static java.math.BigDecimal voucherDebit(SSVoucher voucher) {
        return se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(voucher);
    }

    private static java.math.BigDecimal voucherCredit(SSVoucher voucher) {
        return se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(voucher);
    }

    private static Map<String, Object> invoiceDetails(SSInvoice invoice) {
        Map<String, Object> result = invoiceSummary(invoice);
        List<Map<String, Object>> rows = invoice.getRows().stream()
                .map(BokfriCli::invoiceRow).toList();
        result.put("rows", rows);
        result.put("ocrNumber", invoice.getOCRNumber());
        result.put("yourOrderNumber", invoice.getYourOrderNumber());
        result.put("text", invoice.getText());
        result.put("taxFree", invoice.getTaxFree());
        result.put("rounding", money(SSSaleMath.getRounding(invoice)));
        return result;
    }

    private static Map<String, Object> invoiceRow(SSSaleRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productNumber", row.getProductNr());
        result.put("description", row.getDescription());
        result.put("quantity", row.getQuantity());
        result.put("unitPrice", money(row.getUnitprice()));
        result.put("discount", decimal(row.getDiscount()));
        result.put("taxCode", row.getTaxCode() == null ? null : row.getTaxCode().name());
        result.put("account", row.getAccountNr());
        result.put("project", row.getProjectNr());
        result.put("resultUnit", row.getResultUnitNr());
        result.put("sum", row.getSum().map(BokfriCli::money).orElse(null));
        return result;
    }

    private static Map<String,Object> vatSettlementDetails(VatSettlementPlan p){Map<String,Object>x=new LinkedHashMap<>();x.put("from",p.report().from());x.put("to",p.report().to());x.put("boxes",vatBoxes(p.report().boxes()));x.put("vatToPayOrRefund",money(p.report().vatToPayOrRefund()));x.put("debitTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(p.voucher())));x.put("creditTotal",money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(p.voucher())));x.put("voucherRows",p.voucher().getRows().stream().map(r->{Map<String,Object> v=new LinkedHashMap<>();v.put("account",r.getAccountNr());v.put("debit",money(r.getDebet()));v.put("credit",money(r.getCredit()));return v;}).toList());return x;}

    private static Map<String, Object> voucherSummary(SSVoucher voucher) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", voucher.getNumber());
        result.put("date", voucher.getLocalDate());
        result.put("description", voucher.getDescription());
        result.put("debitTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(voucher)));
        result.put("creditTotal", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(voucher)));
        result.put("rowCount", voucher.getRows().size());
        return result;
    }

    private static Map<String, Object> voucherDetails(SSVoucher voucher) {
        Map<String, Object> result = voucherSummary(voucher);
        List<Map<String, Object>> rows = voucher.getRows().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.getAccountNr());
            SSAccount account = row.getAccount();
            item.put("accountDescription", account == null ? null : account.getDescription());
            item.put("debit", money(row.getDebet()));
            item.put("credit", money(row.getCredit()));
            item.put("project", row.getProjectNr());
            item.put("resultUnit", row.getResultUnitNr());
            item.put("crossed", row.isCrossed());
            item.put("added", row.isAdded());
            item.put("editedAt", row.getLocalEditedDate());
            item.put("editedSignature", row.getEditedSignature());
            return item;
        }).toList();
        result.put("rows", rows);
        result.put("corrects", voucher.getCorrects() == null ? null : voucher.getCorrects().getNumber());
        result.put("correctedBy", voucher.getCorrectedBy() == null
                ? null : voucher.getCorrectedBy().getNumber());
        return result;
    }

    private static List<Map<String, Object>> vatBoxes(
            List<org.fribok.bookkeeping.service.vat.VatReportBox> boxes) {
        return boxes.stream().map(box -> Map.<String, Object>of(
                "number", box.number(), "vatCodes", box.vatCodes(),
                "amount", money(box.amount()))).toList();
    }

    static String money(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static Map<String, Object> selectedContext(ResolvedContext context,
            SSNewCompany company, SSNewAccountingYear year) {
        Map<String, Object> selected = context.asMap();
        selected.put("companyName", company.getName());
        selected.put("yearFrom", year.getLocalFrom().toString());
        selected.put("yearTo", year.getLocalTo().toString());
        return selected;
    }

    private static String voucherDetailsText(SSVoucher voucher) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (SSVoucherRow row : voucher.getRows()) {
            SSAccount account = row.getAccount();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.getAccountNr());
            item.put("description", account == null ? "" : account.getDescription());
            item.put("debit", row.getDebet() == null ? "" : money(row.getDebet()));
            item.put("credit", row.getCredit() == null ? "" : money(row.getCredit()));
            rows.add(item);
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("account", "Total");
        total.put("description", "");
        total.put("debit", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(voucher)));
        total.put("credit", money(se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(voucher)));
        rows.add(total);

        return "Voucher " + voucher.getNumber()
                + "\nDate: " + voucher.getLocalDate()
                + "\nDescription: " + voucher.getDescription()
                + "\n" + voucherRowsText(rows);
    }

    static String voucherRowsText(List<? extends Map<String, ?>> rows) {
        return table(rows, "No voucher rows",
                left("Account", "account"), left("Description", "description"),
                right("Debit", "debit"), right("Credit", "credit"));
    }

    private static Map<String, Object> voucherResult(ResolvedContext context,
            SSNewCompany company, SSNewAccountingYear year, SSVoucher voucher,
            VoucherValidationResult validation, boolean persist, int nextNumber) {
        Map<String, Object> selected = selectedContext(context, company, year);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.valid());
        result.put("dryRun", !persist);
        result.put("selection", selected);
        result.put("number", nextNumber);
        result.put("date", voucher.getLocalDate());
        result.put("description", voucher.getDescription());
        result.put("debitTotal", money(validation.debitTotal()));
        result.put("creditTotal", money(validation.creditTotal()));
        result.put("issues", validation.issues());
        return result;
    }

    private static String voucherText(Map<String, Object> result,
            VoucherValidationResult validation) {
        return "Voucher is valid\nNumber: " + result.get("number") + "\nDebit: "
                + money(validation.debitTotal()) + "\nCredit: "
                + money(validation.creditTotal())
                + (Boolean.TRUE.equals(result.get("created")) ? "\nCreated" : "\nNo changes written");
    }

    private static CliException validationFailure(VoucherValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("debitTotal", money(validation.debitTotal()));
        details.put("creditTotal", money(validation.creditTotal()));
        details.put("issues", validation.issues());
        VoucherValidationIssue first = validation.issues().get(0);
        return new CliException("VOUCHER_INVALID", first.message(), details);
    }

    private static CliException databaseFailure(Exception exception) {
        if (exception instanceof CliException cliException) {
            return cliException;
        }
        if (exception instanceof java.nio.file.FileAlreadyExistsException fileExists) {
            return new CliException("OUTPUT_EXISTS",
                    "Output file already exists: " + fileExists.getFile(), exception);
        }
        if (exception instanceof org.fribok.bookkeeping.dataformat.DataMigrationRequiredException) {
            return new CliException("DATABASE_MIGRATION_REQUIRED",
                    exception.getMessage() + " Run 'bokfri database migrate' to create a verified "
                            + "backup and migrate the database.", exception);
        }
        return new CliException("DATABASE_FAILED", "Could not inspect the database: "
                + exception.getMessage(), exception);
    }

    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
        CommandLine commandLine = new CommandLine(new BokfriCli());
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            boolean internal = !(exception instanceof CliException);
            CliException failure = internal
                    ? new CliException("INTERNAL_ERROR", "An unexpected error occurred.", exception)
                    : (CliException) exception;
            BokfriCli root = (BokfriCli) command.getCommandSpec().root().userObject();
            ResolvedContext context = root.resolveContext(false, false);
            String diagnostic = null;
            String logFailure = null;
            if (internal) {
                diagnostic = CliLog.diagnostic(context.dataDir(), args, exception);
                try {
                    CliLog.append(context.dataDir(), diagnostic);
                } catch (java.io.IOException logException) {
                    logFailure = logException.getMessage();
                }
            }
            if (root.format == OutputFormat.json) {
                try {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("code", failure.getCode());
                    error.put("message", failure.getMessage());
                    if (failure.getDetails() != null) {
                        error.put("details", failure.getDetails());
                    }
                    if (internal) {
                        error.put("logFile", CliLog.file(context.dataDir()).toString());
                        if (root.verbose) {
                            error.put("diagnostic", diagnostic);
                        }
                        if (logFailure != null) {
                            error.put("logWriteError", logFailure);
                        }
                    }
                    err.println(jsonMapper().writeValueAsString(Map.of("error", error)));
                } catch (JsonProcessingException jsonException) {
                    err.println(failure.getCode() + ": " + failure.getMessage());
                }
            } else {
                err.println(failure.getCode() + ": " + failure.getMessage());
                if (internal) {
                    err.println("Details were written to: " + CliLog.file(context.dataDir()));
                    if (logFailure != null) {
                        err.println("Could not write the log file: " + logFailure);
                    }
                    if (root.verbose) {
                        err.println();
                        err.print(diagnostic);
                    } else {
                        err.println("Run the command again with --verbose to print diagnostic details.");
                    }
                }
            }
            return internal ? 70 : 1;
        });
        return commandLine.execute(args);
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        Charset charset = System.console() == null
                ? StandardCharsets.UTF_8
                : System.console().charset();
        System.setProperty("bokfri.cliCharset", charset.name());
        int exitCode = execute(args,
                new PrintWriter(new OutputStreamWriter(System.out, charset), true),
                new PrintWriter(new OutputStreamWriter(System.err, charset), true));
        System.exit(exitCode);
    }
}
