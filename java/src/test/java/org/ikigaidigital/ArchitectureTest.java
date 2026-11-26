package org.ikigaidigital;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the package dependency rules from CLAUDE.md by inspecting the import lines of the main sources.
 * Deliberately dependency-free (no ArchUnit).
 */
class ArchitectureTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/org/ikigaidigital");

    @Test
    void domainHasNoFrameworkImports() {
        assertThat(importsUnder("domain"))
                .noneMatch(line -> line.contains("org.springframework")
                        || line.contains("jakarta.")
                        || line.contains("com.fasterxml")
                        || line.contains("tools.jackson"));
    }

    @Test
    void domainDependsOnNothingOutsideItself() {
        assertThat(importsUnder("domain"))
                .noneMatch(line -> line.contains("org.ikigaidigital.business")
                        || line.contains("org.ikigaidigital.repository")
                        || line.contains("org.ikigaidigital.rest")
                        || line.contains("org.ikigaidigital.application"));
    }

    @Test
    void repositoryApiDoesNotDependOnItsImplementation() {
        assertThat(importsUnder("repository/api"))
                .noneMatch(line -> line.contains("org.ikigaidigital.repository.impl"));
    }

    @Test
    void businessDoesNotDependOnAdapters() {
        assertThat(importsUnder("business"))
                .noneMatch(line -> line.contains("org.ikigaidigital.repository.impl")
                        || line.contains("org.ikigaidigital.rest"));
    }

    @Test
    void restDoesNotDependOnPersistence() {
        assertThat(importsUnder("rest"))
                .noneMatch(line -> line.contains("org.ikigaidigital.repository"));
    }

    private static List<String> importsUnder(String packageDirectory) {
        Path root = MAIN_SOURCES.resolve(packageDirectory);
        if (!Files.isDirectory(root)) {
            // Fail instead of passing vacuously, e.g. when the test runs from a different working directory.
            throw new IllegalStateException("Source directory not found: " + root.toAbsolutePath()
                    + " (run the tests from the java/ module directory)");
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(ArchitectureTest::importLines)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> importLines(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> file.getFileName() + ": " + line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
