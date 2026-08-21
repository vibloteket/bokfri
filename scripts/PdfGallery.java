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
            generateInvoiceScenario();
            System.out.println("PDF gallery generated: " + output);
        } finally {
            deleteRecursively(work);
        }
    }

    private static void generateInvoiceScenario() throws Exception {
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
                  "name": "Exempelkund XYZ AB",
                  "email": "faktura@example.invalid",
                  "invoiceAddress": {
                    "address1": "Testgatan 15",
                    "postalCode": "123 45",
                    "city": "Testköping"
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
                  "text": "Deterministisk gallerifaktura",
                  "rows": [{"productNumber":"P100","quantity":"2.25"}]
                }
                """);
        int invoiceNumber = firstInt(
                cliInYear("invoice", "create", "--file", invoiceInput.toString()).stdout(), "number");

        Path scenario = output.resolve("invoice");
        Files.createDirectories(scenario);
        Path pdf = scenario.resolve("invoice.pdf");
        cliInYear("invoice", "pdf", Integer.toString(invoiceNumber),
                "--output", pdf.toString()).success();
        verifyPdf(pdf, List.of("K100", "Galleriartikel decimal", "2.25", "ORDER-XYZ"));
        renderPages(pdf, scenario);
        writeIndex(invoiceNumber, scenario);
    }

    private static void verifyPdf(Path pdf, List<String> expectedText) throws Exception {
        require(Files.size(pdf) > 1_000, "Invoice PDF is unexpectedly small");
        byte[] signature = Files.readAllBytes(pdf);
        require(signature.length >= 5 && signature[0] == '%' && signature[1] == 'P'
                        && signature[2] == 'D' && signature[3] == 'F' && signature[4] == '-',
                "Invoice output does not have a PDF signature");

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

    private static void renderPages(Path pdf, Path scenario) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            require(document.getNumberOfPages() > 0, "Invoice PDF has no pages");
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 144, ImageType.RGB);
                require(countNonWhitePixels(image) > 1_000,
                        "Rendered invoice page " + (page + 1) + " is blank");
                ImageIO.write(image, "png", scenario.resolve("page-" + (page + 1) + ".png").toFile());
            }
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

    private static void writeIndex(int invoiceNumber, Path scenario) throws IOException {
        Files.writeString(output.resolve("index.html"), """
                <!doctype html>
                <html lang="sv"><meta charset="utf-8"><title>Bokfri PDF gallery</title>
                <style>body{font:16px system-ui;margin:2rem;background:#f5f5f5;color:#222}
                article{max-width:900px;margin:auto;background:white;padding:2rem;box-shadow:0 2px 12px #bbb}
                img{max-width:100%%;border:1px solid #ccc}</style>
                <article><h1>Bokfri PDF gallery</h1><h2>Faktura %d</h2>
                <p><a href="invoice/invoice.pdf">Öppna PDF</a></p>
                <img src="invoice/page-1.png" alt="Faktura %d, sida 1"></article></html>
                """.formatted(invoiceNumber, invoiceNumber), StandardCharsets.UTF_8);
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
        if (jarLauncher) { command.add("java"); command.add("-jar"); }
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
