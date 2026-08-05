import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-platform black-box smoke test for the assembled Bokfri CLI JAR. */
public final class CliSmokeTest {
    private static Path launcher;
    private static boolean jarLauncher;
    private static Path config;
    private static Path data;

    private CliSmokeTest() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: java scripts/CliSmokeTest.java <fat-jar-or-packaged-launcher>");
        }
        launcher = Path.of(arguments[0]).toAbsolutePath().normalize();
        require(Files.isRegularFile(launcher) && Files.size(launcher) > 0,
                "Launcher does not exist: " + launcher);
        jarLauncher = launcher.getFileName().toString().endsWith(".jar");

        Path temporary = Files.createTempDirectory("bokfri-cli-smoke-");
        config = temporary.resolve("config/cli.yaml");
        data = temporary.resolve("data");
        Files.createDirectories(data);

        try {
            runReadOnlyAndContextFlow();
            runReferenceDataFlow(temporary);
            runProductFlow(temporary);
            runInvoiceFlow(temporary);
            runVoucherFlow(temporary);
            runErrorFlow(temporary);
            System.out.println("Bokfri CLI black-box smoke test passed: " + launcher.getFileName());
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void runReadOnlyAndContextFlow() throws Exception {
        Result version = cli("--format", "json", "version");
        version.success();
        version.jsonObject();
        require(version.stdout.contains("\"title\":\"Bokfri\""), "version output lacks title");

        Result companies = cli("--data-dir", data.toString(), "--format", "json", "company", "list");
        companies.success();
        companies.jsonObject();
        int companyId = firstInt(companies.stdout, "id");

        Result years = cli("--data-dir", data.toString(), "--company-id", Integer.toString(companyId),
                "--format", "json", "year", "list");
        years.success();
        years.jsonObject();
        int yearId = firstInt(years.stdout, "id");

        cli("context", "create", "smoke", "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(yearId)).success();
        cli("context", "use", "smoke").success();
        Result current = cli("--format", "json", "context", "current");
        current.success();
        require(current.stdout.contains("\"name\":\"smoke\""), "current context was not selected");
    }

    private static void runReferenceDataFlow(Path temporary) throws Exception {
        Path customerInput = temporary.resolve("customer.json");
        Files.writeString(customerInput, """
                {
                  "number": "CLI-SMOKE-CUSTOMER",
                  "name": "CLI smoke customer",
                  "email": "cli-smoke@example.invalid",
                  "invoiceAddress": {
                    "address1": "Test street 1",
                    "postalCode": "123 45",
                    "city": "Test city"
                  }
                }
                """, StandardCharsets.UTF_8);
        Result customerValidation = cli("--format", "json", "customer", "validate",
                "--file", customerInput.toString());
        customerValidation.success();
        require(customerValidation.stdout.contains("\"valid\":true"),
                "customer validation did not succeed");
        Result customerDryRun = cli("--format", "json", "customer", "create", "--dry-run",
                "--file", customerInput.toString());
        customerDryRun.success();
        require(!customerDryRun.stdout.contains("\"created\":true"),
                "customer dry run unexpectedly created data");
        Result customerCreate = cli("--format", "json", "customer", "create",
                "--file", customerInput.toString());
        customerCreate.success();
        require(customerCreate.stdout.contains("\"created\":true"),
                "customer create lacks created=true");

        Result customers = cli("--format", "json", "customer", "list");
        customers.success();
        customers.jsonObject();
        require(customers.stdout.contains("\"number\":\"CLI-SMOKE-CUSTOMER\""),
                "created customer is absent from list");
        Result customer = cli("--format", "json", "customer", "show", "CLI-SMOKE-CUSTOMER");
        customer.success();
        require(customer.stdout.contains("\"name\":\"CLI smoke customer\""),
                "customer show returned the wrong customer");

        Result products = cli("--format", "json", "product", "list");
        products.success();
        products.jsonObject();
        if (!products.stdout.contains("\"products\":[]")) {
            String productNumber = firstString(products.stdout, "number");
            Result product = cli("--format", "json", "product", "show", productNumber);
            product.success();
            require(product.stdout.contains("\"number\":\"" + productNumber + "\""),
                    "product show returned the wrong product");
        }

        Result invoices = cli("--format", "json", "invoice", "list");
        invoices.success();
        invoices.jsonObject();
        if (!invoices.stdout.contains("\"invoices\":[]")) {
            int invoiceNumber = firstIntAfter(invoices.stdout, "invoices", "number");
            Result invoice = cli("--format", "json", "invoice", "show", Integer.toString(invoiceNumber));
            invoice.success();
            require(firstInt(invoice.stdout, "number") == invoiceNumber,
                    "invoice show returned the wrong invoice");
            require(invoice.stdout.contains("\"rows\":"), "invoice show lacks rows");
        }
    }

    private static void runProductFlow(Path temporary) throws Exception {
        Result accounts = cli("--format", "json", "account", "list");
        accounts.success();
        int salesAccount = firstInt(accounts.stdout, "number");
        Path productInput = temporary.resolve("product.json");
        Files.writeString(productInput, """
                {
                  "number": "CLI-SMOKE-PRODUCT",
                  "description": "CLI smoke product",
                  "sellingPrice": "100.00",
                  "vatRate": "25",
                  "salesAccount": %d
                }
                """.formatted(salesAccount), StandardCharsets.UTF_8);
        cli("--format", "json", "product", "validate", "--file", productInput.toString()).success();
        Result dryRun = cli("--format", "json", "product", "create", "--dry-run",
                "--file", productInput.toString());
        dryRun.success();
        require(!dryRun.stdout.contains("\"created\":true"),
                "product dry run unexpectedly created data");
        Result create = cli("--format", "json", "product", "create",
                "--file", productInput.toString());
        create.success();
        require(create.stdout.contains("\"created\":true"), "product create lacks created=true");
        Result show = cli("--format", "json", "product", "show", "CLI-SMOKE-PRODUCT");
        show.success();
        require(show.stdout.contains("\"description\":\"CLI smoke product\""),
                "product show returned the wrong product");
    }

    private static void runInvoiceFlow(Path temporary) throws Exception {
        String productNumber = "CLI-SMOKE-PRODUCT";

        Result years = cli("--format", "json", "year", "list");
        years.success();
        String date = firstString(years.stdout, "from");

        Path invoiceInput = temporary.resolve("invoice.json");
        Files.writeString(invoiceInput, """
                {
                  "customerNumber": "CLI-SMOKE-CUSTOMER",
                  "date": "%s",
                  "rows": [
                    {"productNumber": "%s", "quantity": 2}
                  ]
                }
                """.formatted(date, productNumber), StandardCharsets.UTF_8);

        Result validation = cli("--format", "json", "invoice", "validate",
                "--file", invoiceInput.toString());
        validation.success();
        Result dryRun = cli("--format", "json", "invoice", "create", "--dry-run",
                "--file", invoiceInput.toString());
        dryRun.success();
        int expectedNumber = firstInt(dryRun.stdout, "number");
        require(!dryRun.stdout.contains("\"created\":true"),
                "invoice dry run unexpectedly created data");

        Result create = cli("--format", "json", "invoice", "create",
                "--file", invoiceInput.toString());
        create.success();
        require(firstInt(create.stdout, "number") == expectedNumber,
                "created invoice number differs from dry run");
        require(create.stdout.contains("\"created\":true"),
                "invoice create lacks created=true");

        Result list = cli("--format", "json", "invoice", "list");
        list.success();
        require(list.stdout.contains("\"number\":" + expectedNumber),
                "created invoice is absent from list");
        Result show = cli("--format", "json", "invoice", "show", Integer.toString(expectedNumber));
        show.success();
        require(show.stdout.contains("\"customerNumber\":\"CLI-SMOKE-CUSTOMER\""),
                "invoice show returned the wrong customer");

        Path pdf = temporary.resolve("invoice-" + expectedNumber + ".pdf");
        Result pdfResult = cli("--format", "json", "invoice", "pdf",
                Integer.toString(expectedNumber), "--output", pdf.toString());
        pdfResult.success();
        require(Files.size(pdf) > 1_000, "invoice PDF is unexpectedly small");
        byte[] signature = Files.readAllBytes(pdf);
        require(signature.length >= 5 && signature[0] == '%' && signature[1] == 'P'
                        && signature[2] == 'D' && signature[3] == 'F' && signature[4] == '-',
                "invoice output does not have a PDF signature");
        Result duplicate = cli("--format", "json", "invoice", "pdf",
                Integer.toString(expectedNumber), "--output", pdf.toString());
        require(duplicate.exitCode != 0, "invoice PDF unexpectedly overwrote an existing file");
        require(duplicate.stderr.contains("\"code\":\"OUTPUT_EXISTS\""),
                "duplicate PDF output lacks stable error code");

        Result journalPreview = cli("--format", "json", "invoice", "journal",
                "--from", date, "--to", date);
        journalPreview.success();
        require(journalPreview.stdout.contains("\"invoiceNumbers\":[" + expectedNumber + "]"),
                "invoice journal preview lacks the created invoice");
        require(journalPreview.stdout.contains("\"committed\":false"),
                "invoice journal preview unexpectedly committed");

        Result journalCommit = cli("--format", "json", "invoice", "journal",
                "--from", date, "--to", date, "--commit");
        journalCommit.success();
        int voucherNumber = firstInt(journalCommit.stdout, "voucherNumber");
        require(journalCommit.stdout.contains("\"committed\":true"),
                "invoice journal commit lacks committed=true");
        Result bookedInvoice = cli("--format", "json", "invoice", "show",
                Integer.toString(expectedNumber));
        bookedInvoice.success();
        require(bookedInvoice.stdout.contains("\"entered\":true"),
                "journal did not mark invoice as entered");
        Result voucher = cli("--format", "json", "voucher", "show", Integer.toString(voucherNumber));
        voucher.success();
        require(voucher.stdout.contains("Fakturajournal"),
                "journal voucher was not persisted");
    }

    private static void runVoucherFlow(Path temporary) throws Exception {
        Result years = cli("--format", "json", "year", "list");
        years.success();
        String date = firstString(years.stdout, "from");

        Result accounts = cli("--format", "json", "account", "list");
        accounts.success();
        List<Integer> numbers = allInts(accounts.stdout, "number");
        require(numbers.size() >= 2, "account list returned fewer than two accounts");

        Path voucher = temporary.resolve("voucher.json");
        String description = "Pipeline CLI smoke test";
        Files.writeString(voucher, """
                {
                  "schemaVersion": 1,
                  "date": "%s",
                  "description": "%s",
                  "rows": [
                    {"account": %d, "debit": "12.34"},
                    {"account": %d, "credit": "12.34"}
                  ]
                }
                """.formatted(date, description, numbers.get(0), numbers.get(1)), StandardCharsets.UTF_8);

        Result validate = cli("--format", "json", "voucher", "validate", "--file", voucher.toString());
        validate.success();
        require(validate.stdout.contains("\"valid\":true"), "voucher validation did not succeed");

        Result dryRun = cli("--format", "json", "voucher", "create", "--dry-run",
                "--file", voucher.toString());
        dryRun.success();
        int expectedNumber = firstInt(dryRun.stdout, "number");
        require(!dryRun.stdout.contains("\"created\":true"), "dry run unexpectedly created a voucher");

        Result create = cli("--format", "json", "voucher", "create", "--file", voucher.toString());
        create.success();
        require(firstInt(create.stdout, "number") == expectedNumber, "created number differs from dry run");
        require(create.stdout.contains("\"created\":true"), "create output lacks created=true");

        Result list = cli("--format", "json", "voucher", "list");
        list.success();
        require(list.stdout.contains("\"description\":\"" + description + "\""),
                "created voucher is absent from list");

        Result show = cli("--format", "json", "voucher", "show", Integer.toString(expectedNumber));
        show.success();
        require(show.stdout.contains("\"description\":\"" + description + "\""),
                "show returned the wrong voucher");
        require(count(show.stdout, "\"account\":") == 2, "show did not return two posting rows");
    }

    private static void runErrorFlow(Path temporary) throws Exception {
        Path invalid = temporary.resolve("invalid-voucher.json");
        Files.writeString(invalid, """
                {"schemaVersion":1,"date":"2000-01-01","description":"Invalid","rows":[]}
                """, StandardCharsets.UTF_8);
        Result validation = cli("--format", "json", "voucher", "validate", "--file", invalid.toString());
        require(validation.exitCode != 0, "invalid voucher unexpectedly succeeded");
        validation.stderrJsonObject();
        require(validation.stderr.contains("\"code\":\"VOUCHER_INVALID\""),
                "invalid voucher lacks stable error code");

        Result missing = cli("--format", "json", "voucher", "show", "2147483647");
        require(missing.exitCode != 0, "missing voucher unexpectedly succeeded");
        missing.stderrJsonObject();
        require(missing.stderr.contains("\"code\":\"VOUCHER_NOT_FOUND\""),
                "missing voucher lacks stable error code");
    }

    private static Result cli(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        if (jarLauncher) {
            command.add(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString());
            command.add("-jar");
        }
        command.add(launcher.toString());
        command.add("--config");
        command.add(config.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        return new Result(exitCode, stdout, stderr, command);
    }

    private static String executable(String name) {
        return System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
    }

    private static int firstInt(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        require(matcher.find(), "JSON lacks numeric key " + key + ": " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private static int firstIntAfter(String json, String section, String key) {
        int sectionStart = json.indexOf("\"" + section + "\"");
        require(sectionStart >= 0, "JSON lacks section " + section);
        return firstInt(json.substring(sectionStart), key);
    }

    private static List<Integer> allInts(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        List<Integer> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(Integer.parseInt(matcher.group(1)));
        }
        return values;
    }

    private static String firstString(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        require(matcher.find(), "JSON lacks string key " + key + ": " + json);
        return matcher.group(1);
    }

    private static int count(String value, String fragment) {
        int count = 0;
        for (int start = 0; (start = value.indexOf(fragment, start)) >= 0; start += fragment.length()) {
            count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> command) {
        void success() {
            require(exitCode == 0, "Command failed (" + exitCode + "): " + command
                    + "\nstdout: " + stdout + "\nstderr: " + stderr);
        }

        void jsonObject() {
            require(stdout.startsWith("{") && stdout.endsWith("}"),
                    "stdout is not a clean JSON object: " + stdout);
        }

        void stderrJsonObject() {
            String lastLine = stderr.lines().reduce((first, second) -> second).orElse("");
            require(lastLine.startsWith("{") && lastLine.endsWith("}"),
                    "stderr does not end in a JSON error: " + stderr);
        }
    }
}
