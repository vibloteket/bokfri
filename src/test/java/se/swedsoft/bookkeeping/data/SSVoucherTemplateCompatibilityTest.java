package se.swedsoft.bookkeeping.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for voucher-template serialization compatibility.
 */
class SSVoucherTemplateCompatibilityTest {

    private static final String LEGACY_CLASS = "se.swedsoft.bookkeeping.data.SSVoucherTemplate";

    @Test
    void readsLegacyDateBasedTemplate(@TempDir Path tempDir) throws Exception {
        LocalDateTime expectedDate = LocalDateTime.of(2025, 12, 31, 14, 15, 16);
        Date legacyDate = Date.from(expectedDate.atZone(ZoneId.systemDefault()).toInstant());
        Path serializedFile = writeLegacyTemplate(tempDir, legacyDate);

        SSVoucherTemplate template;
        try (ObjectInputStream input = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(serializedFile.toFile())))) {
            template = (SSVoucherTemplate) input.readObject();
        }

        assertThat(template.getDescription()).isEqualTo("Legacy template");
        assertThat(template.getLocalDateTime()).isEqualTo(expectedDate);
        assertThat(template.getRows()).isEmpty();
    }

    private Path writeLegacyTemplate(Path tempDir, Date legacyDate) throws Exception {
        Path compileDir = Files.createDirectory(tempDir.resolve("legacy-classes"));
        Path sourceFile = compileDir.resolve("SSVoucherTemplate.java");
        Files.writeString(sourceFile, legacySource());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler available for compatibility test").isNotNull();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjects(sourceFile.toFile());
            boolean compiled = compiler.getTask(null, fileManager, null,
                    List.of("-d", compileDir.toString()), null, compilationUnits).call();
            assertThat(compiled).isTrue();
        }

        Path serializedFile = tempDir.resolve("legacy-template.bin");
        try (LegacyClassLoader classLoader = new LegacyClassLoader(compileDir);
             ObjectOutputStream output = new ObjectOutputStream(
                     new BufferedOutputStream(new FileOutputStream(serializedFile.toFile())))) {
            Class<?> legacyClass = Class.forName(LEGACY_CLASS, true, classLoader);
            Constructor<?> constructor = legacyClass.getDeclaredConstructor(String.class, Date.class);
            output.writeObject(constructor.newInstance("Legacy template", legacyDate));
        }
        return serializedFile;
    }

    private String legacySource() {
        return "package se.swedsoft.bookkeeping.data;\n"
                + "\n"
                + "import java.io.Serializable;\n"
                + "import java.util.Date;\n"
                + "import java.util.LinkedList;\n"
                + "import java.util.List;\n"
                + "\n"
                + "public class SSVoucherTemplate implements Serializable {\n"
                + "    static final long serialVersionUID = 1L;\n"
                + "    private String iDescription;\n"
                + "    private Date iDate;\n"
                + "    private List<Object> iRows;\n"
                + "\n"
                + "    public SSVoucherTemplate(String description, Date date) {\n"
                + "        iDescription = description;\n"
                + "        iDate = date;\n"
                + "        iRows = new LinkedList<>();\n"
                + "    }\n"
                + "}\n";
    }

    private static final class LegacyClassLoader extends URLClassLoader {

        private LegacyClassLoader(Path compileDir) throws IOException {
            super(new URL[]{compileDir.toUri().toURL()},
                    SSVoucherTemplateCompatibilityTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loadedClass = findLoadedClass(name);

                if (loadedClass == null && LEGACY_CLASS.equals(name)) {
                    try {
                        loadedClass = findClass(name);
                    } catch (ClassNotFoundException ignored) {}
                }

                if (loadedClass == null) {
                    loadedClass = super.loadClass(name, false);
                }

                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
        }
    }
}
