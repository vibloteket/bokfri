package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import org.fribok.bookkeeping.service.voucher.VoucherService;
import org.fribok.bookkeeping.service.voucher.VoucherValidationIssue;
import org.fribok.bookkeeping.service.voucher.VoucherValidationResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

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
