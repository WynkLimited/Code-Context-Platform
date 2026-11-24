package com.wynk.codecontext.port.contract;

import java.nio.file.Path;

/**
 * A repository checked out at one specific commit, ready for contract extraction.
 *
 * <p>{@code root} is a worktree produced by {@code RepoWorkspace.createWorktree}, so the commit is
 * already verified to exist. Extractors read from it and must never write to it.
 *
 * @param owner  GitHub owner
 * @param repository repository name
 * @param commit full 40-character SHA this worktree is checked out at
 * @param branch branch or ref this commit was reached through, for diagnostics only
 * @param root   filesystem root of the checkout
 */
public record RepositoryContext(
        String owner,
        String repository,
        String commit,
        String branch,
        Path root
) {
    public String fullName() {
        return owner + "/" + repository;
    }

    /** Repository-relative, forward-slash path for {@code absolute}, used as {@code sourceFile}. */
    public String relativize(Path absolute) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = absolute.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            return normalized.toString().replace('\\', '/');
        }
        return normalizedRoot.relativize(normalized).toString().replace('\\', '/');
    }
}
