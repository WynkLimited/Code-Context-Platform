package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CacheContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final Pattern WRITER = Pattern.compile(
            "@(?:CacheResult|Cacheable|CachePut)\\s*\\([^)]*(?:cacheName|value)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern READER = Pattern.compile(
            "@(?:CacheInvalidate|CacheEvict)\\s*\\([^)]*(?:cacheName|value)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern REDIS_PREFIX = Pattern.compile(
            "\\b[\\w]*REDIS[\\w]*KEY[\\w]*PREFIX\\w*\\s*=\\s*\"([^\"]+)\"");

    @Override public String id() { return ContractType.CACHE.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.CACHE; }
    @Override public boolean available() { return true; }
    @Override public boolean mayDefineContract(String path) {
        return path != null && (path.endsWith(".java") || path.endsWith(".kt")
                || path.endsWith(".properties") || path.endsWith(".yaml") || path.endsWith(".yml"));
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractExport>();
        for (var file : sources(repository)) {
            addExports(WRITER, file, "framework-cache", result);
            addExports(REDIS_PREFIX, file, "redis-key-prefix", result);
        }
        return List.copyOf(result.values());
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractImport>();
        for (var file : sources(repository)) {
            addImports(WRITER, file, "cache-access", result);
            addImports(READER, file, "cache-invalidation", result);
        }
        return List.copyOf(result.values());
    }

    private List<ExtractorFileSupport.SourceFile> sources(RepositoryContext repository) {
        return ExtractorFileSupport.read(repository,
                Set.of(".java", ".kt", ".properties", ".yaml", ".yml"), 2L * 1024 * 1024);
    }

    private void addExports(
            Pattern pattern,
            ExtractorFileSupport.SourceFile file,
            String kind,
            Map<String, ContractExport> result
    ) {
        var matcher = pattern.matcher(file.content());
        while (matcher.find()) {
            String name = matcher.group(1);
            ContractIdentity identity = ContractIdentity.ofCache(name);
            result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                    new ContractExport(identity, type(), file.path(), name,
                            ExtractorFileSupport.sha256(name), Map.of("kind", kind)));
        }
    }

    private void addImports(
            Pattern pattern,
            ExtractorFileSupport.SourceFile file,
            String kind,
            Map<String, ContractImport> result
    ) {
        var matcher = pattern.matcher(file.content());
        while (matcher.find()) {
            String name = matcher.group(1);
            ContractIdentity identity = ContractIdentity.ofCache(name);
            result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                    new ContractImport(identity, type(), file.path(), name, null, Map.of("kind", kind)));
        }
    }
}
