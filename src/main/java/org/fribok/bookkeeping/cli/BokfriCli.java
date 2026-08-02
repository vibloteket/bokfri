package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.app.Version;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;

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
            BokfriCli.YearCommand.class
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

    void output(Object value, String text) {
        if (format == OutputFormat.json) {
            try {
                spec.commandLine().getOut().println(new ObjectMapper().writeValueAsString(value));
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
                    err.println(new ObjectMapper().writeValueAsString(Map.of("error", Map.of(
                            "code", failure.getCode(), "message", failure.getMessage()))));
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
