package com.wynk.codecontext.port.git;

import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner {

    private static final Logger LOG = Logger.getLogger(ProcessRunner.class);
    private static final ThreadLocal<Map<String, String>> ENVIRONMENT = new ThreadLocal<>();

    public static EnvironmentScope environment(Map<String, String> explicit) {
        Map<String, String> previous = ENVIRONMENT.get();
        ENVIRONMENT.set(Map.copyOf(explicit));
        return () -> { if (previous == null) ENVIRONMENT.remove(); else ENVIRONMENT.set(previous); };
    }

    @FunctionalInterface
    public interface EnvironmentScope extends AutoCloseable { @Override void close(); }

    private ProcessRunner() {}

    public static String run(
            List<String> command,
            Path cwd,
            long timeoutSeconds,
            Map<String, String> extraEnv
    ) throws IOException {
        return run(command, cwd, timeoutSeconds, extraEnv, null);
    }

    public static String run(
            List<String> command,
            Path cwd,
            long timeoutSeconds,
            Map<String, String> extraEnv,
            String stdin
    ) throws IOException {
        RunResult result = runResult(command, cwd, timeoutSeconds, extraEnv, stdin);
        if (result.exitCode() != 0) {
            throw new IOException((result.output().isBlank() ? "exit " + result.exitCode() : result.output())
                    + " — cmd: " + String.join(" ", command));
        }
        return result.output();
    }

    public static RunResult runResult(
            List<String> command,
            Path cwd,
            long timeoutSeconds,
            Map<String, String> extraEnv
    ) throws IOException {
        return runResult(command, cwd, timeoutSeconds, extraEnv, null);
    }

    public static RunResult runResult(
            List<String> command,
            Path cwd,
            long timeoutSeconds,
            Map<String, String> extraEnv,
            String stdin
    ) throws IOException {
        LOG.debugf("exec: %s (cwd=%s)", String.join(" ", command), cwd);
        ProcessBuilder pb = new ProcessBuilder(command);
        com.wynk.codecontext.port.sdk.ReviewRunControl.check();
        pb.environment().clear();
        for (String key : new String[]{"PATH", "SystemRoot", "WINDIR"}) {
            String value = System.getenv(key); if (value != null) pb.environment().put(key, value);
        }
        Map<String, String> scoped = ENVIRONMENT.get();
        if (scoped != null) {
            pb.environment().clear();
            boolean gitCommand = Path.of(command.get(0)).getFileName().toString().equals("git");
            scoped.forEach((key, value) -> {
                if (gitCommand || !key.startsWith("GIT_")) pb.environment().put(key, value);
            });
        }
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        var io = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // Drain concurrently: waiting first deadlocks when a child fills its stdout pipe.
            var output = io.submit(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
                    if (bytes.length > 8 * 1024 * 1024) {
                        process.destroyForcibly();
                        throw new IOException("Command output exceeds 8 MiB limit");
                    }
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
                }
            });
            var input = io.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    if (stdin != null) stream.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                throw new IOException("Command timed out after " + timeoutSeconds + "s: " + String.join(" ", command));
            }
            com.wynk.codecontext.port.sdk.ReviewRunControl.check();
            String captured = output.get(1, TimeUnit.SECONDS);
            input.get(1, TimeUnit.SECONDS);
            return new RunResult(process.exitValue(), captured);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Command stream failed: " + e.getCause().getMessage(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Command stream did not close after exit", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (RuntimeException e) {
                // Some sandboxed/macOS runtimes deny the native process-tree query. Always kill
                // the direct child below; report that descendants could not be enumerated.
                LOG.warnf("process_tree_cleanup_unavailable: %s", e.getMessage());
            }
            process.destroyForcibly();
            io.shutdownNow();
        }
    }

    public record RunResult(int exitCode, String output) {}

    /**
     * Returns true when {@code name} resolves to an executable on {@code PATH}.
     * Uses a Java-native PATH walk — does not spawn {@code which}, a shell, or any child process.
     */
    public static boolean commandExists(String name) {
        return resolveExecutable(name).isPresent();
    }

    /**
     * Resolves {@code name} to an absolute executable path by inspecting {@code PATH}.
     * <p>
     * Bare command names (no path separators) are looked up in each PATH directory.
     * Absolute or relative pathnames are validated directly. Missing PATH, blank entries,
     * invalid paths and filesystem errors are treated as "not found".
     */
    public static Optional<Path> resolveExecutable(String name) {
        return resolveExecutable(name, System.getenv("PATH"));
    }

    /** Visible for tests — same semantics as {@link #resolveExecutable(String)} with an explicit PATH. */
    public static Optional<Path> resolveExecutable(String name, String pathEnv) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (containsPathSeparator(trimmed)) {
            return executableIfPresent(Path.of(trimmed));
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        String[] extensions = executableExtensions();
        for (String entry : pathEnv.split(File.pathSeparator, -1)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path directory;
            try {
                directory = Path.of(entry);
            } catch (Exception e) {
                continue;
            }
            for (String extension : extensions) {
                Optional<Path> match = executableIfPresent(directory.resolve(trimmed + extension));
                if (match.isPresent()) {
                    return match;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean containsPathSeparator(String name) {
        return name.indexOf('/') >= 0 || name.indexOf('\\') >= 0;
    }

    private static String[] executableExtensions() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return new String[]{""};
        }
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            return new String[]{"", ".exe", ".cmd", ".bat"};
        }
        String[] parts = pathext.split(";", -1);
        String[] extensions = new String[parts.length + 1];
        extensions[0] = "";
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].strip();
            extensions[i + 1] = part.startsWith(".") || part.isEmpty() ? part : "." + part;
        }
        return extensions;
    }

    private static Optional<Path> executableIfPresent(Path candidate) {
        try {
            if (candidate == null) {
                return Optional.empty();
            }
            // Follow symlinks to the final target; require a regular executable file.
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
                return Optional.empty();
            }
            return Optional.of(candidate.toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
