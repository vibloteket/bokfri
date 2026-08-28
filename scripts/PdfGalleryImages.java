import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Approves and compares PDF gallery PNG images without native image tools. */
public final class PdfGalleryImages {
    private PdfGalleryImages() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !(arguments[0].equals("compare") || arguments[0].equals("approve"))) {
            throw new IllegalArgumentException(
                    "Usage: java scripts/PdfGalleryImages.java compare|approve <gallery> <baselines>");
        }
        Path gallery = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path baselines = Path.of(arguments[2]).toAbsolutePath().normalize();
        List<Path> currentImages;
        try (Stream<Path> paths = Files.walk(gallery)) {
            currentImages = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("page-[0-9]+\\.png"))
                    .sorted().toList();
        }
        require(!currentImages.isEmpty(), "The gallery contains no rendered pages: " + gallery);

        if (arguments[0].equals("approve")) {
            approve(gallery, baselines, currentImages);
        } else {
            compare(gallery, baselines, currentImages);
        }
    }

    private static void approve(Path gallery, Path baselines, List<Path> currentImages) throws IOException {
        for (Path current : currentImages) {
            Path baseline = baselines.resolve(gallery.relativize(current));
            Files.createDirectories(baseline.getParent());
            Files.copy(current, baseline, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Approved baseline: " + baseline);
        }
    }

    private static void compare(Path gallery, Path baselines, List<Path> currentImages) throws IOException {
        long totalChanged = 0;
        for (Path currentPath : currentImages) {
            Path relative = gallery.relativize(currentPath);
            Path baselinePath = baselines.resolve(relative);
            if (!Files.isRegularFile(baselinePath)) {
                System.out.println("No baseline exists yet; visual comparison skipped: " + baselinePath);
                continue;
            }
            totalChanged += compareImage(currentPath, baselinePath);
        }
        if (totalChanged > 0) {
            System.out.println("Visual differences are currently informational; inspect the diff images.");
        }
    }

    private static long compareImage(Path currentPath, Path baselinePath) throws IOException {
        BufferedImage current = ImageIO.read(currentPath.toFile());
        BufferedImage baseline = ImageIO.read(baselinePath.toFile());
        require(current.getWidth() == baseline.getWidth() && current.getHeight() == baseline.getHeight(),
                "Image dimensions differ for " + currentPath.getFileName() + ": current="
                        + current.getWidth() + "x" + current.getHeight() + ", baseline="
                        + baseline.getWidth() + "x" + baseline.getHeight());
        BufferedImage diff = new BufferedImage(current.getWidth(), current.getHeight(), BufferedImage.TYPE_INT_RGB);
        long changed = 0;
        for (int y = 0; y < current.getHeight(); y++) {
            for (int x = 0; x < current.getWidth(); x++) {
                int actual = current.getRGB(x, y) & 0x00ffffff;
                int expected = baseline.getRGB(x, y) & 0x00ffffff;
                if (actual == expected) {
                    int gray = (((actual >> 16) & 0xff) + ((actual >> 8) & 0xff) + (actual & 0xff)) / 3;
                    int faded = 220 + gray / 8;
                    diff.setRGB(x, y, new Color(faded, faded, faded).getRGB());
                } else {
                    changed++;
                    diff.setRGB(x, y, Color.RED.getRGB());
                }
            }
        }
        Path resultDirectory = currentPath.getParent();
        String pageName = currentPath.getFileName().toString().replace(".png", "");
        Path diffPath = resultDirectory.resolve(pageName + "-diff.png");
        if (changed > 0) {
            ImageIO.write(diff, "png", diffPath.toFile());
        } else {
            Files.deleteIfExists(diffPath);
        }
        long pixels = (long) current.getWidth() * current.getHeight();
        double percentage = 100.0 * changed / pixels;
        String result = "image=" + currentPath.getFileName() + System.lineSeparator()
                + "changedPixels=" + changed + System.lineSeparator()
                + "totalPixels=" + pixels + System.lineSeparator()
                + "changedPercent=" + String.format(Locale.ROOT, "%.6f", percentage)
                + System.lineSeparator();
        Files.writeString(resultDirectory.resolve(pageName + "-comparison.txt"),
                result, StandardCharsets.UTF_8);
        System.out.print(result);
        return changed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
