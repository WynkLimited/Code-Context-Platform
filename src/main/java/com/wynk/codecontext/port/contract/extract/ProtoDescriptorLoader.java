package com.wynk.codecontext.port.contract.extract;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.git.ProcessRunner;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Turns a repository's {@code .proto} files into protoc descriptor sets.
 *
 * <p>Descriptors rather than hand-parsing: protoc is the only component that agrees with the
 * compiler about package resolution, nested type qualification and import semantics, which is
 * exactly what contract identity depends on.
 *
 * <p>Files are compiled <em>one proto source directory at a time</em>. That is load-bearing in a
 * monorepo: User-Growth contains nine files named {@code Campaign.proto}, and a single invocation
 * passing nine {@code --proto_path} roots would resolve every {@code import "Campaign.proto"} to
 * whichever copy came first, silently attributing one module's contract to another.
 *
 * <p>Results are cached per {@code (repository, commit)} so the PROTO and GRPC extractors share
 * one protoc run.
 */

public class ProtoDescriptorLoader {

    private static final Logger LOG = Logger.getLogger(ProtoDescriptorLoader.class);

    /** Directories never scanned for contracts — build output and vendored dependencies. */
    private static final List<String> IGNORED_SEGMENTS =
            List.of("/target/", "/build/", "/node_modules/", "/.git/", "/site-packages/", "/.venv/");

    private static final int MAX_PROTO_FILES = 2000;
    private static final long PROTOC_TIMEOUT_SECONDS = 120;

    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    /**
     * One compiled proto file: its descriptor plus the repository-relative path it came from.
     *
     * <p>{@code descriptor.getName()} is only unique within its own protoc invocation, so the
     * repo-relative path is carried separately and is what ends up in the index.
     */
    public record RepoProtoFile(String sourceFile, FileDescriptorProto descriptor) {}

    /**
     * @param files         proto files physically present in the repository, in path order
     * @param dependencies  descriptors pulled in via {@code --include_imports}, keyed by protoc
     *                      name; these are <em>not</em> repository contracts
     * @param toolAvailable false when protoc is missing, which degrades extraction to empty
     * @param failures      human-readable compile failures, for diagnostics
     */
    public record Result(
            List<RepoProtoFile> files,
            Map<String, FileDescriptorProto> dependencies,
            boolean toolAvailable,
            List<String> failures
    ) {
        public static Result unavailable(String reason) {
            return new Result(List.of(), Map.of(), false, List.of(reason));
        }

        public boolean isEmpty() {
            return files.isEmpty();
        }
    }

    public boolean protocAvailable() {
        return ProcessRunner.resolveExecutable("protoc").isPresent();
    }

    /** Absolute {@code protoc} path when available; empty when the optional compiler is missing. */
    Optional<Path> resolveProtoc() {
        return ProcessRunner.resolveExecutable("protoc");
    }

    /** Clears cached descriptor sets. Used by tests and on configuration reload. */
    public void clearCache() {
        cache.clear();
    }

    public Result load(RepositoryContext repository) {
        String key = repository.fullName().toLowerCase(java.util.Locale.ROOT) + "@" + repository.commit();
        return cache.computeIfAbsent(key, ignored -> loadUncached(repository));
    }

    private Result loadUncached(RepositoryContext repository) {
        Optional<Path> protoc = resolveProtoc();
        if (protoc.isEmpty()) {
            LOG.warn("contract.proto.protoc_missing — install protoc to enable proto/gRPC contract extraction");
            return Result.unavailable("protoc not installed");
        }
        List<Path> protoFiles = findProtoFiles(repository.root());
        if (protoFiles.isEmpty()) {
            return new Result(List.of(), Map.of(), true, List.of());
        }

        // Group by containing directory so each invocation has one unambiguous primary root.
        var byDirectory = new LinkedHashMap<Path, List<Path>>();
        for (Path file : protoFiles) {
            byDirectory.computeIfAbsent(file.getParent(), ignored -> new ArrayList<>()).add(file);
        }

        var files = new ArrayList<RepoProtoFile>();
        var dependencies = new LinkedHashMap<String, FileDescriptorProto>();
        var failures = new ArrayList<String>();

        for (var entry : byDirectory.entrySet()) {
            compileDirectory(repository, protoc.get(), entry.getKey(), entry.getValue(), byDirectory.keySet(),
                    files, dependencies, failures);
        }

        LOG.infof("contract.proto.compiled repo=%s commit=%s files=%d dirs=%d failures=%d",
                repository.fullName(), shortSha(repository.commit()),
                files.size(), byDirectory.size(), failures.size());
        return new Result(List.copyOf(files), Map.copyOf(dependencies), true, List.copyOf(failures));
    }

    private void compileDirectory(
            RepositoryContext repository,
            Path protoc,
            Path directory,
            List<Path> directoryFiles,
            java.util.Set<Path> allProtoDirectories,
            List<RepoProtoFile> files,
            Map<String, FileDescriptorProto> dependencies,
            List<String> failures
    ) {
        Path descriptorOut = null;
        try {
            descriptorOut = Files.createTempFile("ugp-contract-", ".desc");
            var command = new ArrayList<String>();
            // Invoke the resolved absolute binary directly — never via a shell.
            command.add(protoc.toString());
            command.add("--include_imports");
            command.add("--descriptor_set_out=" + descriptorOut);
            // Search order matters: the file's own directory wins, so a local
            // import "Campaign.proto" resolves to the copy sitting next to it.
            command.add("--proto_path=" + directory);
            command.add("--proto_path=" + repository.root());
            for (Path other : allProtoDirectories) {
                if (!other.equals(directory)) {
                    command.add("--proto_path=" + other);
                }
            }
            var compiledNames = new ArrayList<String>();
            for (Path file : directoryFiles) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                compiledNames.add(name);
                command.add(name);
            }

            ProcessRunner.RunResult run =
                    ProcessRunner.runResult(command, directory, Math.max(1, Math.min(PROTOC_TIMEOUT_SECONDS, com.wynk.codecontext.port.sdk.ReviewRunControl.remainingMillis() / 1000)), null);
            if (run.exitCode() != 0) {
                String reason = repository.relativize(directory) + ": " + firstLine(run.output());
                LOG.debugf("contract.proto.compile_failed %s", reason);
                failures.add(reason);
                return;
            }

            FileDescriptorSet set;
            try (InputStream in = Files.newInputStream(descriptorOut)) {
                set = FileDescriptorSet.parseFrom(in);
            }

            var compiled = new java.util.HashSet<>(compiledNames);
            for (FileDescriptorProto descriptor : set.getFileList()) {
                if (compiled.contains(descriptor.getName())) {
                    Path absolute = directory.resolve(descriptor.getName());
                    files.add(new RepoProtoFile(repository.relativize(absolute), descriptor));
                } else {
                    dependencies.putIfAbsent(descriptor.getName(), descriptor);
                }
            }
        } catch (IOException e) {
            String reason = repository.relativize(directory) + ": " + e.getMessage();
            LOG.debugf("contract.proto.compile_error %s", reason);
            failures.add(reason);
        } finally {
            if (descriptorOut != null) {
                try {
                    Files.deleteIfExists(descriptorOut);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    private static List<Path> findProtoFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> found = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .filter(ProtoDescriptorLoader::isNotIgnored)
                    .map(p -> p.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_PROTO_FILES + 1L)
                    .toList();
            if (found.size() > MAX_PROTO_FILES) {
                LOG.warnf("contract.proto.too_many_files root=%s cap=%d — extraction truncated",
                        root, MAX_PROTO_FILES);
                return found.subList(0, MAX_PROTO_FILES);
            }
            return found;
        } catch (IOException e) {
            LOG.warnf("contract.proto.scan_failed root=%s: %s", root, e.getMessage());
            return List.of();
        }
    }

    private static boolean isNotIgnored(Path path) {
        String normalized = "/" + path.toString().replace('\\', '/') + "/";
        return IGNORED_SEGMENTS.stream().noneMatch(normalized::contains);
    }

    /** Sorted, de-duplicated dependency names — stable diagnostics for a descriptor set. */
    public static List<String> dependencyNames(Result result) {
        return List.copyOf(new TreeSet<>(result.dependencies().keySet()));
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "protoc failed with no output";
        }
        String line = output.strip().lines().findFirst().orElse(output.strip());
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "-" : sha.substring(0, Math.min(12, sha.length()));
    }
}
