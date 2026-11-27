package com.wynk.codecontext.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hard gate: this platform must not couple to the MultiRepo review agent
 * at compile, test, or runtime. Concepts may be adapted; artifacts must not.
 */
class ArchitecturalIndependenceTest {
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)groupId\\s*>\\s*in\\.wynk\\.ugp"),
            Pattern.compile("(?i)artifactId\\s*>\\s*review-agent"),
            Pattern.compile("(?i)import\\s+in\\.wynk\\.ugp\\.review\\."),
            Pattern.compile("(?i)ugp-review-agent"),
            Pattern.compile("(?i)review-agent-core"),
            Pattern.compile("(?i)review-agent-parent"));

    @Test void pomAndSourceTreeDoNotReferenceMultiRepoArtifacts() throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        assertNoForbidden(root.resolve("pom.xml"));
        try (Stream<Path> paths = Files.walk(root.resolve("src"))) {
            List<Path> offenders = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".yml");
                    })
                    .filter(path -> !path.getFileName().toString().equals("ArchitecturalIndependenceTest.java"))
                    .filter(path -> containsForbidden(path))
                    .toList();
            assertTrue(offenders.isEmpty(), "forbidden MultiRepo coupling in: " + offenders);
        }
    }

    private static void assertNoForbidden(Path file) throws Exception {
        assertFalse(containsForbidden(file), file + " contains forbidden MultiRepo coupling");
    }

    private static boolean containsForbidden(Path path) {
        try {
            String text = Files.readString(path);
            for (Pattern pattern : FORBIDDEN) {
                if (pattern.matcher(text).find()) return true;
            }
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }
}
