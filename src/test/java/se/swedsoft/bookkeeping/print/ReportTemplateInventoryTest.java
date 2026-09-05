package se.swedsoft.bookkeeping.print;

import net.sf.jasperreports.engine.JasperCompileManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the canonical report resources are complete, compilable, and reachable. */
class ReportTemplateInventoryTest {
    private static final Path REPORT_ROOT = Path.of("src/main/resources/reports/report");
    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Pattern REPORT_REFERENCE = Pattern.compile(
            "set(?:PageHeader|PageFooter|ColumnHeader|ColumnFooter|Detail|Summary|Background|"
                    + "LastPageFooter)\\(\\\"([^\\\"]+\\.jrxml)\\\"\\)");
    private static final Pattern STYLE_REFERENCE = Pattern.compile(
            "<template>\\s*<!\\[CDATA\\[\\\"([^\\\"]+\\.jrtx)\\\"\\]\\]>\\s*</template>");

    @Test
    void everyCanonicalReportCompilesAndIsReferenced() throws Exception {
        Set<String> templates = reportTemplates();
        Set<String> references = javaReportReferences();

        assertThat(references).doesNotContainNull();
        assertThat(references).as("Java references to missing report templates")
                .isSubsetOf(templates);
        assertThat(templates).as("Canonical report templates without a Java entry point")
                .containsExactlyInAnyOrderElementsOf(references);

        for (String template : templates) {
            String resource = "/reports/report/" + template;
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as("Packaged report resource %s", resource).isNotNull();
                try {
                    JasperCompileManager.compileReport(input);
                } catch (Exception exception) {
                    throw new AssertionError("Could not compile " + resource, exception);
                }
            }
        }
    }

    @Test
    void externalStyleTemplatesExist() throws Exception {
        try (Stream<Path> files = Files.walk(REPORT_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jrxml")).toList()) {
                Matcher matcher = STYLE_REFERENCE.matcher(
                        Files.readString(file, StandardCharsets.ISO_8859_1));
                while (matcher.find()) {
                    String resource = "/" + matcher.group(1);
                    try (InputStream input = getClass().getResourceAsStream(resource)) {
                        assertThat(input).as("Style template referenced by %s", file).isNotNull();
                    }
                }
            }
        }
    }

    @Test
    void checkedInDependencyMapContainsEveryCanonicalTemplate() throws Exception {
        String dependencyMap = Files.readString(Path.of("docs/report-templates.md"));

        for (String template : reportTemplates()) {
            assertThat(dependencyMap).as("Dependency map entry for %s", template)
                    .contains("`" + template + "`");
        }
    }

    @Test
    void repositoryHasNoLegacyOrCompiledReportTree() throws Exception {
        assertThat(Files.exists(Path.of("data/report"))).isFalse();

        try (Stream<Path> files = Files.walk(Path.of("."))) {
            List<Path> compiled = files.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(Path.of("./target")))
                    .filter(path -> path.toString().endsWith(".jasperreport"))
                    .toList();
            assertThat(compiled).as("Compiled report artifacts checked into source directories")
                    .isEmpty();
        }
    }

    private Set<String> reportTemplates() throws Exception {
        Set<String> templates = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(REPORT_ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jrxml"))
                    .map(REPORT_ROOT::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .sorted()
                    .forEach(templates::add);
        }
        return templates;
    }

    private Set<String> javaReportReferences() throws Exception {
        Set<String> references = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.ISO_8859_1)
                        .replaceAll("(?m)//.*$", "");
                Matcher matcher = REPORT_REFERENCE.matcher(source);
                while (matcher.find()) {
                    references.add(matcher.group(1));
                }
            }
        }
        return references;
    }
}
