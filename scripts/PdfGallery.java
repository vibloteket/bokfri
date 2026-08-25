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
            int longInvoiceNumber = generateMultipageInvoiceScenario();
            generateCustomerListScenario();
            generateGeneralLedgerScenario(invoiceNumber);
            writeIndex(invoiceNumber, longInvoiceNumber);
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
                  "email": "ekonomi@galleri.invalid"
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
                  "stockProduct": false
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
                "Leveransvägen 27", "lastkaj 4", "Galleriartikel decimal", "2.25", "0.5",
                "Mycket litet belopp", "ORDER-XYZ"));
        renderPages(pdf, scenario);
        return invoiceNumber;
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

    private static void generateCustomerListScenario() throws Exception {
        Path scenario = output.resolve("customer-list");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("customer-list.pdf");
        cliInYear("customer", "list", "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("K100", "Exempelkund ÅÄÖ AB",
                "559999-5678", "+46 8 123 45 67"));
        renderPages(pdf, scenario);
    }

    private static void generateGeneralLedgerScenario(int invoiceNumber) throws Exception {
        Result journal = cliInYear("invoice", "journal", "--from", "2026-03-15",
                "--to", "2026-03-15", "--commit");
        require(journal.stdout().contains("\"invoiceNumbers\":[" + invoiceNumber + "]"),
                "Invoice journal does not contain gallery invoice " + invoiceNumber);
        require(journal.stdout().contains("\"committed\":true"),
                "Invoice journal was not committed");

        Result bookedInvoice = cliInYear("invoice", "show", Integer.toString(invoiceNumber));
        require(bookedInvoice.stdout().contains("\"entered\":true"),
                "Gallery invoice was not marked as entered");

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

    private static void verifyPdf(Path pdf, List<String> expectedText) throws Exception {
        require(Files.size(pdf) > 1_000, "PDF is unexpectedly small: " + pdf);
        byte[] signature = Files.readAllBytes(pdf);
        require(signature.length >= 5 && signature[0] == '%' && signature[1] == 'P'
                        && signature[2] == 'D' && signature[3] == 'F' && signature[4] == '-',
                "Output does not have a PDF signature: " + pdf);

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
        for (String expected : expectedText) {
            require(text.toString().contains(expected), "PDF lacks expected text: " + expected);
        }
        Files.writeString(pdf.getParent().resolve("text.txt"), text, StandardCharsets.UTF_8);
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

    private static void writeIndex(int invoiceNumber, int longInvoiceNumber) throws IOException {
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
                <article><h2>Flersidig faktura %d</h2>
                <p><a href="invoice-multipage/invoice-multipage.pdf">Öppna PDF</a></p>
                %s</article>
                <article><h2>Kundlista</h2><p><a href="customer-list/customer-list.pdf">Öppna PDF</a></p>
                <img src="customer-list/page-1.png" alt="Kundlista, sida 1"></article>
                <article><h2>Huvudbok – konto 3001</h2>
                <p><a href="general-ledger-3001/general-ledger-3001.pdf">Öppna PDF</a></p>
                <img src="general-ledger-3001/page-1.png" alt="Huvudbok konto 3001, sida 1"></article>
                </main></html>
                """.formatted(invoiceNumber, invoiceNumber, longInvoiceNumber, multipageImages),
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
