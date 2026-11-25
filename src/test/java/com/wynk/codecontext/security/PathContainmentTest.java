package com.wynk.codecontext.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathContainmentTest {
    @TempDir Path temp;

    @Test @DisabledOnOs(OS.WINDOWS)
    void rejectsSymlinkEscapeUnderConfiguredRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("repos"));
        Path outside = Files.createDirectories(temp.resolve("secret"));
        Files.writeString(outside.resolve("passwd"), "leaked");
        Path owner = Files.createDirectories(root.resolve("team"));
        Path link = owner.resolve("escaped");
        Files.createSymbolicLink(link, outside);

        var failure = assertThrows(Exception.class, () -> PathContainment.rejectSymlinkAncestors(root, link.resolve("repo")));
        assertTrue(failure.getMessage().toLowerCase().contains("symlink"));
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void walkContainedDoesNotDescendIntoDirectorySymlink() throws Exception {
        Path root = Files.createDirectories(temp.resolve("snapshot"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), "<project/>");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        List<Path> poms;
        try (var walk = PathContainment.walkContained(root)) {
            poms = walk.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !PathContainment.isSymlink(p))
                    .toList();
        }
        assertEquals(1, poms.size());
        assertEquals(root.resolve("pom.xml"), poms.get(0));
    }

    @Test void requireUnderRejectsTraversal() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        assertThrows(Exception.class, () -> PathContainment.requireUnder(root, root.resolve("../other")));
    }
}
