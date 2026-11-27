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

public class StringFormatContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b[\\w]*(?:URI|URL|DEEPLINK|FORMAT|TEMPLATE)[\\w]*\\s*=\\s*\"([a-zA-Z][a-zA-Z0-9+.-]*://[^\"]+)\"");
    private static final Pattern NAVIGATION = Pattern.compile(
            "(?:app:uri|android:uri)\\s*=\\s*\"([a-zA-Z][a-zA-Z0-9+.-]*://[^\"]+)\"");

    @Override public String id() { return ContractType.STRING_FORMAT.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.STRING_FORMAT; }
    @Override public java.util.Set<String> incrementalInputSuffixes() { return java.util.Set.of(".java",".kt",".swift",".xml",".plist"); }
    @Override public boolean available() { return true; }
    @Override public boolean mayDefineContract(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".swift")
                || lower.endsWith(".xml") || lower.endsWith(".plist")
                || lower.endsWith("androidmanifest.xml");
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractExport>();
        for (var file : sources(repository)) {
            var matcher = DECLARATION.matcher(file.content());
            while (matcher.find()) {
                String format = normalizePrintf(matcher.group(1));
                ContractIdentity identity = ContractIdentity.ofStringFormat(format);
                result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                        new ContractExport(identity, type(), file.path(), format,
                                ExtractorFileSupport.sha256(format),
                                Map.of("kind", "declared-uri-format")));
            }
        }
        return List.copyOf(result.values());
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var result = new LinkedHashMap<String, ContractImport>();
        for (var file : sources(repository)) {
            var matcher = NAVIGATION.matcher(file.content());
            while (matcher.find()) {
                String format = normalizePrintf(matcher.group(1));
                ContractIdentity identity = ContractIdentity.ofDeepLink(format);
                result.putIfAbsent(identity.canonicalKey() + "|" + file.path(),
                        new ContractImport(identity, type(), file.path(), format, null,
                                Map.of("kind", "navigation-deeplink")));
            }
        }
        return List.copyOf(result.values());
    }

    private List<ExtractorFileSupport.SourceFile> sources(RepositoryContext repository) {
        return ExtractorFileSupport.read(repository,
                Set.of(".java", ".kt", ".swift", ".xml", ".plist"), 2L * 1024 * 1024);
    }

    private static String normalizePrintf(String value) {
        return value.strip()
                .replaceAll("%(?:\\d+\\$)?[sd]", "{}")
                .replaceAll("\\{\\}", "{value}");
    }
}
