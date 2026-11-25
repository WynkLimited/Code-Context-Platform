package com.wynk.codecontext.security;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Filesystem containment for untrusted repository trees.
 *
 * <p>Indexed repositories are untrusted: a checkout may contain symlinks that point outside the
 * configured root. Callers should refuse symlink ancestors and treat symlink leaves as unsafe.
 */
public final class PathContainment {
    private PathContainment() {}

    /** Ensures {@code candidate} stays under {@code root} after normalization (no symlink follow). */
    public static Path requireUnder(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IOException("path escaped configured root");
        }
        return normalized;
    }

    /**
     * Rejects when any path component from {@code root} through {@code path} is a symbolic link.
     * Missing intermediate directories are allowed (creation still pending).
     */
    public static void rejectSymlinkAncestors(Path root, Path path) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IOException("path escaped configured root");
        }
        Path cursor = normalizedRoot;
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
            throw new IOException("symlinked repository path rejected");
        }
        Path relative = normalizedRoot.relativize(normalized);
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IOException("symlinked repository path rejected");
            }
        }
    }

    /** True when {@code path} itself is a symbolic link (does not follow). */
    public static boolean isSymlink(Path path) {
        return path != null && Files.isSymbolicLink(path);
    }

    /**
     * Walks {@code root} without {@link FileVisitOption#FOLLOW_LINKS}, so directory symlinks are
     * not descended into.
     */
    public static Stream<Path> walkContained(Path root) throws IOException {
        return Files.walk(root);
    }
}
