import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Builds the deterministic PDF gallery through the assembled Bokfri CLI. */
public final class PdfGallery {
    private static Path launcher;
    private static boolean jarLauncher;
    private static Path work;
    private static Path output;
    private static Path config;
    private static Path data;
    private static int companyId;
    private static int yearId;

    private PdfGallery() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: java -cp <bokfri-fat-jar> scripts/PdfGallery.java "
                            + "<fat-jar-or-packaged-launcher> <output-directory>");
        }
        launcher = Path.of(arguments[0]).toAbsolutePath().normalize();
        jarLauncher = launcher.getFileName().toString().endsWith(".jar");
        require(Files.isRegularFile(launcher), "Launcher does not exist: " + launcher);
        output = Path.of(arguments[1]).toAbsolutePath().normalize();
        recreateDirectory(output);
        work = Files.createTempDirectory("bokfri-pdf-gallery-");
        config = work.resolve("config/database.config");
        data = work.resolve("data");
        Files.createDirectories(data);

        try {
            int invoiceNumber = generateInvoiceScenario();
            generateOcrInvoiceScenario(invoiceNumber);
            generateDeliveryNoteScenarios(invoiceNumber);
            generatePickingListScenario(invoiceNumber);
            generateSalesReportScenario();
            int longInvoiceNumber = generateMultipageInvoiceScenario();
            int amountsInvoiceNumber = generateAmountsInvoiceScenario();
            generateCustomerListScenario();
            int voucherNumber = generateInvoiceJournalScenario(invoiceNumber);
            generateReceivablesBeforePaymentScenario();
            generateCreditInvoiceScenario(invoiceNumber);
            generateInpaymentScenarios(invoiceNumber);
            generateReceivablesAfterPaymentScenario();
            generateVoucherScenario(voucherNumber);
            generateVoucherListScenario(voucherNumber);
            generateGeneralLedgerScenario();
            generateIncomeStatementScenario();
            generateBalanceSheetScenario();
            generateVatReportScenario();
            generatePayablesScenarios();
            generateDocumentRegisterScenarios();
            writeIndex(invoiceNumber, longInvoiceNumber, amountsInvoiceNumber, voucherNumber);
            System.out.println("PDF gallery generated: " + output);
        } finally {
            deleteRecursively(work);
        }
    }

    private static int generateInvoiceScenario() throws Exception {
        int planId = firstInt(cli("account-plan", "list").stdout(), "id");
        Path company = json("company.json", """
                {
                  "name": "Galleri AB",
                  "corporateId": "559999-1234",
                  "email": "ekonomi@galleri.invalid",
                  "bankgiro": "5555-0100"
                }
                """);
        companyId = firstInt(cli("company", "create", "--file", company.toString()).stdout(), "id");
        Path year = json("year.json", """
                {"from":"2026-01-01","to":"2026-12-31","accountPlanId":%d}
                """.formatted(planId));
        yearId = firstInt(cliWithCompany("year", "create", "--file", year.toString()).stdout(), "id");

        Path customer = json("customer.json", """
                {
                  "number": "K100",
                  "name": "Exempelkund ÅÄÖ AB",
                  "registrationNumber": "559999-5678",
                  "vatNumber": "SE559999567801",
                  "email": "faktura@example.invalid",
                  "phone": "+46 8 123 45 67",
                  "ourContact": "Anna Åström",
                  "yourContact": "Émile Öberg",
                  "invoiceAddress": {
                    "name": "Exempelkund ÅÄÖ AB",
                    "address1": "Testgatan 15",
                    "address2": "Ekonomiavdelningen",
                    "postalCode": "123 45",
                    "city": "Testköping",
                    "country": "Sverige"
                  },
                  "deliveryAddress": {
                    "name": "Exempelkund ÅÄÖ – Lager & Gods",
                    "address1": "Leveransvägen 27",
                    "address2": "Port B, lastkaj 4",
                    "postalCode": "987 65",
                    "city": "Åmål",
                    "country": "Sverige"
                  }
                }
                """);
        cliInYear("customer", "create", "--file", customer.toString()).success();

        Path product = json("product.json", """
                {
                  "number": "P100",
                  "description": "Galleriartikel decimal",
                  "sellingPrice": "125.00",
                  "vatRate": "25",
                  "salesAccount": 3001,
                  "stockProduct": false,
                  "weight": "1.25",
                  "volume": "0.75"
                }
                """);
        cliInYear("product", "create", "--file", product.toString()).success();

        Path invoiceInput = json("invoice.json", """
                {
                  "customerNumber": "K100",
                  "date": "2026-03-15",
                  "dueDate": "2026-04-14",
                  "yourOrderNumber": "ORDER-XYZ",
                  "text": "Deterministisk gallerifaktura – tack för ert köp!",
                  "rows": [
                    {"productNumber":"P100","quantity":"2.25"},
                    {
                      "description":"Konsulttjänst – åäö & analys",
                      "quantity":"0.5","unitPrice":"80.00","vatRate":"25","salesAccount":3001
                    },
                    {
                      "description":"Mycket litet belopp","quantity":"1","unitPrice":"0.01",
                      "vatRate":"25","salesAccount":3001
                    }
                  ]
                }
                """);
        int invoiceNumber = firstInt(
                cliInYear("invoice", "create", "--file", invoiceInput.toString()).stdout(), "number");

        Path scenario = output.resolve("invoice");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice.pdf");
        cliInYear("invoice", "pdf", Integer.toString(invoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("K100", "Exempelkund ÅÄÖ AB",
                "Leveransvägen 27", "lastkaj 4", "Galleriartikel decimal", "2.25st", "0.5",
                "Mycket litet belopp", "ORDER-XYZ"));
        renderPages(pdf, scenario);
        return invoiceNumber;
    }

    private static void generateOcrInvoiceScenario(int invoiceNumber) throws Exception {
        Path scenario = output.resolve("invoice-ocr");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice-ocr.pdf");
        Result invoice = cliInYear("invoice", "pdf", Integer.toString(invoiceNumber),
                "--ocr", "--ocr-background", "--output", pdf.toString());
        require(invoice.stdout().contains("\"ocr\":true"),
                "OCR invoice output does not identify the OCR variant");
        require(invoice.stdout().contains("\"ocrNumber\":\""),
                "OCR invoice output lacks an OCR number");
        verifyPdf(pdf, List.of("K100", "Exempelkund ÅÄÖ AB", "5555-0100",
                "Galleriartikel decimal", "ATT BETALA"));
        renderPages(pdf, scenario);
    }

    private static void generateDeliveryNoteScenarios(int invoiceNumber) throws Exception {
        for (boolean hidePrices : List.of(false, true)) {
            String name = hidePrices ? "delivery-note-without-prices" : "delivery-note";
            Path scenario = output.resolve(name);
            Files.createDirectories(scenario);
            Path pdf = scenario.resolve(name + ".pdf");
            java.util.ArrayList<String> arguments = new java.util.ArrayList<>(List.of(
                    "invoice", "delivery-note", Integer.toString(invoiceNumber),
                    "--output", pdf.toString()));
            if (hidePrices) arguments.add("--hide-unit-price");
            cliInYear(arguments.toArray(String[]::new)).success();
            List<String> expected = new java.util.ArrayList<>(List.of("FÖLJESEDEL", "K100",
                    "Leveransvägen 27", "Galleriartikel decimal", "2.25"));
            if (!hidePrices) expected.add("125,00");
            verifyPdf(pdf, expected);
            if (hidePrices) {
                verifyPdfLacks(pdf, List.of("125,00", "281,25"));
            }
            renderPages(pdf, scenario);
        }
    }

    private static void generatePickingListScenario(int invoiceNumber) throws Exception {
        Path scenario = output.resolve("picking-list");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("picking-list.pdf");
        cliInYear("invoice", "picking-list", Integer.toString(invoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("PLOCKLISTA", "K100", "Galleriartikel decimal", "2.25",
                "2,812", "1,688"));
        renderPages(pdf, scenario);
    }

    private static void generateSalesReportScenario() throws Exception {
        Path scenario = output.resolve("sales-report");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("sales-report.pdf");
        cliInYear("sales-report", "--from", "2026-01-01", "--to", "2026-12-31",
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("Försäljningsrapport", "P100", "Galleriartikel decimal", "2.25"));
        renderPages(pdf, scenario);
    }

    private static int generateMultipageInvoiceScenario() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int row = 1; row <= 45; row++) {
            if (row > 1) rows.append(',');
            rows.append("""
                    {"description":"Flersidig rad %02d – stabil sidbrytning",
                     "quantity":"1","unitPrice":"%d.00","vatRate":"25","salesAccount":3001}
                    """.formatted(row, row));
        }
        Path invoiceInput = json("multipage-invoice.json", """
                {
                  "customerNumber":"K100",
                  "date":"2026-04-20",
                  "dueDate":"2026-05-20",
                  "yourOrderNumber":"ORDER-MULTIPAGE",
                  "text":"Flersidig faktura med 45 deterministiska rader",
                  "rows":[%s]
                }
                """.formatted(rows));
        int invoiceNumber = firstInt(cliInYear("invoice", "create", "--file",
                invoiceInput.toString()).stdout(), "number");

        Path scenario = output.resolve("invoice-multipage");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice-multipage.pdf");
        cliInYear("invoice", "pdf", Integer.toString(invoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("ORDER-MULTIPAGE", "Flersidig rad 01", "Flersidig rad 45"));
        verifyMultipageInvoiceText(pdf);
        int pages = renderPages(pdf, scenario);
        require(pages >= 2, "Multipage invoice rendered only " + pages + " page(s)");
        return invoiceNumber;
    }

    private static int generateAmountsInvoiceScenario() throws Exception {
        Path invoiceInput = json("amounts-invoice.json", """
                {
                  "customerNumber":"K100",
                  "date":"2026-05-10",
                  "dueDate":"2026-06-09",
                  "yourOrderNumber":"ORDER-AMOUNTS",
                  "text":"Belopps- och momsgränser",
                  "rows":[
                    {"description":"Minsta belopp 25 %","quantity":"1","unitPrice":"0.01",
                     "vatRate":"25","salesAccount":3001},
                    {"description":"Stort belopp med rabatt","quantity":"1","unitPrice":"1234567.89",
                     "discount":"12.5","vatRate":"25","salesAccount":3001},
                    {"description":"Halvtimme med 12 % moms","quantity":"0.5","unitPrice":"199.95",
                     "vatRate":"12","salesAccount":3001},
                    {"description":"Kvartsenhet med 6 % moms","quantity":"2.25","unitPrice":"9.99",
                     "vatRate":"6","salesAccount":3001}
                  ]
                }
                """);
        Result created = cliInYear("invoice", "create", "--file", invoiceInput.toString());
        int invoiceNumber = firstInt(created.stdout(), "number");
        require(created.stdout().contains("\"rowCount\":4"),
                "Amounts invoice does not contain four rows");
        require(created.stdout().contains("\"net\":\"1080369.37\""),
                "Amounts invoice has an unexpected net total");
        require(created.stdout().contains("\"vat\":\"270075.08\""),
                "Amounts invoice has an unexpected VAT total");
        require(created.stdout().contains("\"total\":\"1350444.00\""),
                "Amounts invoice has an unexpected total");

        Path scenario = output.resolve("invoice-amounts");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice-amounts.pdf");
        cliInYear("invoice", "pdf", Integer.toString(invoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("ORDER-AMOUNTS", "Minsta belopp", "Stort belopp",
                "246,90", "12,50", "Halvtimme", "Kvartsenhet", "Moms 25%", "Moms 12%", "Moms 6%",
                "270", "061,73", "12,00", "1,35"));
        int pages = renderPages(pdf, scenario);
        require(pages == 1, "Amounts invoice unexpectedly rendered " + pages + " pages");
        return invoiceNumber;
    }

    private static void generateCustomerListScenario() throws Exception {
        Path scenario = output.resolve("customer-list");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("customer-list.pdf");
        cliInYear("customer", "list", "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("K100", "Exempelkund ÅÄÖ AB",
                "559999-5678", "+46 8 123 45 67"));
        renderPages(pdf, scenario);
    }

    private static int generateInvoiceJournalScenario(int invoiceNumber) throws Exception {
        Path scenario = output.resolve("invoice-journal");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice-journal.pdf");
        Result journal = cliInYear("invoice", "journal", "--from", "2026-03-15",
                "--to", "2026-03-15", "--output", pdf.toString(), "--commit");
        require(journal.stdout().contains("\"invoiceNumbers\":[" + invoiceNumber + "]"),
                "Invoice journal does not contain gallery invoice " + invoiceNumber);
        require(journal.stdout().contains("\"debitTotal\":\"402.00\""),
                "Invoice journal has an unexpected debit total: " + journal.stdout());
        require(journal.stdout().contains("\"creditTotal\":\"402.00\""),
                "Invoice journal does not balance: " + journal.stdout());
        require(journal.stdout().contains("\"committed\":true"),
                "Invoice journal was not committed");
        int voucherNumber = firstInt(journal.stdout(), "voucherNumber");
        verifyPdf(pdf, List.of("Fakturajournal", "K100", "Exempelkund ÅÄÖ AB",
                "1510", "3001", "2611", "3740"));
        verifyPdfLacks(pdf, List.of("null"));
        String journalText = extractPdfText(pdf);
        require(journalText.indexOf("3001") < journalText.indexOf("Total summa"),
                "Invoice journal totals appear before the final voucher row");
        renderPages(pdf, scenario);

        Result bookedInvoice = cliInYear("invoice", "show", Integer.toString(invoiceNumber));
        require(bookedInvoice.stdout().contains("\"entered\":true"),
                "Gallery invoice was not marked as entered");
        return voucherNumber;
    }

    private static void generateReceivablesBeforePaymentScenario() throws Exception {
        generateReceivablesReport("accounts-receivable-before-payment", "accounts-receivable",
                "2026-03-16", List.of("K100", "Exempelkund ÅÄÖ AB", "402.00"));
        generateReceivablesReport("customer-claims-before-payment", "customer-claims",
                "2026-03-16", List.of("K100", "Exempelkund ÅÄÖ AB", "402.00"));
    }

    private static void generateInpaymentScenarios(int invoiceNumber) throws Exception {
        Path input = json("inpayment.json", """
                {
                  "date":"2026-04-14",
                  "text":"Full betalning av gallerifaktura",
                  "rows":[{"invoiceNumber":%d,"amount":"302.00","currencyRate":"1.00"}]
                }
                """.formatted(invoiceNumber));
        Result created = cliInYear("inpayment", "create", "--file", input.toString());
        int inpaymentNumber = firstInt(created.stdout(), "number");
        require(created.stdout().contains("\"total\":\"302.00\""),
                "Inpayment has an unexpected total: " + created.stdout());

        Path listScenario = output.resolve("inpayment-list");
        Files.createDirectories(listScenario);
        Path listPdf = listScenario.resolve("inpayment-list.pdf");
        cliInYear("inpayment", "pdf-list", "--output", listPdf.toString()).success();
        verifyPdf(listPdf, List.of("Inbetalnings nr", Integer.toString(inpaymentNumber),
                "Full betalning av gallerifaktura", "302.00"));
        renderPages(listPdf, listScenario);

        Path journalScenario = output.resolve("inpayment-journal");
        Files.createDirectories(journalScenario);
        Path journalPdf = journalScenario.resolve("inpayment-journal.pdf");
        Result journal = cliInYear("inpayment", "journal", "--from", "2026-04-14",
                "--to", "2026-04-14", "--output", journalPdf.toString(), "--commit");
        require(journal.stdout().contains("\"debitTotal\":\"302.00\""),
                "Inpayment journal has an unexpected debit total: " + journal.stdout());
        require(journal.stdout().contains("\"creditTotal\":\"302.00\""),
                "Inpayment journal does not balance: " + journal.stdout());
        verifyPdf(journalPdf, List.of("Inbetalningsjournal", "1510", "1930", "302.00"));
        String journalText = extractPdfText(journalPdf);
        require(journalText.indexOf("1930") < journalText.indexOf("Summa redovisningsvaluta"),
                "Inpayment journal total appears before the final account row");
        renderPages(journalPdf, journalScenario);
    }

    private static void generateReceivablesAfterPaymentScenario() throws Exception {
        generateReceivablesReport("accounts-receivable-after-payment", "accounts-receivable",
                "2026-04-15", List.of("0.00"));
        generateReceivablesReport("customer-claims-after-payment", "customer-claims",
                "2026-04-15", List.of("0.00"));
    }

    private static void generateReceivablesReport(String scenarioName, String command,
            String date, List<String> expected) throws Exception {
        Path scenario = output.resolve(scenarioName);
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve(scenarioName + ".pdf");
        cliInYear(command, "--date", date, "--output", pdf.toString()).success();
        verifyPdf(pdf, expected);
        renderPages(pdf, scenario);
    }

    private static void generateCreditInvoiceScenario(int invoiceNumber) throws Exception {
        Path input = json("credit-invoice.json", """
                {"invoiceNumber":%d,"date":"2026-03-20","amount":"100.00"}
                """.formatted(invoiceNumber));
        Result created = cliInYear("credit-invoice", "create", "--file", input.toString());
        int creditInvoiceNumber = firstInt(created.stdout(), "number");
        require(created.stdout().contains("\"creditingInvoiceNumber\":" + invoiceNumber),
                "Credit invoice does not refer to invoice " + invoiceNumber);
        require(created.stdout().contains("\"total\":\"100.00\""),
                "Credit invoice has an unexpected total: " + created.stdout());

        Path scenario = output.resolve("credit-invoice");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("credit-invoice.pdf");
        cliInYear("credit-invoice", "pdf", Integer.toString(creditInvoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("KREDITFAKTURA", "K100", "Exempelkund ÅÄÖ AB",
                "Galleriartikel decimal", "100,00"));
        renderPages(pdf, scenario);
    }

    private static void generateVoucherScenario(int voucherNumber) throws Exception {
        Path scenario = output.resolve("voucher");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("voucher.pdf");
        Result voucher = cliInYear("voucher", "show", Integer.toString(voucherNumber),
                "--output", pdf.toString());
        require(voucher.stdout().contains("\"debitTotal\":\"402.00\""),
                "Voucher has an unexpected debit total: " + voucher.stdout());
        require(voucher.stdout().contains("\"creditTotal\":\"402.00\""),
                "Voucher does not balance: " + voucher.stdout());
        for (String account : List.of("1510", "3001", "2611", "3740")) {
            require(voucher.stdout().contains("\"account\":" + account),
                    "Voucher lacks account " + account);
        }
        verifyPdf(pdf, List.of("Verifikation", "Fakturajournal", "1510", "3001", "2611", "3740"));
        verifyPdfLacks(pdf, List.of("Preview voucher"));
        renderPages(pdf, scenario);
    }

    private static void generateVoucherListScenario(int voucherNumber) throws Exception {
        Path scenario = output.resolve("voucher-list");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("voucher-list.pdf");
        Result vouchers = cliInYear("voucher", "list", "--from", "2026-03-15",
                "--to", "2026-03-15", "--output", pdf.toString());
        require(vouchers.stdout().contains("\"number\":" + voucherNumber),
                "Voucher list lacks invoice journal voucher " + voucherNumber);
        require(vouchers.stdout().contains("\"debitTotal\":\"402.00\""),
                "Voucher list has an unexpected debit total: " + vouchers.stdout());
        require(vouchers.stdout().contains("\"creditTotal\":\"402.00\""),
                "Voucher list does not balance: " + vouchers.stdout());
        verifyPdf(pdf, List.of("Fakturajournal", "1510", "3001", "2611", "3740"));
        renderPages(pdf, scenario);
    }

    private static void generateGeneralLedgerScenario() throws Exception {
        Path scenario = output.resolve("general-ledger-3001");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("general-ledger-3001.pdf");
        Result ledger = cliInYear("general-ledger", "--account", "3001",
                "--from", "2026-01-01", "--to", "2026-12-31", "--output", pdf.toString());
        require(ledger.stdout().contains("\"account\":3001"),
                "General ledger output does not describe account 3001");
        require(!ledger.stdout().contains("\"rows\":[]"),
                "General ledger for account 3001 contains no transactions");
        verifyPdf(pdf, List.of("3001", "Fakturajournal", "321.26"));
        renderPages(pdf, scenario);
    }

    private static void generateIncomeStatementScenario() throws Exception {
        Path scenario = output.resolve("income-statement");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("income-statement.pdf");
        Result report = cliInYear("income-statement", "--from", "2026-01-01",
                "--to", "2026-12-31", "--output", pdf.toString());
        require(report.stdout().contains("\"from\":\"2026-01-01\""),
                "Income statement has an unexpected start date");
        require(report.stdout().contains("\"to\":\"2026-12-31\""),
                "Income statement has an unexpected end date");
        require(report.stdout().contains("\"result\":\"321.68\""),
                "Income statement has an unexpected result: " + report.stdout());
        require(!report.stdout().contains("\"rows\":[]"),
                "Income statement contains no rows");
        verifyPdf(pdf, List.of("Resultatrapport", "3001", "321.26", "3740", "0.42"));
        renderPages(pdf, scenario);
    }

    private static void generateVatReportScenario() throws Exception {
        Path scenario = output.resolve("vat-report");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("vat-report.pdf");
        Result report = cliInYear("vat", "report", "--from", "2026-01-01",
                "--to", "2026-12-31", "--output", pdf.toString());
        require(report.stdout().contains("\"number\":5")
                        && report.stdout().contains("\"amount\":\"321.00\""),
                "VAT report has an unexpected taxable-sales box: " + report.stdout());
        require(report.stdout().contains("\"number\":10")
                        && report.stdout().contains("\"amount\":\"80.00\""),
                "VAT report has an unexpected output-VAT box: " + report.stdout());
        require(report.stdout().contains("\"vatToPayOrRefund\":\"80.00\""),
                "VAT report has an unexpected VAT result: " + report.stdout());
        verifyPdf(pdf, List.of("Momsrapport", "321", "80"));
        renderPages(pdf, scenario);
    }

    private static void generatePayablesScenarios() throws Exception {
        Path supplier = json("supplier.json", """
                {
                  "number":"L100","name":"Exempelleverantör ÅÄÖ AB",
                  "registrationNumber":"556677-8899","email":"ekonomi@leverantor.invalid",
                  "bankgiro":"5555-0200",
                  "address":{"address1":"Leverantörsgatan 8","postalCode":"111 22",
                             "city":"Göteborg","country":"Sverige"}
                }
                """);
        cliInYear("supplier", "create", "--file", supplier.toString()).success();
        Path invoice = json("supplier-invoice.json", """
                {
                  "supplierNumber":"L100","date":"2026-06-01","dueDate":"2026-07-01",
                  "reference":"LEV-2026-001","vat":"200.00","rounding":"0.00",
                  "rows":[{"description":"Kontorsmaterial för galleri","quantity":1,
                           "unitPrice":"800.00","account":6110}]
                }
                """);
        Result created = cliInYear("supplier-invoice", "create", "--file", invoice.toString());
        int invoiceNumber = firstInt(created.stdout(), "number");
        require(created.stdout().contains("\"total\":\"1000.00\""),
                "Supplier invoice has an unexpected total: " + created.stdout());

        Path journalScenario = output.resolve("supplier-invoice-journal");
        Files.createDirectories(journalScenario);
        Path journalPdf = journalScenario.resolve("supplier-invoice-journal.pdf");
        Result journal = cliInYear("supplier-invoice", "journal", "--from", "2026-06-01",
                "--to", "2026-06-01", "--output", journalPdf.toString(), "--commit");
        require(journal.stdout().contains("\"debitTotal\":\"1000.00\"")
                        && journal.stdout().contains("\"creditTotal\":\"1000.00\""),
                "Supplier invoice journal does not balance: " + journal.stdout());
        verifyPdf(journalPdf, List.of("Leverantörsfakturajournal", "L100", "2440", "2641", "6110"));
        verifyPdfLacks(journalPdf, List.of("null"));
        require(extractPdfText(journalPdf).indexOf("6110")
                        < extractPdfText(journalPdf).indexOf("Summa redovisningsvaluta"),
                "Supplier invoice journal total appears before the final account row");
        renderPages(journalPdf, journalScenario);

        generatePayablesReport("accounts-payable-before-settlement", "accounts-payable",
                "2026-06-02", List.of("L100", "Exempelleverantör ÅÄÖ AB", "1,000.00"));
        generatePayablesReport("supplier-debts-before-settlement", "supplier-debts",
                "2026-06-02", List.of("L100", "Exempelleverantör ÅÄÖ AB", "1,000.00"));

        Path credit = json("supplier-credit-invoice.json", """
                {"supplierInvoiceNumber":%d,"date":"2026-06-10","amount":"250.00"}
                """.formatted(invoiceNumber));
        Result creditCreated = cliInYear("supplier-credit-invoice", "create", "--file", credit.toString());
        require(creditCreated.stdout().contains("\"total\":\"250.00\""),
                "Supplier credit invoice has an unexpected total: " + creditCreated.stdout());
        Path creditScenario = output.resolve("supplier-credit-invoice-journal");
        Files.createDirectories(creditScenario);
        Path creditPdf = creditScenario.resolve("supplier-credit-invoice-journal.pdf");
        Result creditJournal = cliInYear("supplier-credit-invoice", "journal", "--from", "2026-06-10",
                "--to", "2026-06-10", "--output", creditPdf.toString(), "--commit");
        require(creditJournal.stdout().contains("\"debitTotal\":\"250.00\"")
                        && creditJournal.stdout().contains("\"creditTotal\":\"250.00\""),
                "Supplier credit journal does not balance: " + creditJournal.stdout());
        verifyPdf(creditPdf, List.of("kreditfakturajournal", "L100", "2440", "2641", "6110"));
        verifyPdfLacks(creditPdf, List.of("null"));
        require(extractPdfText(creditPdf).indexOf("6110")
                        < extractPdfText(creditPdf).indexOf("Summa redovisningsvaluta"),
                "Supplier credit journal total appears before the final account row");
        renderPages(creditPdf, creditScenario);

        Path payment = json("outpayment.json", """
                {"date":"2026-07-01","text":"Slutbetalning av leverantörsfaktura",
                 "rows":[{"invoiceNumber":%d,"amount":"750.00","currencyRate":"1.00"}]}
                """.formatted(invoiceNumber));
        Result paymentCreated = cliInYear("outpayment", "create", "--file", payment.toString());
        require(paymentCreated.stdout().contains("\"total\":\"750.00\""),
                "Outpayment has an unexpected total: " + paymentCreated.stdout());
        Path listScenario = output.resolve("outpayment-list");
        Files.createDirectories(listScenario);
        Path listPdf = listScenario.resolve("outpayment-list.pdf");
        cliInYear("outpayment", "pdf-list", "--output", listPdf.toString()).success();
        verifyPdf(listPdf, List.of("Utbetalningslista", "Slutbetalning av leverantörsfaktura", "750.00"));
        renderPages(listPdf, listScenario);

        Path paymentJournalScenario = output.resolve("outpayment-journal");
        Files.createDirectories(paymentJournalScenario);
        Path paymentJournalPdf = paymentJournalScenario.resolve("outpayment-journal.pdf");
        Result paymentJournal = cliInYear("outpayment", "journal", "--from", "2026-07-01",
                "--to", "2026-07-01", "--output", paymentJournalPdf.toString(), "--commit");
        require(paymentJournal.stdout().contains("\"debitTotal\":\"750.00\"")
                        && paymentJournal.stdout().contains("\"creditTotal\":\"750.00\""),
                "Outpayment journal does not balance: " + paymentJournal.stdout());
        verifyPdf(paymentJournalPdf, List.of("Utbetalningsjournal", "1930", "2440", "750.00"));
        require(extractPdfText(paymentJournalPdf).indexOf("1930")
                        < extractPdfText(paymentJournalPdf).indexOf("Summa redovisningsvaluta"),
                "Outpayment journal total appears before the final account row");
        renderPages(paymentJournalPdf, paymentJournalScenario);

        generatePayablesReport("accounts-payable-after-settlement", "accounts-payable",
                "2026-07-02", List.of("0.00"));
        generatePayablesReport("supplier-debts-after-settlement", "supplier-debts",
                "2026-07-02", List.of("0.00"));
    }

    private static void generatePayablesReport(String scenarioName, String command,
            String date, List<String> expected) throws Exception {
        Path scenario = output.resolve(scenarioName);
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve(scenarioName + ".pdf");
        cliInYear(command, "--date", date, "--output", pdf.toString()).success();
        verifyPdf(pdf, expected);
        renderPages(pdf, scenario);
    }

    private static void generateDocumentRegisterScenarios() throws Exception {
        generateDocumentRegister("invoice-list", new String[]{"invoice", "list"},
                List.of("Fakturalista", "K100", "Exempelkund ÅÄÖ AB", "402.00"));
        generateDocumentRegister("credit-invoice-list", new String[]{"credit-invoice", "list"},
                List.of("Kreditfakturalista", "K100", "Exempelkund ÅÄÖ AB", "100.00"));
        generateDocumentRegister("supplier-list", new String[]{"supplier", "list"},
                List.of("L100", "Exempelleverantör ÅÄÖ AB"));
        generateDocumentRegister("supplier-invoice-list", new String[]{"supplier-invoice", "list"},
                List.of("Leverantörsfakturalista", "L100", "Exempelleverantör ÅÄÖ AB", "1,000.00"));
        generateDocumentRegister("supplier-credit-invoice-list",
                new String[]{"supplier-credit-invoice", "list"},
                List.of("Leverantörskreditfakturalista", "L100", "250.00"));
    }

    private static void generateDocumentRegister(String scenarioName, String[] command,
            List<String> expected) throws Exception {
        Path scenario = output.resolve(scenarioName);
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve(scenarioName + ".pdf");
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>(List.of(command));
        arguments.add("--output"); arguments.add(pdf.toString());
        cliInYear(arguments.toArray(String[]::new)).success();
        verifyPdf(pdf, expected);
        int pages = renderPages(pdf, scenario);
        if (scenarioName.equals("invoice-list")) {
            require(pages >= 3, "Invoice list does not exercise multipage layout: " + pages);
        } else {
            require(pages == 1, scenarioName + " unexpectedly rendered " + pages + " pages");
        }
    }

    private static void generateBalanceSheetScenario() throws Exception {
        Path scenario = output.resolve("balance-sheet");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("balance-sheet.pdf");
        Result report = cliInYear("balance-sheet", "--date", "2026-12-31",
                "--output", pdf.toString());
        require(report.stdout().contains("\"date\":\"2026-12-31\""),
                "Balance sheet has an unexpected date");
        require(report.stdout().contains("\"difference\":\"0.00\""),
                "Balance sheet does not balance: " + report.stdout());
        require(report.stdout().contains("\"currentResult\":\"321.68\""),
                "Balance sheet has an unexpected current result: " + report.stdout());
        require(!report.stdout().contains("\"rows\":[]"),
                "Balance sheet contains no rows");
        verifyPdf(pdf, List.of("Balansrapport", "1510", "402.00"));
        renderPages(pdf, scenario);
    }

    private static void verifyMultipageInvoiceText(Path pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf.toString());
        try {
            require(reader.getNumberOfPages() >= 2, "Multipage invoice PDF has fewer than two pages");
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            String firstPage = extractor.getTextFromPage(1);
            String lastPage = extractor.getTextFromPage(reader.getNumberOfPages());
            require(!firstPage.contains("ATT BETALA"),
                    "Multipage invoice shows totals on its first page");
            require(lastPage.contains("ATT BETALA"),
                    "Multipage invoice lacks totals on its last page");
        } finally {
            reader.close();
        }
    }

    private static void verifyPdfLacks(Path pdf, List<String> forbiddenText) throws Exception {
        String text = extractPdfText(pdf);
        for (String forbidden : forbiddenText) {
            require(!text.contains(forbidden), "PDF contains forbidden text: " + forbidden);
        }
    }

    private static void verifyPdf(Path pdf, List<String> expectedText) throws Exception {
        require(Files.size(pdf) > 1_000, "PDF is unexpectedly small: " + pdf);
        byte[] signature = Files.readAllBytes(pdf);
        require(signature.length >= 5 && signature[0] == '%' && signature[1] == 'P'
                        && signature[2] == 'D' && signature[3] == 'F' && signature[4] == '-',
                "Output does not have a PDF signature: " + pdf);

        String text = extractPdfText(pdf);
        for (String expected : expectedText) {
            require(text.contains(expected), "PDF lacks expected text: " + expected);
        }
        Files.writeString(pdf.getParent().resolve("text.txt"), text, StandardCharsets.UTF_8);
    }

    private static String extractPdfText(Path pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf.toString());
        StringBuilder text = new StringBuilder();
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }

    private static int renderPages(Path pdf, Path scenario) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int pages = document.getNumberOfPages();
            require(pages > 0, "PDF has no pages: " + pdf);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 144, ImageType.RGB);
                require(countNonWhitePixels(image) > 1_000,
                        "Rendered page " + (page + 1) + " is blank: " + pdf);
                ImageIO.write(image, "png", scenario.resolve("page-" + (page + 1) + ".png").toFile());
            }
            return pages;
        }
    }

    private static int countNonWhitePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) count++;
            }
        }
        return count;
    }

    private static void writeIndex(int invoiceNumber, int longInvoiceNumber,
            int amountsInvoiceNumber, int voucherNumber) throws IOException {
        String multipageImages;
        try (Stream<Path> paths = Files.list(output.resolve("invoice-multipage"))) {
            multipageImages = paths.filter(path -> path.getFileName().toString().matches("page-[0-9]+\\.png"))
                    .sorted().map(path -> "<img src=\"invoice-multipage/" + path.getFileName()
                            + "\" alt=\"Flersidig faktura, "
                            + path.getFileName().toString().replace("page-", "sida ").replace(".png", "")
                            + "\">").collect(java.util.stream.Collectors.joining("\n"));
        }
        Files.writeString(output.resolve("index.html"), """
                <!doctype html>
                <html lang="sv"><meta charset="utf-8"><title>Bokfri PDF gallery</title>
                <style>body{font:16px system-ui;margin:2rem;background:#f5f5f5;color:#222}
                main{max-width:900px;margin:auto}article{background:white;padding:2rem;margin:2rem 0;box-shadow:0 2px 12px #bbb}
                img{max-width:100%%;border:1px solid #ccc}</style>
                <main><h1>Bokfri PDF gallery</h1>
                <article><h2>Faktura %d</h2><p><a href="invoice/invoice.pdf">Öppna PDF</a></p>
                <img src="invoice/page-1.png" alt="Faktura %d, sida 1"></article>
                <article><h2>OCR-faktura</h2>
                <p><a href="invoice-ocr/invoice-ocr.pdf">Öppna PDF</a></p>
                <img src="invoice-ocr/page-1.png" alt="OCR-faktura, sida 1"></article>
                <article><h2>Följesedel med priser</h2>
                <p><a href="delivery-note/delivery-note.pdf">Öppna PDF</a></p>
                <img src="delivery-note/page-1.png" alt="Följesedel med priser, sida 1"></article>
                <article><h2>Följesedel utan priser</h2>
                <p><a href="delivery-note-without-prices/delivery-note-without-prices.pdf">Öppna PDF</a></p>
                <img src="delivery-note-without-prices/page-1.png" alt="Följesedel utan priser, sida 1"></article>
                <article><h2>Plocklista</h2>
                <p><a href="picking-list/picking-list.pdf">Öppna PDF</a></p>
                <img src="picking-list/page-1.png" alt="Plocklista, sida 1"></article>
                <article><h2>Försäljningsrapport</h2>
                <p><a href="sales-report/sales-report.pdf">Öppna PDF</a></p>
                <img src="sales-report/page-1.png" alt="Försäljningsrapport, sida 1"></article>
                <article><h2>Kreditfaktura</h2>
                <p><a href="credit-invoice/credit-invoice.pdf">Öppna PDF</a></p>
                <img src="credit-invoice/page-1.png" alt="Kreditfaktura, sida 1"></article>
                <article><h2>Flersidig faktura %d</h2>
                <p><a href="invoice-multipage/invoice-multipage.pdf">Öppna PDF</a></p>
                %s</article>
                <article><h2>Beloppsfaktura %d</h2>
                <p><a href="invoice-amounts/invoice-amounts.pdf">Öppna PDF</a></p>
                <img src="invoice-amounts/page-1.png" alt="Beloppsfaktura, sida 1"></article>
                <article><h2>Kundlista</h2><p><a href="customer-list/customer-list.pdf">Öppna PDF</a></p>
                <img src="customer-list/page-1.png" alt="Kundlista, sida 1"></article>
                <article><h2>Fakturajournal</h2>
                <p><a href="invoice-journal/invoice-journal.pdf">Öppna PDF</a></p>
                <img src="invoice-journal/page-1.png" alt="Fakturajournal, sida 1"></article>
                <article><h2>Kundreskontra före betalning</h2>
                <p><a href="accounts-receivable-before-payment/accounts-receivable-before-payment.pdf">Öppna PDF</a></p>
                <img src="accounts-receivable-before-payment/page-1.png" alt="Kundreskontra före betalning"></article>
                <article><h2>Kundfordran före betalning</h2>
                <p><a href="customer-claims-before-payment/customer-claims-before-payment.pdf">Öppna PDF</a></p>
                <img src="customer-claims-before-payment/page-1.png" alt="Kundfordran före betalning"></article>
                <article><h2>Inbetalningslista</h2>
                <p><a href="inpayment-list/inpayment-list.pdf">Öppna PDF</a></p>
                <img src="inpayment-list/page-1.png" alt="Inbetalningslista"></article>
                <article><h2>Inbetalningsjournal</h2>
                <p><a href="inpayment-journal/inpayment-journal.pdf">Öppna PDF</a></p>
                <img src="inpayment-journal/page-1.png" alt="Inbetalningsjournal"></article>
                <article><h2>Kundreskontra efter betalning</h2>
                <p><a href="accounts-receivable-after-payment/accounts-receivable-after-payment.pdf">Öppna PDF</a></p>
                <img src="accounts-receivable-after-payment/page-1.png" alt="Kundreskontra efter betalning"></article>
                <article><h2>Kundfordran efter betalning</h2>
                <p><a href="customer-claims-after-payment/customer-claims-after-payment.pdf">Öppna PDF</a></p>
                <img src="customer-claims-after-payment/page-1.png" alt="Kundfordran efter betalning"></article>
                <article><h2>Verifikation %d</h2>
                <p><a href="voucher/voucher.pdf">Öppna PDF</a></p>
                <img src="voucher/page-1.png" alt="Verifikation %d, sida 1"></article>
                <article><h2>Verifikationslista</h2>
                <p><a href="voucher-list/voucher-list.pdf">Öppna PDF</a></p>
                <img src="voucher-list/page-1.png" alt="Verifikationslista, sida 1"></article>
                <article><h2>Huvudbok – konto 3001</h2>
                <p><a href="general-ledger-3001/general-ledger-3001.pdf">Öppna PDF</a></p>
                <img src="general-ledger-3001/page-1.png" alt="Huvudbok konto 3001, sida 1"></article>
                <article><h2>Resultatrapport</h2>
                <p><a href="income-statement/income-statement.pdf">Öppna PDF</a></p>
                <img src="income-statement/page-1.png" alt="Resultatrapport, sida 1"></article>
                <article><h2>Balansrapport</h2>
                <p><a href="balance-sheet/balance-sheet.pdf">Öppna PDF</a></p>
                <img src="balance-sheet/page-1.png" alt="Balansrapport, sida 1"></article>
                <article><h2>Momsrapport</h2>
                <p><a href="vat-report/vat-report.pdf">Öppna PDF</a></p>
                <img src="vat-report/page-1.png" alt="Momsrapport, sida 1"></article>
                <article><h2>Leverantörsfakturajournal</h2>
                <p><a href="supplier-invoice-journal/supplier-invoice-journal.pdf">Öppna PDF</a></p>
                <img src="supplier-invoice-journal/page-1.png" alt="Leverantörsfakturajournal"></article>
                <article><h2>Leverantörsreskontra före reglering</h2>
                <p><a href="accounts-payable-before-settlement/accounts-payable-before-settlement.pdf">Öppna PDF</a></p>
                <img src="accounts-payable-before-settlement/page-1.png" alt="Leverantörsreskontra före reglering"></article>
                <article><h2>Leverantörsskuld före reglering</h2>
                <p><a href="supplier-debts-before-settlement/supplier-debts-before-settlement.pdf">Öppna PDF</a></p>
                <img src="supplier-debts-before-settlement/page-1.png" alt="Leverantörsskuld före reglering"></article>
                <article><h2>Leverantörskreditfakturajournal</h2>
                <p><a href="supplier-credit-invoice-journal/supplier-credit-invoice-journal.pdf">Öppna PDF</a></p>
                <img src="supplier-credit-invoice-journal/page-1.png" alt="Leverantörskreditfakturajournal"></article>
                <article><h2>Utbetalningslista</h2>
                <p><a href="outpayment-list/outpayment-list.pdf">Öppna PDF</a></p>
                <img src="outpayment-list/page-1.png" alt="Utbetalningslista"></article>
                <article><h2>Utbetalningsjournal</h2>
                <p><a href="outpayment-journal/outpayment-journal.pdf">Öppna PDF</a></p>
                <img src="outpayment-journal/page-1.png" alt="Utbetalningsjournal"></article>
                <article><h2>Leverantörsreskontra efter reglering</h2>
                <p><a href="accounts-payable-after-settlement/accounts-payable-after-settlement.pdf">Öppna PDF</a></p>
                <img src="accounts-payable-after-settlement/page-1.png" alt="Leverantörsreskontra efter reglering"></article>
                <article><h2>Leverantörsskuld efter reglering</h2>
                <p><a href="supplier-debts-after-settlement/supplier-debts-after-settlement.pdf">Öppna PDF</a></p>
                <img src="supplier-debts-after-settlement/page-1.png" alt="Leverantörsskuld efter reglering"></article>
                <article><h2>Fakturalista</h2><p><a href="invoice-list/invoice-list.pdf">Öppna PDF</a></p>
                <img src="invoice-list/page-1.png" alt="Fakturalista, sida 1">
                <img src="invoice-list/page-2.png" alt="Fakturalista, sida 2">
                <img src="invoice-list/page-3.png" alt="Fakturalista, sida 3"></article>
                <article><h2>Kreditfakturalista</h2><p><a href="credit-invoice-list/credit-invoice-list.pdf">Öppna PDF</a></p>
                <img src="credit-invoice-list/page-1.png" alt="Kreditfakturalista"></article>
                <article><h2>Leverantörslista</h2><p><a href="supplier-list/supplier-list.pdf">Öppna PDF</a></p>
                <img src="supplier-list/page-1.png" alt="Leverantörslista"></article>
                <article><h2>Leverantörsfakturalista</h2><p><a href="supplier-invoice-list/supplier-invoice-list.pdf">Öppna PDF</a></p>
                <img src="supplier-invoice-list/page-1.png" alt="Leverantörsfakturalista"></article>
                <article><h2>Leverantörskreditfakturalista</h2><p><a href="supplier-credit-invoice-list/supplier-credit-invoice-list.pdf">Öppna PDF</a></p>
                <img src="supplier-credit-invoice-list/page-1.png" alt="Leverantörskreditfakturalista"></article>
                </main></html>
                """.formatted(invoiceNumber, invoiceNumber, longInvoiceNumber, multipageImages,
                        amountsInvoiceNumber, voucherNumber, voucherNumber),
                StandardCharsets.UTF_8);
    }

    private static Path json(String name, String content) throws IOException {
        Path file = work.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Result cli(String... arguments) throws Exception {
        return run(List.of(arguments));
    }

    private static Result cliWithCompany(String... arguments) throws Exception {
        return runWithContext(false, arguments);
    }

    private static Result cliInYear(String... arguments) throws Exception {
        return runWithContext(true, arguments);
    }

    private static Result runWithContext(boolean includeYear, String... arguments) throws Exception {
        java.util.ArrayList<String> all = new java.util.ArrayList<>();
        all.add("--company-id"); all.add(Integer.toString(companyId));
        if (includeYear) { all.add("--year-id"); all.add(Integer.toString(yearId)); }
        all.addAll(List.of(arguments));
        return run(all);
    }

    private static Result run(List<String> arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        if (jarLauncher) {
            command.add("java");
            command.add("-Dbokfri.reportDate=2026-03-15");
            command.add("-jar");
        }
        command.add(launcher.toString());
        command.add("--config"); command.add(config.toString());
        command.add("--data-dir"); command.add(data.toString());
        command.add("--format"); command.add("json");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        Result result = new Result(process.waitFor(), stdout, stderr, command);
        result.success();
        return result;
    }

    private static int firstInt(String json, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        require(matcher.find(), "JSON lacks integer " + key + ": " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private static void recreateDirectory(Path directory) throws IOException {
        deleteRecursively(directory);
        Files.createDirectories(directory);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Result(int exitCode, String stdout, String stderr, List<String> command) {
        void success() {
            require(exitCode == 0, "Command failed (" + exitCode + "): " + String.join(" ", command)
                    + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
    }
}
