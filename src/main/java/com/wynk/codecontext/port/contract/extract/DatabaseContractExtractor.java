package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DatabaseContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Table\\s*\\(\\s*name|Document\\s*\\(\\s*(?:collection|value))\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CREATE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:TABLE|COLLECTION)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([\\w.-]+)");

    @Override public String id() { return ContractType.DATABASE.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.DATABASE; }
    @Override public java.util.Set<String> incrementalInputSuffixes() { return java.util.Set.of(".java",".kt",".sql",".js",".json"); }
    @Override public boolean available() { return true; }
    @Override public boolean mayDefineContract(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".sql")
                || lower.endsWith(".js") || lower.endsWith(".json");
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractExport>();
        for (var file : sources(repository)) {
            addExports(MAPPING, file, "entity-mapping", result);
            addExports(CREATE, file, "migration", result);
        }
        return List.copyOf(result.values());
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractImport>();
        for (var file : sources(repository)) {
            var matcher = MAPPING.matcher(file.content());
            while (matcher.find()) {
                String relation = matcher.group(1);
                ContractIdentity identity = ContractIdentity.ofDatabase(relation);
                result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                        new ContractImport(identity, type(), file.path(), relation, null,
                                Map.of("kind", "entity-mapping")));
            }
        }
        return List.copyOf(result.values());
    }

    private List<ExtractorFileSupport.SourceFile> sources(RepositoryContext repository) {
        return ExtractorFileSupport.read(repository,
                Set.of(".java", ".kt", ".sql", ".js", ".json"), 4L * 1024 * 1024);
    }

    private void addExports(
            Pattern pattern,
            ExtractorFileSupport.SourceFile file,
            String kind,
            Map<String, ContractExport> result
    ) {
        var matcher = pattern.matcher(file.content());
        while (matcher.find()) {
            String relation = matcher.group(1);
            ContractIdentity identity = ContractIdentity.ofDatabase(relation);
            result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                    new ContractExport(identity, type(), file.path(), relation,
                            ExtractorFileSupport.sha256(matcher.group().replaceAll("\\s+", " ")),
                            Map.of("kind", kind)));
        }
    }
}
