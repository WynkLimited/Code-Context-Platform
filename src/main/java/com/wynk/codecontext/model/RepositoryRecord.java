package com.wynk.codecontext.model;

import java.util.UUID;

public record RepositoryRecord(UUID id, String owner, String name, String cloneUrl, String team,
                              String defaultBranch, String repositoryType, String mirrorPath) {
    public String key() { return owner + "/" + name; }
}
