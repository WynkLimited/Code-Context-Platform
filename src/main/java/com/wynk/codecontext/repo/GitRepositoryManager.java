package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.security.OutboundUrlGuard;
import com.wynk.codecontext.security.PathContainment;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.HttpConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GitRepositoryManager {
    private static final Set<String> IGNORED = Set.of(".git", "target", "build", "node_modules", "vendor", "dist", "out", ".gradle");
    private final CodeContextProperties properties;
    private final ConcurrentHashMap<Path,Object> locks = new ConcurrentHashMap<>();
    private final OutboundUrlGuard.NameResolver nameResolver;
    @Autowired
    public GitRepositoryManager(CodeContextProperties properties) { this(properties, InetAddressResolver.INSTANCE); }
    /** Test seam for injecting a fake DNS resolver; not a Spring injection point. */
    GitRepositoryManager(CodeContextProperties properties, OutboundUrlGuard.NameResolver nameResolver) {
        this.properties = properties;
        this.nameResolver = nameResolver;
    }

    public Path sync(RepositoryRecord repo) throws Exception {
        Path root = safeRoot().resolve(safeComponent(repo.owner())).resolve(safeComponent(repo.name())).normalize();
        if (!root.startsWith(safeRoot())) throw new IOException("repository path escaped configured root");
        PathContainment.rejectSymlinkAncestors(safeRoot(), root);
        validateUrl(repo.cloneUrl());
        synchronized (locks.computeIfAbsent(root, k -> new Object())) {
            if (!Files.exists(root.resolve(".git"))) {
                validateUrl(repo.cloneUrl()); Files.createDirectories(root.getParent());
                PathContainment.rejectSymlinkAncestors(safeRoot(), root);
                // Init + fetch (instead of cloneRepository) so http.followRedirects=false is set
                // before the first HTTPS request and redirect-based SSRF cannot land on private IPs.
                try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch(repo.defaultBranch()).call()) {
                    disableHttpRedirects(git);
                    var config = git.getRepository().getConfig();
                    config.setString("remote", "origin", "url", repo.cloneUrl());
                    config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
                    config.save();
                    var fetch = git.fetch().setRemote("origin").setTimeout(properties.gitTimeoutSeconds());
                    if (!properties.gitAccessToken().isBlank()) {
                        fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", properties.gitAccessToken()));
                    }
                    fetch.call();
                    String remoteBranch = "origin/" + repo.defaultBranch();
                    if (git.getRepository().resolve(remoteBranch) == null) {
                        throw new IOException("remote branch missing after fetch: " + repo.defaultBranch());
                    }
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(remoteBranch).call();
                }
            }
            try (Git git = Git.open(root.toFile())) {
                PathContainment.rejectSymlinkAncestors(safeRoot(), root);
                disableHttpRedirects(git);
                String[] origins=git.getRepository().getConfig().getStringList("remote","origin","url");
                if(origins.length!=1||!repo.cloneUrl().equals(origins[0]))
                    throw new IOException("mirror origin does not match registered repository");
                validateUrl(origins[0]);
                var fetch=git.fetch().setRemote("origin").setTimeout(properties.gitTimeoutSeconds());if(!properties.gitAccessToken().isBlank())fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token",properties.gitAccessToken()));fetch.call();
                git.checkout().setName(repo.defaultBranch()).call();
                var pull=git.pull().setRemote("origin").setRemoteBranchName(repo.defaultBranch()).setTimeout(properties.gitTimeoutSeconds());if(!properties.gitAccessToken().isBlank())pull.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token",properties.gitAccessToken()));pull.call();
            }
        }
        return root;
    }

    public String head(Path root) throws IOException {
        try (Git git = Git.open(root.toFile())) { return git.getRepository().resolve("HEAD").name(); }
    }

    public List<SourceFile> filesAt(Path root, String revision) throws Exception {
        List<SourceFile> files = new ArrayList<>();
        try (Git git = Git.open(root.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId id = git.getRepository().resolve(revision + "^{tree}");
            if (id == null) throw new IOException("unknown Git tree");
            try (TreeWalk tree = new TreeWalk(git.getRepository())) {
                tree.addTree(walk.parseTree(id)); tree.setRecursive(true);
                while (tree.next()) {
                    String path = tree.getPathString();
                    if (skip(path) || tree.getFileMode(0).getObjectType() != org.eclipse.jgit.lib.Constants.OBJ_BLOB) continue;
                    long size = tree.getObjectReader().open(tree.getObjectId(0)).getSize();
                    if (size <= 0 || size > properties.maxFileBytes()) continue;
                    byte[] bytes = tree.getObjectReader().open(tree.getObjectId(0)).getBytes((int)properties.maxFileBytes());
                    String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (content.indexOf('\0') >= 0) continue;
                    files.add(new SourceFile(path, content, tree.getObjectId(0).name()));
                }
            }
        }
        return List.copyOf(files);
    }
    public String readFileAtRevision(Path root,String revision,String relativePath,long maxBytes) throws Exception {
        Path normalized=Path.of(relativePath).normalize();
        if(normalized.isAbsolute()||normalized.startsWith("..")||relativePath.indexOf('\0')>=0)throw new IllegalArgumentException("unsafe source path");
        String repoPath=normalized.toString().replace('\\','/');
        try(Git git=Git.open(root.toFile());RevWalk walk=new RevWalk(git.getRepository())) {
            ObjectId commit=git.getRepository().resolve(revision+"^{tree}");if(commit==null)throw new IOException("indexed commit is unavailable");
            try(TreeWalk tree=TreeWalk.forPath(git.getRepository(),repoPath,walk.parseTree(commit))) {
                if(tree==null||tree.getFileMode(0).getObjectType()!=org.eclipse.jgit.lib.Constants.OBJ_BLOB||tree.getFileMode(0).getObjectType()==org.eclipse.jgit.lib.Constants.OBJ_COMMIT)throw new IOException("file not found in indexed commit");
                var loader=git.getRepository().open(tree.getObjectId(0));if(loader.getSize()>maxBytes)throw new IOException("file exceeds read limit");
                byte[] content=loader.getBytes((int)maxBytes);if(new String(content,java.nio.charset.StandardCharsets.UTF_8).indexOf('\0')>=0)throw new IOException("binary source file cannot be displayed");
                return new String(content,java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
    private Path safeRoot() throws IOException { Path p=Path.of(properties.repositoryRoot()).toAbsolutePath().normalize(); Files.createDirectories(p); return p.toRealPath(); }
    private static String safeComponent(String s) { if (s==null || !s.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || s.equals("..")) throw new IllegalArgumentException("invalid repository identity"); return s; }
    private static boolean skip(String path) { for(String part:path.split("/")) if(IGNORED.contains(part)) return true; String l=path.toLowerCase(Locale.ROOT); return l.endsWith(".jar")||l.endsWith(".class")||l.endsWith(".lock")||l.endsWith(".min.js")||l.endsWith(".png")||l.endsWith(".jpg")||l.endsWith(".pdf"); }
    private void validateUrl(String value) throws IOException {
        try {
            var uri=java.net.URI.create(value);
            if("file".equalsIgnoreCase(uri.getScheme())) {
                Path allowed=Path.of(properties.localGitRoot()).toAbsolutePath().normalize().toRealPath();
                Path local=Path.of(uri).toRealPath(); if(!local.startsWith(allowed)||!Files.isDirectory(local.resolve(".git")))throw new IllegalArgumentException("local repository must be inside LOCAL_GIT_ROOT"); return;
            }
            var allowedHosts=Arrays.stream(properties.gitAllowedHosts().split(",")).map(String::strip).filter(h->!h.isBlank()).collect(Collectors.toUnmodifiableSet());
            OutboundUrlGuard.requireAllowlistedHttps(value, allowedHosts, nameResolver);
            if (!uri.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?")) {
                throw new IllegalArgumentException("clone URL host/scheme/path is not allowed");
            }
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("invalid or disallowed clone URL",ex); }
    }
    private static void disableHttpRedirects(Git git) throws IOException {
        var config = git.getRepository().getConfig();
        config.setEnum(HttpConfig.HTTP, null, HttpConfig.FOLLOW_REDIRECTS_KEY, HttpConfig.HttpRedirectMode.FALSE);
        config.setInt(HttpConfig.HTTP, null, HttpConfig.MAX_REDIRECTS_KEY, 0);
        config.save();
    }
    public record SourceFile(String path, String content, String blobSha) {}

    /** Default DNS resolver; package-visible for adversarial tests. */
    enum InetAddressResolver implements OutboundUrlGuard.NameResolver {
        INSTANCE;
        @Override public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            return java.net.InetAddress.getAllByName(host);
        }
    }
}
