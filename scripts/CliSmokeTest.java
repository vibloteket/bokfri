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
            runCompanyAndYearCreationFlow(temporary);
            runOpeningBalanceFlow(temporary);
            runReferenceDataFlow(temporary);
            runProductFlow(temporary);
            runInvoiceFlow(temporary);
            runInpaymentFlow(temporary);
            runSupplierFlow(temporary);
            runSupplierInvoiceFlow(temporary);
            runOutpaymentFlow(temporary);
            runVatFlow(temporary);
            runVoucherFlow(temporary);
            runNextYearFlow(temporary);
            runSieExportFlow(temporary);
            runBackupFlow(temporary);
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

    private static void runCompanyAndYearCreationFlow(Path temporary) throws Exception {
        Result plans = cli("--format", "json", "account-plan", "list");
        plans.success();
        int planId = firstInt(plans.stdout, "id");
        Path company = temporary.resolve("company.json");
        Files.writeString(company, "{\"name\":\"CLI isolated full-year company\"}", StandardCharsets.UTF_8);
        Result created = cli("--format", "json", "company", "create", "--file", company.toString());
        created.success();
        int companyId = firstInt(created.stdout, "id");
        Path year = temporary.resolve("year-2026.json");
        Files.writeString(year, "{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\",\"accountPlanId\":"
                + planId + "}", StandardCharsets.UTF_8);
        Result createdYear = cli("--company-id", Integer.toString(companyId), "--format", "json",
                "year", "create", "--file", year.toString());
        createdYear.success();
        int yearId = firstInt(createdYear.stdout, "id");
        require(createdYear.stdout.contains("\"from\":\"2026-01-01\""),
                "accounting year was not created");

        cli("context", "create", "isolated-full-year", "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(yearId)).success();
        cli("context", "use", "isolated-full-year").success();
        Result current = cli("--format", "json", "context", "current");
        current.success();
        require(current.stdout.contains("\"companyId\":" + companyId),
                "isolated context did not select the created company");
        require(current.stdout.contains("\"yearId\":" + yearId),
                "isolated context did not select the created year");

        Files.writeString(temporary.resolve("created-company.txt"), Integer.toString(companyId));
        Files.writeString(temporary.resolve("created-year.txt"), Integer.toString(yearId));
        Files.writeString(temporary.resolve("account-plan.txt"), Integer.toString(planId));
    }

    private static void runOpeningBalanceFlow(Path temporary) throws Exception {
        Result accounts = cli("--format", "json", "account", "list");
        accounts.success();
        List<Integer> numbers = accountNumbersByFlag(accounts.stdout, "balanceAccount", true);
        require(numbers.size() >= 2, "new year lacks balance accounts");
        Path input = temporary.resolve("opening-balance.json");
        Files.writeString(input, "{\"balances\":[{\"account\":" + numbers.get(0)
                + ",\"amount\":\"100.00\"},{\"account\":" + numbers.get(1)
                + ",\"amount\":\"-100.00\"}]}", StandardCharsets.UTF_8);
        Result validate = cli("--format", "json", "opening-balance", "validate",
                "--file", input.toString());
        validate.success();
        require(validate.stdout.contains("\"difference\":\"0.00\""),
                "opening balance validation is not balanced");
        Result set = cli("--format", "json", "opening-balance", "set", "--file", input.toString());
        set.success();
        require(set.stdout.contains("\"written\":true"), "opening balance was not written");
        Result show = cli("--format", "json", "opening-balance", "show");
        show.success();
        require(show.stdout.contains("\"debitTotal\":\"100.00\""),
                "opening balance was not persisted in the isolated year");
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
        List<Integer> resultAccounts = accountNumbersByFlag(accounts.stdout, "balanceAccount", false);
        require(!resultAccounts.isEmpty(), "new year lacks result accounts");
        int salesAccount = resultAccounts.get(0);
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

    private static void runSupplierFlow(Path temporary) throws Exception {
        Path input = temporary.resolve("supplier.json");
        Files.writeString(input, """
                {
                  "number": "CLI-SMOKE-SUPPLIER",
                  "name": "CLI smoke supplier",
                  "email": "supplier@example.invalid",
                  "address": {"city": "Test city"}
                }
                """, StandardCharsets.UTF_8);
        cli("--format", "json", "supplier", "validate", "--file", input.toString()).success();
        Result dryRun = cli("--format", "json", "supplier", "create", "--dry-run",
                "--file", input.toString());
        dryRun.success();
        require(!dryRun.stdout.contains("\"created\":true"),
                "supplier dry run unexpectedly created data");
        Result create = cli("--format", "json", "supplier", "create", "--file", input.toString());
        create.success();
        require(create.stdout.contains("\"created\":true"), "supplier create lacks created=true");
        Result list = cli("--format", "json", "supplier", "list");
        list.success();
        require(list.stdout.contains("\"number\":\"CLI-SMOKE-SUPPLIER\""),
                "created supplier is absent from list");
        Result show = cli("--format", "json", "supplier", "show", "CLI-SMOKE-SUPPLIER");
        show.success();
        require(show.stdout.contains("\"name\":\"CLI smoke supplier\""),
                "supplier show returned the wrong supplier");
    }

    private static void runSupplierInvoiceFlow(Path temporary) throws Exception {
        Result accounts=cli("--format","json","account","list");accounts.success();List<Integer> resultAccounts=accountNumbersByFlag(accounts.stdout,"balanceAccount",false);require(!resultAccounts.isEmpty(),"new year lacks purchase accounts");int account=resultAccounts.get(0);
        Result years=cli("--format","json","year","list");years.success();String date=firstString(years.stdout,"from");
        Path input=temporary.resolve("supplier-invoice.json");Files.writeString(input,"""
                {"supplierNumber":"CLI-SMOKE-SUPPLIER","date":"%s","vat":"0","rows":[{"description":"CLI smoke purchase","quantity":1,"unitPrice":"200.00","account":%d}]}
                """.formatted(date,account),StandardCharsets.UTF_8);
        cli("--format","json","supplier-invoice","validate","--file",input.toString()).success();
        Result dry=cli("--format","json","supplier-invoice","create","--dry-run","--file",input.toString());dry.success();int number=firstInt(dry.stdout,"number");
        Result create=cli("--format","json","supplier-invoice","create","--file",input.toString());create.success();require(firstInt(create.stdout,"number")==number,"supplier invoice number differs from dry run");
        cli("--format","json","supplier-invoice","show",Integer.toString(number)).success();
        Result preview=cli("--format","json","supplier-invoice","journal","--from",date,"--to",date);preview.success();require(preview.stdout.contains("\"committed\":false"),"supplier invoice journal preview committed");
        Result commit=cli("--format","json","supplier-invoice","journal","--from",date,"--to",date,"--commit");commit.success();require(commit.stdout.contains("\"committed\":true"),"supplier invoice journal did not commit");
        Result shown=cli("--format","json","supplier-invoice","show",Integer.toString(number));shown.success();require(shown.stdout.contains("\"entered\":true"),"supplier invoice was not marked entered");
        Files.writeString(temporary.resolve("supplier-invoice-number.txt"),Integer.toString(number));
    }

    private static void runOutpaymentFlow(Path temporary) throws Exception {
        int invoice=Integer.parseInt(Files.readString(temporary.resolve("supplier-invoice-number.txt")));
        Result shown=cli("--format","json","supplier-invoice","show",Integer.toString(invoice));shown.success();String date=firstString(shown.stdout,"date");String balance=firstString(shown.stdout,"balance");
        Path input=temporary.resolve("outpayment.json");Files.writeString(input,"""
                {"date":"%s","text":"CLI smoke outpayment","rows":[{"invoiceNumber":%d,"amount":"%s"}]}
                """.formatted(date,invoice,balance),StandardCharsets.UTF_8);
        cli("--format","json","outpayment","validate","--file",input.toString()).success();Result dry=cli("--format","json","outpayment","create","--dry-run","--file",input.toString());dry.success();int number=firstInt(dry.stdout,"number");
        Result create=cli("--format","json","outpayment","create","--file",input.toString());create.success();require(firstInt(create.stdout,"number")==number,"outpayment number differs from dry run");
        cli("--format","json","outpayment","show",Integer.toString(number)).success();cli("--format","json","outpayment","journal","--from",date,"--to",date).success();Result commit=cli("--format","json","outpayment","journal","--from",date,"--to",date,"--commit");commit.success();
        shown=cli("--format","json","supplier-invoice","show",Integer.toString(invoice));shown.success();require(shown.stdout.contains("\"balance\":\"0"),"supplier invoice balance is not zero");
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

    private static void runInpaymentFlow(Path temporary) throws Exception {
        Result invoices = cli("--format", "json", "invoice", "list");
        invoices.success();
        int invoiceNumber = invoiceNumberForCustomer(invoices.stdout, "CLI-SMOKE-CUSTOMER");
        Result invoice = cli("--format", "json", "invoice", "show", Integer.toString(invoiceNumber));
        invoice.success();
        String date = firstString(invoice.stdout, "date");
        String balance = firstString(invoice.stdout, "balance");
        Path input = temporary.resolve("inpayment.json");
        Files.writeString(input, """
                {
                  "date": "%s",
                  "text": "CLI smoke inpayment",
                  "rows": [{"invoiceNumber": %d, "amount": "%s"}]
                }
                """.formatted(date, invoiceNumber, balance), StandardCharsets.UTF_8);
        cli("--format", "json", "inpayment", "validate", "--file", input.toString()).success();
        Result dryRun = cli("--format", "json", "inpayment", "create", "--dry-run",
                "--file", input.toString());
        dryRun.success();
        int expectedNumber = firstInt(dryRun.stdout, "number");
        Result create = cli("--format", "json", "inpayment", "create", "--file", input.toString());
        create.success();
        require(firstInt(create.stdout, "number") == expectedNumber,
                "created inpayment number differs from dry run");
        cli("--format", "json", "inpayment", "show", Integer.toString(expectedNumber)).success();
        Result preview = cli("--format", "json", "inpayment", "journal",
                "--from", date, "--to", date);
        preview.success();
        require(preview.stdout.contains("\"committed\":false"),
                "inpayment journal preview unexpectedly committed");
        Result commit = cli("--format", "json", "inpayment", "journal",
                "--from", date, "--to", date, "--commit");
        commit.success();
        require(commit.stdout.contains("\"committed\":true"),
                "inpayment journal did not commit");
        Result paidInvoice = cli("--format", "json", "invoice", "show", Integer.toString(invoiceNumber));
        paidInvoice.success();
        require(paidInvoice.stdout.contains("\"balance\":\"0"),
                "paid invoice balance is not zero");
    }

    private static void runVatFlow(Path temporary) throws Exception {
        Result years=cli("--format","json","year","list");years.success();String from=firstString(years.stdout,"from");String to=firstString(years.stdout,"to");
        Result report=cli("--format","json","vat","report","--from",from,"--to",to);report.success();require(report.stdout.contains("\"boxes\":"),"VAT report lacks boxes");
        Result preview=cli("--format","json","vat","settle","--from",from,"--to",to);preview.success();require(preview.stdout.contains("\"committed\":false"),"VAT preview committed");
        Result commit=cli("--format","json","vat","settle","--from",from,"--to",to,"--commit");commit.success();require(commit.stdout.contains("\"committed\":true"),"VAT settlement did not commit");int voucher=firstInt(commit.stdout,"voucherNumber");cli("--format","json","voucher","show",Integer.toString(voucher)).success();
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

    private static void runNextYearFlow(Path temporary) throws Exception {
        int companyId = Integer.parseInt(Files.readString(temporary.resolve("created-company.txt")));
        int fromYearId = Integer.parseInt(Files.readString(temporary.resolve("created-year.txt")));
        int planId = Integer.parseInt(Files.readString(temporary.resolve("account-plan.txt")));
        Path year = temporary.resolve("year-2027.json");
        Files.writeString(year, "{\"from\":\"2027-01-01\",\"to\":\"2027-12-31\",\"accountPlanId\":"
                + planId + "}", StandardCharsets.UTF_8);
        Result created = cli("--company-id", Integer.toString(companyId), "--format", "json",
                "year", "create", "--file", year.toString());
        created.success();
        int toYearId = firstInt(created.stdout, "id");

        cli("context", "create", "isolated-next-year", "--data-dir", data.toString(),
                "--company-id", Integer.toString(companyId), "--year-id", Integer.toString(toYearId)).success();
        cli("context", "use", "isolated-next-year").success();
        Result current = cli("--format", "json", "context", "current");
        current.success();
        require(current.stdout.contains("\"yearId\":" + toYearId),
                "next-year context did not select 2027");

        Result preview = cli("--format", "json", "opening-balance", "carry-forward",
                "--from-year-id", Integer.toString(fromYearId));
        preview.success();
        require(preview.stdout.contains("\"committed\":false"),
                "carry-forward preview unexpectedly committed");
        require(!preview.stdout.contains("\"balances\":[]"),
                "carry-forward preview contains no balances");
        Result commit = cli("--format", "json", "opening-balance", "carry-forward",
                "--from-year-id", Integer.toString(fromYearId), "--commit");
        commit.success();
        require(commit.stdout.contains("\"committed\":true"), "carry-forward did not commit");
        Result show = cli("--format", "json", "opening-balance", "show");
        show.success();
        require(show.stdout.contains("\"difference\":\"0.00\""),
                "2027 opening balance is not balanced");
        require(!show.stdout.contains("\"balances\":[]"),
                "2027 opening balance was not persisted");
    }

    private static void runSieExportFlow(Path temporary) throws Exception {
        Path output = temporary.resolve("company-2027.se");
        Result export = cli("--format", "json", "sie", "export", "--output", output.toString());
        export.success();
        require(Files.size(output) > 100, "SIE export is unexpectedly small");
        String content = Files.readString(output, java.nio.charset.Charset.forName("IBM-437"));
        require(content.contains("#SIETYP 4"), "SIE export lacks type declaration");
        require(content.contains("#FNAMN"), "SIE export lacks company name");
        require(content.contains("#RAR"), "SIE export lacks accounting year");
        Result duplicate = cli("--format", "json", "sie", "export", "--output", output.toString());
        require(duplicate.exitCode != 0, "SIE export unexpectedly overwrote an existing file");
        require(duplicate.stderr.contains("\"code\":\"OUTPUT_EXISTS\""),
                "duplicate SIE output lacks stable error code");
    }

    private static void runBackupFlow(Path temporary) throws Exception {
        Path output = temporary.resolve("bokfri-backup.zip");
        Result create = cli("--format", "json", "backup", "create", "--output", output.toString());
        create.success();
        require(Files.size(output) > 1_000, "backup archive is unexpectedly small");
        Result verify = cli("--format", "json", "backup", "verify", "--file", output.toString());
        verify.success();
        require(verify.stdout.contains("\"valid\":true"), "backup verification did not succeed");
        require(verify.stdout.contains("JFSDB.properties"), "backup lacks database properties");
        require(verify.stdout.contains("JFSDB.script"), "backup lacks database script");
        require(verify.stdout.contains("backup.info"), "backup lacks metadata");
        Result list = cli("--format", "json", "backup", "list");
        list.success();
        require(list.stdout.contains(output.toAbsolutePath().toString().replace("\\", "\\\\")),
                "created backup is absent from backup list");
        Result duplicate = cli("--format", "json", "backup", "create", "--output", output.toString());
        require(duplicate.exitCode != 0, "backup unexpectedly overwrote an existing file");
        require(duplicate.stderr.contains("\"code\":\"OUTPUT_EXISTS\""),
                "duplicate backup output lacks stable error code");
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

    private static List<Integer> accountNumbersByFlag(String json, String flag, boolean value) {
        Matcher matcher = Pattern.compile("\\{\\\"number\\\":(\\d+).*?\\\"" + flag
                + "\\\":" + value).matcher(json);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) numbers.add(Integer.parseInt(matcher.group(1)));
        return numbers;
    }

    private static int supplierInvoiceNumberForSupplier(String json, String supplierNumber) {
        Matcher matcher = Pattern.compile("\\{\\\"number\\\":(\\d+).*?\\\"supplierNumber\\\":\\\""
                + Pattern.quote(supplierNumber) + "\\\"").matcher(json);
        require(matcher.find(), "Supplier invoice list lacks supplier " + supplierNumber);
        return Integer.parseInt(matcher.group(1));
    }

    private static int invoiceNumberForCustomer(String json, String customerNumber) {
        Matcher matcher = Pattern.compile("\\{\\\"number\\\":(\\d+).*?\\\"customerNumber\\\":\\\""
                + Pattern.quote(customerNumber) + "\\\"").matcher(json);
        require(matcher.find(), "Invoice list lacks customer " + customerNumber);
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
