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

public class ConfigContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final Pattern PROPERTY = Pattern.compile("(?m)^\\s*([A-Za-z][\\w.-]+)\\s*[:=]");
    private static final Pattern CONFIG_PROPERTY = Pattern.compile(
            "@ConfigProperty\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VALUE = Pattern.compile("@Value\\s*\\(\\s*\"\\$\\{([^}:]+)");

    @Override public String id() { return ContractType.CONFIG.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.CONFIG; }
    @Override public boolean available() { return true; }
    @Override public boolean mayDefineContract(String path) {
        if (path == null) return false;
        return path.endsWith(".properties") || path.endsWith(".yaml") || path.endsWith(".yml")
                || path.endsWith(".java") || path.endsWith(".kt");
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractExport>();
        for (var file : ExtractorFileSupport.read(repository, Set.of(".properties", ".yaml", ".yml"), 2L * 1024 * 1024)) {
            var matcher = PROPERTY.matcher(file.content());
            while (matcher.find()) {
                String key = matcher.group(1);
                ContractIdentity identity = ContractIdentity.ofConfig(key);
                result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                        new ContractExport(identity, type(), file.path(), key,
                                ExtractorFileSupport.sha256(key),
                                Map.of("kind", isFeatureFlag(key) ? "feature-flag" : "config-key")));
            }
        }
        return List.copyOf(result.values());
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractImport>();
        for (var file : ExtractorFileSupport.read(repository, Set.of(".java", ".kt"), 2L * 1024 * 1024)) {
            addImports(CONFIG_PROPERTY, file, result, "microprofile-config");
            addImports(VALUE, file, result, "spring-value");
        }
        return List.copyOf(result.values());
    }

    private void addImports(
            Pattern pattern,
            ExtractorFileSupport.SourceFile file,
            Map<String, ContractImport> result,
            String framework
    ) {
        var matcher = pattern.matcher(file.content());
        while (matcher.find()) {
            String key = matcher.group(1);
            ContractIdentity identity = ContractIdentity.ofConfig(key);
            result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                    new ContractImport(identity, type(), file.path(), key, null,
                            Map.of("framework", framework,
                                    "kind", isFeatureFlag(key) ? "feature-flag" : "config-key")));
        }
    }

    private static boolean isFeatureFlag(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("feature") || lower.contains("flag") || lower.endsWith(".enabled");
    }
}
