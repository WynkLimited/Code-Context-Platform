package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class EnumContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern ENUM = Pattern.compile(
            "\\bpublic\\s+enum\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\.(\\w+)\\s*;");

    @Override public String id() { return ContractType.ENUM.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.ENUM; }
    @Override public java.util.Set<String> incrementalInputSuffixes() { return java.util.Set.of(".java",".kt"); }
    @Override public boolean available() { return true; }
    @Override public boolean mayDefineContract(String path) {
        return path != null && (path.endsWith(".java") || path.endsWith(".kt"));
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var result = new ArrayList<ContractExport>();
        for (var file : ExtractorFileSupport.read(repository, Set.of(".java", ".kt"), 2L * 1024 * 1024)) {
            var packageMatcher = PACKAGE.matcher(file.content());
            String namespace = packageMatcher.find() ? packageMatcher.group(1) : null;
            var matcher = ENUM.matcher(file.content());
            while (matcher.find()) {
                String signature = matcher.group(2).replaceAll("//.*", "")
                        .replaceAll("\\s+", "").replaceFirst(";.*$", "");
                ContractIdentity identity = ContractIdentity.ofEnum(namespace, matcher.group(1));
                result.add(new ContractExport(identity, type(), file.path(), identity.qualifiedName(),
                        ExtractorFileSupport.sha256(signature),
                        Map.of("kind", "enum", "signature", signature)));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var result = new ArrayList<ContractImport>();
        for (var file : ExtractorFileSupport.read(repository, Set.of(".java", ".kt"), 2L * 1024 * 1024)) {
            var matcher = IMPORT.matcher(file.content());
            while (matcher.find()) {
                String namespace = matcher.group(1);
                String name = matcher.group(2);
                if (!Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\.\\s*\\w+\\b")
                        .matcher(file.content()).find()) {
                    continue;
                }
                ContractIdentity identity = ContractIdentity.ofEnum(namespace, name);
                result.add(new ContractImport(identity, type(), file.path(), identity.qualifiedName(),
                        null, Map.of("kind", "enum-api-usage")));
            }
        }
        return List.copyOf(result);
    }
}
