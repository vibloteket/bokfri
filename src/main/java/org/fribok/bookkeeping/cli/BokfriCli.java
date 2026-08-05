package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import org.fribok.bookkeeping.service.customer.CustomerService;
import org.fribok.bookkeeping.service.customer.CustomerValidationIssue;
import org.fribok.bookkeeping.service.customer.CustomerValidationResult;
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
import org.fribok.bookkeeping.service.product.ProductService;
import org.fribok.bookkeeping.service.product.ProductValidationIssue;
import org.fribok.bookkeeping.service.product.ProductValidationResult;
import org.fribok.bookkeeping.service.voucher.VoucherService;
import org.fribok.bookkeeping.service.voucher.VoucherValidationIssue;
import org.fribok.bookkeeping.service.voucher.VoucherValidationResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import se.swedsoft.bookkeeping.calc.math.SSSaleMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Headless command-line interface for Bokfri. */
@Command(name = "bokfri", mixinStandardHelpOptions = true,
        description = "Inspect and configure Bokfri without starting the Swing interface.",
        subcommands = {
            BokfriCli.VersionCommand.class,
            BokfriCli.PathsCommand.class,
            BokfriCli.DoctorCommand.class,
            BokfriCli.ContextCommand.class,
            BokfriCli.CompanyCommand.class,
            BokfriCli.YearCommand.class,
            BokfriCli.AccountCommand.class,
            BokfriCli.CustomerCommand.class,
            BokfriCli.ProductCommand.class,
            BokfriCli.InvoiceCommand.class,
            BokfriCli.InpaymentCommand.class,
            BokfriCli.VoucherCommand.class
        })
public class BokfriCli implements Runnable {
    enum OutputFormat { text, json }

    @Option(names = "--config", description = "CLI config file")
    java.nio.file.Path configPath;

    @Option(names = "--context", description = "Context to use for this command")
    String contextName;

    @Option(names = "--data-dir", description = "Override the Bokfri data directory")
    java.nio.file.Path dataDir;

    @Option(names = "--company-id", description = "Override the company id")
    Integer companyId;

    @Option(names = "--year-id", description = "Override the accounting year id")
    Integer yearId;

    @Option(names = "--format", defaultValue = "text", description = "Output format: ${COMPLETION-CANDIDATES}")
    OutputFormat format;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "A command is required");
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    CliConfigStore configStore() {
        java.nio.file.Path selected = configPath != null
                ? configPath
                : Path.get(Path.USER_CONF).toPath().resolve("cli.yaml");
        return new CliConfigStore(selected);
    }

    CliConfig loadConfig() {
        try {
            return configStore().load();
        } catch (IOException exception) {
            throw new CliException("CONFIG_READ_FAILED",
                    "Could not read " + configStore().getPath() + ": " + exception.getMessage(), exception);
        }
    }

    ResolvedContext resolveContext(boolean requireCompany, boolean requireYear) {
        CliConfig config = loadConfig();
        String selectedName = contextName != null ? contextName : config.getCurrentContext();
        CliContext stored = selectedName == null ? null : config.getContexts().get(selectedName);
        if (selectedName != null && stored == null) {
            throw new CliException("CONTEXT_NOT_FOUND", "No context is named " + selectedName);
        }

        java.nio.file.Path selectedDataDir = dataDir != null
                ? dataDir
                : stored != null && stored.getDataDir() != null
                        ? Paths.get(stored.getDataDir())
                        : Path.get(Path.USER_DATA).toPath();
        Integer selectedCompany = companyId != null
                ? companyId : stored == null ? null : stored.getCompanyId();
        Integer selectedYear = yearId != null ? yearId : stored == null ? null : stored.getYearId();

        if (requireCompany && selectedCompany == null) {
            throw new CliException("COMPANY_REQUIRED", "Select a context or provide --company-id");
        }
        if (requireYear && selectedYear == null) {
            throw new CliException("YEAR_REQUIRED", "Select a context or provide --year-id");
        }
        return new ResolvedContext(selectedName, selectedDataDir.toAbsolutePath().normalize(),
                selectedCompany, selectedYear);
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

    record ResolvedContext(String name, java.nio.file.Path dataDir,
                           Integer companyId, Integer yearId) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
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

    @Command(name = "version", description = "Print version and build information")
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

    @Command(name = "paths", description = "Print resolved Bokfri paths")
    static class PathsCommand extends CliCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Path path : Path.values()) {
                result.put(toCamelCase(path.name()), Path.get(path).getAbsolutePath());
            }
            result.put("cliConfig", parent.configStore().getPath().toString());
            StringBuilder text = new StringBuilder();
            result.forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
            parent.output(result, text.toString().stripTrailing());
            return 0;
        }

        private static String toCamelCase(String name) {
            String[] parts = name.toLowerCase().split("_");
            return parts[0] + Character.toUpperCase(parts[1].charAt(0)) + parts[1].substring(1);
        }
    }

    @Command(name = "doctor", description = "Check CLI configuration and selected data directory")
    static class DoctorCommand extends CliCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            ResolvedContext context = parent.resolveContext(false, false);
            boolean configExists = Files.exists(parent.configStore().getPath());
            boolean dataDirExists = Files.isDirectory(context.dataDir());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("configExists", configExists);
            result.put("dataDirExists", dataDirExists);
            result.put("context", context.asMap());
            parent.output(result, "Status: OK\nConfig: " + parent.configStore().getPath()
                    + (configExists ? "" : " (not created yet)") + "\nData directory: "
                    + context.dataDir() + (dataDirExists ? "" : " (will be created when needed)")
                    + "\nContext: " + (context.name() == null ? "none" : context.name()));
            return 0;
        }
    }

    @Command(name = "context", description = "Manage named company/year contexts",
            subcommands = {ContextList.class, ContextCurrent.class, ContextShow.class,
                    ContextCreate.class, ContextUse.class, ContextDelete.class})
    static class ContextCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A context command is required");
        }
    }

    abstract static class ContextSubcommand {
        @CommandLine.ParentCommand ContextCommand command;
        BokfriCli root() { return command.parent; }
    }

    @Command(name = "list", description = "List contexts")
    static class ContextList extends ContextSubcommand implements Callable<Integer> {
        @Override public Integer call() {
            CliConfig config = root().loadConfig();
            List<Map<String, Object>> contexts = config.getContexts().entrySet().stream().map(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("current", entry.getKey().equals(config.getCurrentContext()));
                item.put("dataDir", entry.getValue().getDataDir());
                item.put("companyId", entry.getValue().getCompanyId());
                item.put("yearId", entry.getValue().getYearId());
                return item;
            }).toList();
            String text = contexts.isEmpty() ? "No contexts configured" : contexts.stream()
                    .map(item -> (Boolean.TRUE.equals(item.get("current")) ? "* " : "  ")
                            + item.get("name") + "  company=" + item.get("companyId")
                            + " year=" + item.get("yearId") + "  " + item.get("dataDir"))
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currentContext", config.getCurrentContext());
            result.put("contexts", contexts);
            root().output(result, text);
            return 0;
        }
    }

    @Command(name = "current", description = "Show the current context")
    static class ContextCurrent extends ContextSubcommand implements Callable<Integer> {
        @Override public Integer call() {
            CliConfig config = root().loadConfig();
            if (config.getCurrentContext() == null) {
                throw new CliException("CONTEXT_NOT_SELECTED", "No current context is selected");
            }
            return showContext(root(), config, config.getCurrentContext());
        }
    }

    @Command(name = "show", description = "Show a context")
    static class ContextShow extends ContextSubcommand implements Callable<Integer> {
        @Parameters(index = "0") String name;
        @Override public Integer call() { return showContext(root(), root().loadConfig(), name); }
    }

    private static int showContext(BokfriCli root, CliConfig config, String name) {
        CliContext context = config.getContexts().get(name);
        if (context == null) {
            throw new CliException("CONTEXT_NOT_FOUND", "No context is named " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("current", name.equals(config.getCurrentContext()));
        result.put("dataDir", context.getDataDir());
        result.put("companyId", context.getCompanyId());
        result.put("yearId", context.getYearId());
        root.output(result, "Context: " + name + "\nData directory: " + context.getDataDir()
                + "\nCompany id: " + context.getCompanyId() + "\nYear id: " + context.getYearId());
        return 0;
    }

    @Command(name = "create", description = "Create or replace a context")
    static class ContextCreate extends ContextSubcommand implements Callable<Integer> {
        @Parameters(index = "0") String name;
        @Option(names = "--data-dir", required = true) java.nio.file.Path dataDir;
        @Option(names = "--company-id", required = true) Integer companyId;
        @Option(names = "--year-id", required = true) Integer yearId;

        @Override public Integer call() {
            CliConfig config = root().loadConfig();
            config.getContexts().put(name, new CliContext(dataDir, companyId, yearId));
            save(root(), config);
            return showContext(root(), config, name);
        }
    }

    @Command(name = "use", description = "Select the current context")
    static class ContextUse extends ContextSubcommand implements Callable<Integer> {
        @Parameters(index = "0") String name;
        @Override public Integer call() {
            CliConfig config = root().loadConfig();
            if (!config.getContexts().containsKey(name)) {
                throw new CliException("CONTEXT_NOT_FOUND", "No context is named " + name);
            }
            config.setCurrentContext(name);
            save(root(), config);
            root().output(Map.of("currentContext", name), "Current context is now " + name);
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a context")
    static class ContextDelete extends ContextSubcommand implements Callable<Integer> {
        @Parameters(index = "0") String name;
        @Override public Integer call() {
            CliConfig config = root().loadConfig();
            if (config.getContexts().remove(name) == null) {
                throw new CliException("CONTEXT_NOT_FOUND", "No context is named " + name);
            }
            if (name.equals(config.getCurrentContext())) {
                config.setCurrentContext(null);
            }
            save(root(), config);
            root().output(Map.of("deleted", name), "Deleted context " + name);
            return 0;
        }
    }

    private static void save(BokfriCli root, CliConfig config) {
        try {
            root.configStore().save(config);
        } catch (IOException exception) {
            throw new CliException("CONFIG_WRITE_FAILED",
                    "Could not write " + root.configStore().getPath() + ": " + exception.getMessage(), exception);
        }
    }

    @Command(name = "company", description = "Inspect companies", subcommands = CompanyList.class)
    static class CompanyCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A company command is required");
        }
    }

    @Command(name = "list", description = "List companies")
    static class CompanyList implements Callable<Integer> {
        @CommandLine.ParentCommand CompanyCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(false, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                List<Map<String, Object>> companies = runtime.database().getCompanies().stream().map(company -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", company.getId());
                    item.put("name", company.getName());
                    item.put("corporateId", company.getCorporateID());
                    return item;
                }).toList();
                String text = companies.stream().map(item -> item.get("id") + "\t" + item.get("name")
                        + (item.get("corporateId") == null ? "" : "\t" + item.get("corporateId")))
                        .reduce((left, right) -> left + "\n" + right).orElse("No companies found");
                root.output(Map.of("context", context.asMap(), "companies", companies), text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "year", description = "Inspect accounting years", subcommands = YearList.class)
    static class YearCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A year command is required");
        }
    }

    @Command(name = "list", description = "List accounting years for the selected company")
    static class YearList implements Callable<Integer> {
        @CommandLine.ParentCommand YearCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                List<Map<String, Object>> years = runtime.database().getYearsForCompany(company).stream().map(year -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", year.getId());
                    item.put("from", year.getLocalFrom().toString());
                    item.put("to", year.getLocalTo().toString());
                    return item;
                }).toList();
                String text = years.stream().map(item -> item.get("id") + "\t" + item.get("from")
                        + " – " + item.get("to"))
                        .reduce((left, right) -> left + "\n" + right).orElse("No accounting years found");
                Map<String, Object> selected = context.asMap();
                selected.put("companyName", company.getName());
                root.output(Map.of("context", selected, "years", years), text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "account", description = "Inspect accounts", subcommands = AccountList.class)
    static class AccountCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An account command is required");
        }
    }

    @Command(name = "list", description = "List accounts for the selected year")
    static class AccountList implements Callable<Integer> {
        @CommandLine.ParentCommand AccountCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                List<Map<String, Object>> accounts = runtime.database().getAccounts().stream().map(account -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("number", account.getNumber());
                    item.put("description", account.getDescription());
                    item.put("active", account.isActive());
                    return item;
                }).toList();
                String text = accounts.stream().map(item -> item.get("number") + "\t"
                        + item.get("description"))
                        .reduce((left, right) -> left + "\n" + right).orElse("No accounts found");
                Map<String, Object> selected = context.asMap();
                selected.put("companyName", company.getName());
                selected.put("yearFrom", year.getLocalFrom().toString());
                selected.put("yearTo", year.getLocalTo().toString());
                root.output(Map.of("context", selected, "accounts", accounts), text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "customer", description = "Inspect and create customers",
            subcommands = {CustomerList.class, CustomerShow.class,
                    CustomerValidate.class, CustomerCreate.class})
    static class CustomerCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A customer command is required");
        }
    }

    @Command(name = "list", description = "List customers")
    static class CustomerList implements Callable<Integer> {
        @CommandLine.ParentCommand CustomerCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                CustomerService service = new CustomerService(runtime.database());
                List<Map<String, Object>> customers = service.list().stream()
                        .map(BokfriCli::customerSummary).toList();
                root.output(Map.of("context", selectedCompanyContext(context, company),
                                "count", customers.size(), "customers", customers),
                        customers.stream().map(customer -> customer.get("number") + "\t"
                                + customer.get("name") + "\t" + customer.get("email"))
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("No customers found"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "show", description = "Show one customer by number")
    static class CustomerShow implements Callable<Integer> {
        @CommandLine.ParentCommand CustomerCommand command;
        @Parameters(index = "0") String number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSCustomer customer = new CustomerService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("CUSTOMER_NOT_FOUND",
                                "No customer has number " + number));
                Map<String, Object> result = customerDetails(customer);
                result.put("context", selectedCompanyContext(context, company));
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
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedCompanyContext(context, company));
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

    @Command(name = "validate", description = "Validate customer JSON without writing")
    static class CustomerValidate extends CustomerOperation {
        @Override boolean persist() { return false; }
    }

    @Command(name = "create", description = "Create a customer from JSON")
    static class CustomerCreate extends CustomerOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(name = "product", description = "Inspect and create products",
            subcommands = {ProductList.class, ProductShow.class,
                    ProductValidate.class, ProductCreate.class})
    static class ProductCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A product command is required");
        }
    }

    @Command(name = "list", description = "List products")
    static class ProductList implements Callable<Integer> {
        @CommandLine.ParentCommand ProductCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                ProductService service = new ProductService(runtime.database());
                List<Map<String, Object>> products = service.list().stream()
                        .map(BokfriCli::productDetails).toList();
                root.output(Map.of("context", selectedCompanyContext(context, company),
                                "count", products.size(), "products", products),
                        products.stream().map(product -> product.get("number") + "\t"
                                + product.get("description") + "\t" + product.get("sellingPrice"))
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("No products found"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "show", description = "Show one product by number")
    static class ProductShow implements Callable<Integer> {
        @CommandLine.ParentCommand ProductCommand command;
        @Parameters(index = "0") String number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSProduct product = new ProductService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("PRODUCT_NOT_FOUND",
                                "No product has number " + number));
                Map<String, Object> result = productDetails(product);
                result.put("context", selectedCompanyContext(context, company));
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
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedContext(context, company, year));
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

    @Command(name = "validate", description = "Validate product JSON without writing")
    static class ProductValidate extends ProductOperation {
        @Override boolean persist() { return false; }
    }

    @Command(name = "create", description = "Create a product from JSON")
    static class ProductCreate extends ProductOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(name = "invoice", description = "Inspect and create customer invoices",
            subcommands = {InvoiceList.class, InvoiceShow.class, InvoicePdf.class,
                    InvoiceJournal.class, InvoiceValidate.class, InvoiceCreate.class})
    static class InvoiceCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An invoice command is required");
        }
    }

    @Command(name = "list", description = "List customer invoices")
    static class InvoiceList implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--from") java.time.LocalDate from;
        @Option(names = "--to") java.time.LocalDate to;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                List<Map<String, Object>> invoices = new InvoiceService(runtime.database())
                        .list(from, to).stream().map(BokfriCli::invoiceSummary).toList();
                root.output(Map.of("context", selectedCompanyContext(context, company),
                                "count", invoices.size(), "invoices", invoices),
                        invoices.stream().map(invoice -> invoice.get("number") + "\t"
                                + invoice.get("date") + "\t" + invoice.get("customerName")
                                + "\t" + invoice.get("total"))
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("No invoices found"));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "show", description = "Show one customer invoice by number")
    static class InvoiceShow implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0") int number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSInvoice invoice = new InvoiceService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INVOICE_NOT_FOUND",
                                "No invoice has number " + number));
                Map<String, Object> result = invoiceDetails(invoice);
                result.put("context", selectedCompanyContext(context, company));
                root.output(result, "Invoice " + invoice.getNumber() + "\nCustomer: "
                        + invoice.getCustomerName() + "\nTotal: " + SSSaleMath.getTotalSum(invoice));
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "journal", description = "Preview or commit an invoice journal for a period")
    static class InvoiceJournal implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--commit", description = "Persist the voucher and mark invoices entered")
        boolean commit;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedContext(context, company, year));
                if (commit) {
                    InvoiceJournalResult committed = service.commitJournal(plan);
                    result.put("voucherNumber", committed.voucherNumber());
                }
                root.output(result, commit
                        ? "Committed invoice journal " + plan.journalNumber() + " with voucher "
                                + result.get("voucherNumber") + " for " + plan.invoices().size() + " invoices"
                        : "Invoice journal " + plan.journalNumber() + " preview\nInvoices: "
                                + plan.invoices().size() + "\nDebit: "
                                + se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                                        .getDebetSum(plan.voucher()).toPlainString()
                                + "\nCredit: " + se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                                        .getCreditSum(plan.voucher()).toPlainString()
                                + "\nNo changes written");
                return 0;
            } catch (IllegalArgumentException exception) {
                throw new CliException("INVOICE_JOURNAL_INVALID", exception.getMessage(), exception);
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "pdf", description = "Generate a PDF for an existing invoice")
    static class InvoicePdf implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Parameters(index = "0", description = "Invoice number") int number;
        @Option(names = "--output", required = true, description = "Destination PDF file")
        java.nio.file.Path output;
        @Option(names = "--language", defaultValue = "sv-SE", description = "Invoice language")
        String language;
        @Option(names = "--overwrite", description = "Replace an existing output file")
        boolean overwrite;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                InvoiceService service = new InvoiceService(runtime.database());
                SSInvoice invoice = service.find(number).orElseThrow(() ->
                        new CliException("INVOICE_NOT_FOUND", "No invoice has number " + number));
                java.nio.file.Path pdf = service.exportPdf(invoice, output,
                        java.util.Locale.forLanguageTag(language), overwrite);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("invoiceNumber", number);
                result.put("output", pdf.toString());
                result.put("bytes", Files.size(pdf));
                result.put("language", language);
                result.put("context", selectedCompanyContext(context, company));
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

    abstract static class InvoiceOperation implements Callable<Integer> {
        @CommandLine.ParentCommand InvoiceCommand command;
        @Option(names = "--file", required = true, description = "Invoice JSON file, or - for stdin")
        String file;

        abstract boolean persist();

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            InvoiceInput input = readInvoiceInput(file);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
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
                result.put("context", selectedCompanyContext(context, company));
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

    @Command(name = "validate", description = "Validate invoice JSON without writing")
    static class InvoiceValidate extends InvoiceOperation {
        @Override boolean persist() { return false; }
    }

    @Command(name = "create", description = "Create an unbooked customer invoice from JSON")
    static class InvoiceCreate extends InvoiceOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(name = "inpayment", description = "Inspect, create, and book customer inpayments",
            subcommands = {InpaymentList.class, InpaymentShow.class, InpaymentJournal.class,
                    InpaymentValidate.class, InpaymentCreate.class})
    static class InpaymentCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "An inpayment command is required");
        }
    }

    @Command(name = "list", description = "List customer inpayments")
    static class InpaymentList implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                List<Map<String, Object>> items = new InpaymentService(runtime.database()).list()
                        .stream().map(BokfriCli::inpaymentDetails).toList();
                root.output(Map.of("context", selectedCompanyContext(context, company),
                                "count", items.size(), "inpayments", items),
                        items.stream().map(item -> item.get("number") + "\t" + item.get("date")
                                + "\t" + item.get("text") + "\t" + item.get("total"))
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("No inpayments found"));
                return 0;
            } catch (Exception exception) { throw databaseFailure(exception); }
        }
    }

    @Command(name = "show", description = "Show one customer inpayment")
    static class InpaymentShow implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Parameters(index = "0") int number;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, false);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                runtime.database().init(false);
                SSInpayment item = new InpaymentService(runtime.database()).find(number)
                        .orElseThrow(() -> new CliException("INPAYMENT_NOT_FOUND",
                                "No inpayment has number " + number));
                Map<String, Object> result = inpaymentDetails(item);
                result.put("context", selectedCompanyContext(context, company));
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
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedContext(context, company, year));
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

    @Command(name = "validate", description = "Validate inpayment JSON without writing")
    static class InpaymentValidate extends InpaymentOperation {
        @Override boolean persist() { return false; }
    }

    @Command(name = "create", description = "Create an unbooked customer inpayment")
    static class InpaymentCreate extends InpaymentOperation {
        @Option(names = "--dry-run") boolean dryRun;
        @Override boolean persist() { return !dryRun; }
    }

    @Command(name = "journal", description = "Preview or commit an inpayment journal")
    static class InpaymentJournal implements Callable<Integer> {
        @CommandLine.ParentCommand InpaymentCommand command;
        @Option(names = "--from", required = true) java.time.LocalDate from;
        @Option(names = "--to", required = true) java.time.LocalDate to;
        @Option(names = "--commit") boolean commit;
        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedContext(context, company, year));
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

    @Command(name = "voucher", description = "Inspect, validate, and create manual vouchers",
            subcommands = {VoucherList.class, VoucherShow.class,
                    VoucherValidate.class, VoucherCreate.class})
    static class VoucherCommand extends CliCommand implements Runnable {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "A voucher command is required");
        }
    }

    @Command(name = "list", description = "List vouchers in the selected accounting year")
    static class VoucherList implements Callable<Integer> {
        @CommandLine.ParentCommand VoucherCommand command;
        @Option(names = "--from", description = "Only vouchers on or after this date")
        java.time.LocalDate from;
        @Option(names = "--to", description = "Only vouchers on or before this date")
        java.time.LocalDate to;
        @Option(names = "--limit", description = "Maximum results; by default all are returned")
        Integer limit;

        @Override public Integer call() {
            if (limit != null && limit < 1) {
                throw new CliException("LIMIT_INVALID", "--limit must be at least 1");
            }
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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
                result.put("context", selectedContext(context, company, year));
                result.put("count", vouchers.size());
                result.put("vouchers", vouchers);
                String text = vouchers.stream().map(voucher -> voucher.get("number") + "\t"
                        + voucher.get("date") + "\t" + voucher.get("description") + "\t"
                        + voucher.get("debitTotal"))
                        .reduce((left, right) -> left + "\n" + right).orElse("No vouchers found");
                root.output(result, text);
                return 0;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        }
    }

    @Command(name = "show", description = "Show one voucher by number")
    static class VoucherShow implements Callable<Integer> {
        @CommandLine.ParentCommand VoucherCommand command;
        @Parameters(index = "0", description = "Voucher number")
        int number;

        @Override public Integer call() {
            BokfriCli root = command.parent;
            ResolvedContext context = root.resolveContext(true, true);
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
                SSNewCompany company = runtime.selectCompany(context.companyId());
                SSNewAccountingYear year = runtime.selectYear(company, context.yearId());
                VoucherService service = new VoucherService(runtime.database());
                SSVoucher voucher = service.find(number).orElseThrow(() ->
                        new CliException("VOUCHER_NOT_FOUND", "No voucher has number " + number));
                Map<String, Object> result = voucherDetails(voucher);
                result.put("context", selectedContext(context, company, year));
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
            try (BokfriRuntime runtime = BokfriRuntime.open(context.dataDir())) {
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

    @Command(name = "validate", description = "Validate a voucher JSON file without writing")
    static class VoucherValidate extends VoucherOperation {
        @Override boolean persist() { return false; }
    }

    @Command(name = "create", description = "Create a validated manual voucher")
    static class VoucherCreate extends VoucherOperation {
        @Option(names = "--dry-run", description = "Validate and preview without writing")
        boolean dryRun;
        @Override boolean persist() { return !dryRun; }
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
            return input;
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
                    .orElseThrow(() -> new CliException("INPAYMENT_INVOICE_NOT_FOUND",
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
        return new CliException("INPAYMENT_INVALID", first.message(), details);
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
            return input;
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
        return product;
    }

    private static CliException productValidationFailure(ProductValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("issues", validation.issues());
        ProductValidationIssue first = validation.issues().get(0);
        return new CliException("PRODUCT_INVALID", first.message(), details);
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
            return input;
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
            return input;
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
            return input;
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
        result.put("sellingPrice", decimal(product.getSellingPrice()));
        result.put("taxCode", product.getTaxCode() == null ? null : product.getTaxCode().name());
        result.put("taxRate", product.getTaxRate().map(BokfriCli::decimal).orElse(null));
        result.put("unit", product.getUnit() == null ? null : product.getUnit().getName());
        result.put("salesAccount", product.getDefaultAccount(SSDefaultAccount.Sales));
        result.put("project", product.getProjectNr());
        result.put("resultUnit", product.getResultUnitNr());
        result.put("expired", product.isExpired());
        return result;
    }

    private static Map<String, Object> inpaymentDetails(SSInpayment item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", item.getNumber());
        result.put("date", item.getLocalDate());
        result.put("text", item.getText());
        result.put("entered", item.isEntered());
        result.put("total", decimal(se.swedsoft.bookkeeping.calc.math.SSInpaymentMath.getSum(item)));
        result.put("rows", item.getRows().stream().map(row -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("invoiceNumber", row.getInvoiceNr());
            value.put("amount", decimal(row.getValue()));
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
        result.put("debitTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getDebetSum(plan.voucher()).toPlainString());
        result.put("creditTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getCreditSum(plan.voucher()).toPlainString());
        return result;
    }

    private static Map<String, Object> invoiceJournalDetails(InvoiceJournalPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journalNumber", plan.journalNumber());
        result.put("from", plan.from());
        result.put("to", plan.to());
        result.put("invoiceNumbers", plan.invoices().stream().map(SSInvoice::getNumber).toList());
        result.put("invoiceCount", plan.invoices().size());
        result.put("debitTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getDebetSum(plan.voucher()).toPlainString());
        result.put("creditTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getCreditSum(plan.voucher()).toPlainString());
        result.put("voucherRows", plan.voucher().getRows().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account", row.getAccountNr());
            item.put("debit", decimal(row.getDebet()));
            item.put("credit", decimal(row.getCredit()));
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
        result.put("net", decimal(SSSaleMath.getNetSum(invoice)));
        result.put("vat", decimal(SSSaleMath.getTotalTaxSum(invoice)));
        result.put("total", decimal(SSSaleMath.getTotalSum(invoice)));
        result.put("balance", invoice.getNumber() == null
                ? null : decimal(se.swedsoft.bookkeeping.calc.math.SSInvoiceMath.getSaldo(invoice)));
        result.put("rowCount", invoice.getRows().size());
        return result;
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
        result.put("rounding", decimal(SSSaleMath.getRounding(invoice)));
        return result;
    }

    private static Map<String, Object> invoiceRow(SSSaleRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productNumber", row.getProductNr());
        result.put("description", row.getDescription());
        result.put("quantity", row.getQuantity());
        result.put("unitPrice", decimal(row.getUnitprice()));
        result.put("discount", decimal(row.getDiscount()));
        result.put("taxCode", row.getTaxCode() == null ? null : row.getTaxCode().name());
        result.put("account", row.getAccountNr());
        result.put("project", row.getProjectNr());
        result.put("resultUnit", row.getResultUnitNr());
        result.put("sum", row.getSum().map(BokfriCli::decimal).orElse(null));
        return result;
    }

    private static Map<String, Object> voucherSummary(SSVoucher voucher) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", voucher.getNumber());
        result.put("date", voucher.getLocalDate());
        result.put("description", voucher.getDescription());
        result.put("debitTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getDebetSum(voucher).toPlainString());
        result.put("creditTotal", se.swedsoft.bookkeeping.calc.math.SSVoucherMath
                .getCreditSum(voucher).toPlainString());
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
            item.put("debit", decimal(row.getDebet()));
            item.put("credit", decimal(row.getCredit()));
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

    private static String decimal(java.math.BigDecimal value) {
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
        StringBuilder text = new StringBuilder();
        text.append("Voucher ").append(voucher.getNumber()).append('\n');
        text.append("Date: ").append(voucher.getLocalDate()).append('\n');
        text.append("Description: ").append(voucher.getDescription()).append('\n');
        for (SSVoucherRow row : voucher.getRows()) {
            text.append(row.getAccountNr()).append('\t')
                    .append(row.getDebet() == null ? "" : row.getDebet().toPlainString())
                    .append('\t')
                    .append(row.getCredit() == null ? "" : row.getCredit().toPlainString())
                    .append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static Map<String, Object> voucherResult(ResolvedContext context,
            SSNewCompany company, SSNewAccountingYear year, SSVoucher voucher,
            VoucherValidationResult validation, boolean persist, int nextNumber) {
        Map<String, Object> selected = selectedContext(context, company, year);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.valid());
        result.put("dryRun", !persist);
        result.put("context", selected);
        result.put("number", nextNumber);
        result.put("date", voucher.getLocalDate());
        result.put("description", voucher.getDescription());
        result.put("debitTotal", validation.debitTotal().toPlainString());
        result.put("creditTotal", validation.creditTotal().toPlainString());
        result.put("issues", validation.issues());
        return result;
    }

    private static String voucherText(Map<String, Object> result,
            VoucherValidationResult validation) {
        return "Voucher is valid\nNumber: " + result.get("number") + "\nDebit: "
                + validation.debitTotal().toPlainString() + "\nCredit: "
                + validation.creditTotal().toPlainString()
                + (Boolean.TRUE.equals(result.get("created")) ? "\nCreated" : "\nNo changes written");
    }

    private static CliException validationFailure(VoucherValidationResult validation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", false);
        details.put("debitTotal", validation.debitTotal().toPlainString());
        details.put("creditTotal", validation.creditTotal().toPlainString());
        details.put("issues", validation.issues());
        VoucherValidationIssue first = validation.issues().get(0);
        return new CliException("VOUCHER_INVALID", first.message(), details);
    }

    private static CliException databaseFailure(Exception exception) {
        if (exception instanceof CliException cliException) {
            return cliException;
        }
        return new CliException("DATABASE_FAILED", "Could not inspect the database: "
                + exception.getMessage(), exception);
    }

    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        System.setProperty("java.awt.headless", "true");
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
        CommandLine commandLine = new CommandLine(new BokfriCli());
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            CliException failure = exception instanceof CliException cliException
                    ? cliException : new CliException("INTERNAL_ERROR", exception.getMessage(), exception);
            BokfriCli root = (BokfriCli) command.getCommandSpec().root().userObject();
            if (root.format == OutputFormat.json) {
                try {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("code", failure.getCode());
                    error.put("message", failure.getMessage());
                    if (failure.getDetails() != null) {
                        error.put("details", failure.getDetails());
                    }
                    err.println(jsonMapper().writeValueAsString(Map.of("error", error)));
                } catch (JsonProcessingException jsonException) {
                    err.println(failure.getCode() + ": " + failure.getMessage());
                }
            } else {
                err.println(failure.getCode() + ": " + failure.getMessage());
            }
            return 1;
        });
        return commandLine.execute(args);
    }

    public static void main(String[] args) {
        int exitCode = execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        System.exit(exitCode);
    }
}
