package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractEntryMetadata;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.contract.source.JavaApiMethodParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class MavenContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.4.1";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final Pattern PUBLIC_API = Pattern.compile(
            "\\bpublic\\s+(?:sealed\\s+|final\\s+|abstract\\s+|static\\s+|default\\s+)*"
                    + "(?:class|interface|record|enum|[\\w$.<>?,\\[\\] ]+)\\s+[^\\n{;]+(?:[;{])");

    @Override public String id() { return ContractType.MAVEN.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.MAVEN; }
    @Override public boolean available() { return true; }

    @Override
    public boolean mayDefineContract(String path) {
        return path != null && (path.endsWith("pom.xml")
                || path.matches(".*src/main/(java|kotlin)/.*\\.(java|kt)$"));
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var exports = new ArrayList<ContractExport>();
        for (Pom pom : poms(repository)) {
            if (pom.groupId == null || pom.artifactId == null) continue;
            ApiSurface surface = publicApiSignature(repository, pom.path);
            String api = surface.signature();
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("kind", "maven-artifact");
            base.put("signature", api);
            base.put("javaApiComparison", surface.javaComparisons());
            base.put("javaApiAnnotations", surface.javaAnnotations());
            base.put("javaApiExtractionComplete", surface.javaComplete());
            base.put("packaging", pom.packaging == null ? "jar" : pom.packaging);
            base.put("mavenBuildContract", pom.buildContract);
            String coordinates = pom.groupId + ":" + pom.artifactId;
            String fingerprint = sha256(api + "\nBUILD:" + pom.buildContract);
            Map<String, Object> metadata = ContractEntryMetadata.forMavenExport(
                    base,
                    repository.commit(),
                    pom.path,
                    coordinates,
                    api,
                    fingerprint,
                    VERSION);
            exports.add(new ContractExport(
                    ContractIdentity.ofMaven(pom.groupId, pom.artifactId, pom.version),
                    type(), pom.path, coordinates, fingerprint, metadata));
        }
        return exports.stream().sorted(Comparator.comparing(ContractExport::canonicalKey)).toList();
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        var imports = new ArrayList<ContractImport>();
        for (Pom pom : poms(repository)) {
            for (Dependency dependency : pom.dependencies) {
                if (dependency.groupId == null || dependency.artifactId == null) continue;
                imports.add(new ContractImport(
                        ContractIdentity.ofMaven(dependency.groupId, dependency.artifactId, dependency.version),
                        type(), pom.path, dependency.groupId + ":" + dependency.artifactId,
                        dependency.version,
                        ContractEntryMetadata.forMavenImport(
                                Map.of("scope", dependency.scope == null ? "compile" : dependency.scope),
                                repository.commit(),
                                pom.path,
                                dependency.groupId + ":" + dependency.artifactId,
                                dependency.version == null ? "" : dependency.version,
                                VERSION)));
            }
            if (pom.parent != null && pom.parent.groupId != null && pom.parent.artifactId != null) {
                Dependency dependency = pom.parent;
                imports.add(new ContractImport(ContractIdentity.ofMaven(dependency.groupId, dependency.artifactId, dependency.version),
                        type(), pom.path, dependency.groupId + ":" + dependency.artifactId, dependency.version,
                        ContractEntryMetadata.forMavenImport(Map.of("scope", "parent", "role", "parent"), repository.commit(),
                                pom.path, dependency.groupId + ":" + dependency.artifactId,
                                dependency.version == null ? "" : dependency.version, VERSION)));
            }
        }
        return imports.stream().sorted(Comparator.comparing(ContractImport::canonicalKey)).toList();
    }

    private record ApiSurface(String signature, Map<String, String> javaComparisons,
            Map<String, String> javaAnnotations, boolean javaComplete) {}

    private static ApiSurface publicApiSignature(RepositoryContext repository, String pomPath) {
        Path pom = Path.of(pomPath);
        Path parent = pom.getParent();
        String sourcePrefix = (parent == null ? "" : parent.toString().replace('\\', '/') + "/")
                + "src/main/";
        var signatures = new ArrayList<String>();
        var javaComparisons = new LinkedHashMap<String, String>();
        var javaAnnotations = new LinkedHashMap<String, String>();
        boolean javaComplete = true;
        for (var file : ExtractorFileSupport.read(repository, Set.of(".java", ".kt"), MAX_FILE_BYTES)) {
            if (!file.path().startsWith(sourcePrefix)) continue;
            var parsed = file.path().endsWith(".java")
                    ? JavaApiMethodParser.extract(file.path(), file.content()) : new JavaApiMethodParser.Extraction(List.of(), true);
            javaComplete &= parsed.complete();
            var javaMethods = parsed.methods();
            for (var method : javaMethods) {
                String declaration = method.ownerFqn() + "#" + method.header();
                signatures.add(declaration);
                javaComparisons.put(declaration, method.ownerFqn() + "#" + method.comparisonHeader());
                javaAnnotations.put(method.ownerFqn() + "#" + method.comparisonHeader(), method.annotationSignature());
            }
            var matcher = PUBLIC_API.matcher(file.content());
            while (matcher.find()) {
                String signature = matcher.group().replaceAll("\\s+", " ").strip();
                if (javaMethods.stream().noneMatch(m -> matcher.start() >= m.startOffset()
                        && matcher.start() < m.endOffset())) signatures.add(signature);
            }
        }
        signatures.sort(String::compareTo);
        return new ApiSurface(String.join("\n", signatures), Map.copyOf(javaComparisons), Map.copyOf(javaAnnotations), javaComplete);
    }

    private static List<Pom> poms(RepositoryContext repository) {
        var result = new ArrayList<Pom>();
        for (var source : ExtractorFileSupport.read(repository, Set.of("pom.xml"), MAX_FILE_BYTES)) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setExpandEntityReferences(false);
                factory.setXIncludeAware(false);
                Document document = factory.newDocumentBuilder().parse(
                        new java.io.ByteArrayInputStream(source.content().getBytes(StandardCharsets.UTF_8)));
                Element project = document.getDocumentElement();
                Element parent = child(project, "parent");
                String groupId = text(project, "groupId", parent == null ? null : text(parent, "groupId", null));
                String version = text(project, "version", parent == null ? null : text(parent, "version", null));
                var dependencies = new ArrayList<Dependency>();
                Element dependenciesNode = child(project, "dependencies");
                if (dependenciesNode != null) {
                    for (Element dependency : children(dependenciesNode, "dependency")) {
                        dependencies.add(new Dependency(
                                text(dependency, "groupId", null),
                                text(dependency, "artifactId", null),
                                text(dependency, "version", null),
                                text(dependency, "scope", null)));
                    }
                }
                result.add(new Pom(source.path(), groupId, text(project, "artifactId", null), version,
                        text(project, "packaging", null), List.copyOf(dependencies),
                        parent == null ? null : new Dependency(text(parent, "groupId", null), text(parent, "artifactId", null), text(parent, "version", null), "parent"),
                        MavenBuildContract.signature(source.content())));
            } catch (Exception ignored) {
                // A malformed POM reduces coverage but cannot fail the origin review.
            }
        }
        return List.copyOf(result);
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName(element).equals(name)) return element;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName(element).equals(name)) result.add(element);
        }
        return result;
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        return local == null ? element.getTagName().replaceFirst("^.*:", "") : local;
    }

    private static String text(Element parent, String name, String fallback) {
        Element element = child(parent, name);
        return element == null || element.getTextContent().isBlank() ? fallback : element.getTextContent().strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Dependency(String groupId, String artifactId, String version, String scope) {}
    private record Pom(String path, String groupId, String artifactId, String version,
                       String packaging, List<Dependency> dependencies, Dependency parent, String buildContract) {}
}
