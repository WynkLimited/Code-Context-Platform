package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.crossrepo.PromptSafety;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Offline Maven inheritance evidence only. Never invokes Maven, plugins, extensions or repositories. */
public final class MavenBuildContract {
    private MavenBuildContract() {}
    private record Dependency(String key, String version) {}
    private record Model(String coordinate, String parent, Map<String, String> properties,
            Map<String, String> managed, List<Dependency> dependencies, List<String> limitations) {}
    private record Effective(Map<String, String> properties, Map<String, String> managed,
            Map<String, String> dependencies, List<String> limitations) {}
    public record Assessment(List<String> changes, List<String> limitations, boolean classpathVerified) {}

    public static Map<String, String> sources(Path root) {
        var result = new TreeMap<String, String>();
        long bytes = 0;
        var candidates = new TreeSet<Path>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, Set.of(), 32, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
                    if (++visited[0] > 5000 || candidates.size() >= 256) return FileVisitResult.TERMINATE;
                    return Set.of(".git", "target", "node_modules", "build", ".venv", "generated").contains(path.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
                    if (++visited[0] > 5000 || candidates.size() >= 256) return FileVisitResult.TERMINATE;
                    if (attributes.isRegularFile() && path.getFileName().toString().equals("pom.xml")) candidates.add(path);
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path path : candidates) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                Path safe = PromptSafety.resolveContained(root, relative);
                if (safe == null || Files.isSymbolicLink(path) || !Files.isRegularFile(safe)) continue;
                long size = Files.size(safe);
                if (size > 2L * 1024 * 1024 || bytes + size > 16L * 1024 * 1024) continue;
                result.put(relative, Files.readString(safe)); bytes += size;
            }
        } catch (java.io.IOException ignored) { }
        return Map.copyOf(result);
    }

    public static String signature(String xml) {
        Model model = parse(xml);
        var result = new TreeMap<String, String>();
        result.put("parent", model.parent());
        model.managed().forEach((key, version) -> result.put("managed:" + key, expand(version, model.properties())));
        model.dependencies().forEach(d -> result.put("dependency:" + d.key(), expand(d.version(), model.properties())));
        return result.toString();
    }

    public static Assessment assess(Map<String, String> consumer, Map<String, String> before, Map<String, String> after) {
        var limitations = new TreeSet<String>();
        var changes = new TreeSet<String>();
        Map<String, Model> left = models(consumer, before, limitations), right = models(consumer, after, limitations);
        for (var entry : new TreeMap<>(consumer).entrySet()) {
            if (!entry.getKey().endsWith("pom.xml")) continue;
            try {
                Model model = parse(entry.getValue());
                Effective baseline = effective(model, left, new HashSet<>()), head = effective(model, right, new HashSet<>());
                limitations.addAll(baseline.limitations()); limitations.addAll(head.limitations());
                var all = new TreeSet<>(baseline.dependencies().keySet()); all.addAll(head.dependencies().keySet());
                for (String key : all) {
                    String oldVersion = baseline.dependencies().getOrDefault(key, "ABSENT");
                    String newVersion = head.dependencies().getOrDefault(key, "ABSENT");
                    if (!oldVersion.equals(newVersion)) changes.add(entry.getKey() + " " + key + " " + oldVersion + " -> " + newVersion);
                    if (oldVersion.contains("${") || newVersion.contains("${")) limitations.add("UNRESOLVED_VERSION_PROPERTY: " + key);
                }
            } catch (IllegalArgumentException e) { limitations.add("MALFORMED_OR_UNSAFE_CONSUMER_POM: " + entry.getKey()); }
        }
        if (consumer.keySet().stream().noneMatch(p -> p.endsWith("pom.xml"))) limitations.add("CONSUMER_POM_NOT_SELECTED");
        limitations.add("CLASSPATH_MEDIATION_UNVERIFIED: transitive artifacts, external BOMs and activated profiles require approved isolated Maven evidence");
        return new Assessment(List.copyOf(changes), List.copyOf(limitations), false);
    }

    private static Map<String, Model> models(Map<String, String> consumer, Map<String, String> producer, Set<String> problems) {
        var models = new HashMap<String, Model>();
        for (var set : List.of(consumer, producer)) for (var entry : set.entrySet()) if (entry.getKey().endsWith("pom.xml")) {
            try {
                Model model = parse(entry.getValue());
                models.put(model.coordinate(), model);
            } catch (IllegalArgumentException e) { problems.add("MALFORMED_OR_UNSAFE_POM: " + entry.getKey()); }
        }
        return models;
    }

    private static Effective effective(Model model, Map<String, Model> available, Set<String> visited) {
        var properties = new TreeMap<String, String>();
        var managed = new TreeMap<String, String>();
        var dependencies = new TreeMap<String, String>();
        var problems = new ArrayList<>(model.limitations());
        if (!visited.add(model.coordinate()) || visited.size() > 20)
            return new Effective(properties, managed, dependencies, List.of("PARENT_CYCLE_OR_DEPTH_LIMIT"));
        if (!model.parent().isEmpty()) {
            Model parent = available.get(model.parent());
            if (parent == null) problems.add("EXTERNAL_PARENT_UNAVAILABLE: " + model.parent());
            else {
                Effective inherited = effective(parent, available, visited);
                properties.putAll(inherited.properties()); managed.putAll(inherited.managed());
                dependencies.putAll(inherited.dependencies()); problems.addAll(inherited.limitations());
            }
        }
        properties.putAll(model.properties()); managed.putAll(model.managed());
        for (var dependency : model.dependencies()) {
            String version = dependency.version().isEmpty() ? managed.getOrDefault(dependency.key(), "UNMANAGED") : dependency.version();
            dependencies.put(dependency.key(), expand(version, properties));
        }
        return new Effective(Map.copyOf(properties), Map.copyOf(managed), Map.copyOf(dependencies), List.copyOf(problems));
    }

    private static Model parse(String xml) {
        if (xml == null || xml.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("POM unavailable or oversized");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false); factory.setXIncludeAware(false);
            Element project = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            var parent = child(project, "parent");
            String group = text(project, "groupId", text(parent, "groupId", ""));
            String version = text(project, "version", text(parent, "version", ""));
            var properties = new TreeMap<String, String>();
            Element props = child(project, "properties");
            if (props != null) for (Element property : children(props, null)) properties.put(name(property), property.getTextContent().strip());
            properties.put("project.groupId", group); properties.put("project.version", version);
            properties.put("project.artifactId", text(project, "artifactId", ""));
            var managed = new TreeMap<String, String>();
            var limitations = new ArrayList<String>();
            var management = child(child(project, "dependencyManagement"), "dependencies");
            for (Element dependency : children(management, "dependency")) {
                if (text(dependency, "scope", "").equals("import")) limitations.add("IMPORTED_BOM_UNAVAILABLE: " + key(dependency));
                managed.put(key(dependency), text(dependency, "version", "UNMANAGED"));
            }
            var dependencies = children(child(project, "dependencies"), "dependency").stream()
                    .map(d -> new Dependency(key(d), text(d, "version", ""))).toList();
            if (child(project, "profiles") != null) limitations.add("PROFILE_ACTIVATION_UNVERIFIED");
            return new Model(group + ":" + text(project, "artifactId", "") + ":" + version,
                    parent == null ? "" : text(parent, "groupId", "") + ":" + text(parent, "artifactId", "") + ":" + text(parent, "version", ""),
                    Map.copyOf(properties), Map.copyOf(managed), dependencies, List.copyOf(limitations));
        } catch (Exception e) { throw new IllegalArgumentException("Malformed or unsafe Maven model", e); }
    }

    private static String expand(String value, Map<String, String> properties) {
        for (int i = 0; i < 20; i++) {
            if (value.length() > 8192) return "${UNRESOLVED_OVERSIZED_VERSION}";
            var result = new StringBuilder();
            int offset = 0;
            while (offset < value.length()) {
                int start = value.indexOf("${", offset), end = start < 0 ? -1 : value.indexOf('}', start + 2);
                if (end < 0) { result.append(value, offset, value.length()); break; }
                result.append(value, offset, start);
                String replacement = properties.getOrDefault(value.substring(start + 2, end), value.substring(start, end + 1));
                if (result.length() + replacement.length() > 8192) return "${UNRESOLVED_OVERSIZED_VERSION}";
                result.append(replacement); offset = end + 1;
            }
            String expanded = result.toString();
            if (expanded.equals(value)) break;
            value = expanded;
        }
        return value;
    }
    private static String key(Element d) { return text(d, "groupId", "") + ":" + text(d, "artifactId", "")
            + ":" + text(d, "type", "jar") + ":" + text(d, "classifier", ""); }
    private static String name(Element e) { return e.getTagName().replaceFirst("^.*:", ""); }
    private static List<Element> children(Element parent, String wanted) {
        var result = new ArrayList<Element>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element e && (wanted == null || name(e).equals(wanted))) result.add(e);
        return result;
    }
    private static Element child(Element parent, String wanted) { return children(parent, wanted).stream().findFirst().orElse(null); }
    private static String text(Element parent, String wanted, String fallback) {
        var child = child(parent, wanted); return child == null || child.getTextContent().isBlank() ? fallback : child.getTextContent().strip();
    }
}
