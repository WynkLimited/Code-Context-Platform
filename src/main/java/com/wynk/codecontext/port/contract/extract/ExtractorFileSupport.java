package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.RepositoryContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class ExtractorFileSupport {

    private static final List<String> IGNORED = List.of(
            "/.git/", "/target/", "/build/", "/out/", "/node_modules/",
            "/.idea/", "/.venv/", "/generated/", "/generated-sources/");

    private ExtractorFileSupport() {}

    record SourceFile(String path, String content) {}

    static List<SourceFile> read(
            RepositoryContext repository,
            Set<String> extensions,
            long maxBytes
    ) {
        if (repository == null || repository.root() == null || !Files.isDirectory(repository.root())) {
            return List.of();
        }
        var result = new ArrayList<SourceFile>();
        try (Stream<Path> paths = Files.walk(repository.root())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> matches(repository.root(), path, extensions))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> readOne(repository, path, maxBytes, result));
        } catch (IOException ignored) {
            return List.copyOf(result);
        }
        return List.copyOf(result);
    }

    private static boolean matches(Path root, Path file, Set<String> extensions) {
        String relative = "/" + root.relativize(file).toString().replace('\\', '/');
        String lower = relative.toLowerCase(Locale.ROOT);
        return IGNORED.stream().noneMatch(lower::contains)
                && extensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).anyMatch(lower::endsWith);
    }

    private static void readOne(
            RepositoryContext repository,
            Path path,
            long maxBytes,
            List<SourceFile> result
    ) {
        try {
            if (Files.size(path) <= maxBytes) {
                result.add(new SourceFile(
                        repository.relativize(path),
                        Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException ignored) {
            // One unreadable source file reduces coverage; it must not fail the review.
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
