package com.wynk.codecontext.port.contract.graph;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.port.contract.ContractEntryMetadata;
import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Binds contract evidence to generation-local code nodes with explicit ambiguity outcomes. */
public final class GraphSymbolBinder {
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    private final NodeSource nodeSource;

    public GraphSymbolBinder(JdbcTemplate jdbc, JsonMapper mapper) {
        this((generation,file)->jdbc.query("SELECT id,node_type,symbol,qualified_name,start_line,end_line,metadata::text FROM code_nodes WHERE generation_id=? AND file_path=? ORDER BY start_line,end_line,id",
                (rs,n) -> { Map<String,Object> metadata; try { metadata=mapper.readValue(rs.getString(7),MAP); } catch(Exception ignored) { metadata=Map.of(); }
                    return new NodeCandidate(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),metadata); },generation,file));
    }

    GraphSymbolBinder(NodeSource nodeSource) { this.nodeSource=nodeSource; }

    public ContractIndex bind(ContractIndex index, java.util.UUID generationId) {
        List<ContractExport> exports = index.exports().stream().map(e -> new ContractExport(e.contract(), e.type(), e.sourceFile(), e.symbol(), e.contentHash(), bind(generationId, e.sourceFile(), e.symbol(), e.metadata()))).toList();
        List<ContractImport> imports = index.imports().stream().map(i -> new ContractImport(i.contract(), i.type(), i.sourceFile(), i.symbol(), i.dependencyVersion(), bind(generationId, i.sourceFile(), i.symbol(), i.metadata()))).toList();
        var enriched = new ContractIndex(index.owner(), index.repository(), index.commit(), index.branch(), index.indexSchemaVersion(), index.extractorVersions(), index.compatibilityRulesVersion(), index.generatorVersion(), index.generatedAt(), null, exports, imports);
        return enriched.withIndexHash(com.wynk.codecontext.port.contract.ContractIndexHasher.hash(enriched));
    }

    private Map<String,Object> bind(java.util.UUID generation, String file, String symbol, Map<String,Object> base) {
        var out = new LinkedHashMap<>(base);
        if (file == null || file.isBlank()) {
            out.put("graphBindingStatus", "UNRESOLVED");
            out.put("graphBindingReason", "missing source file");
            return Map.copyOf(out);
        }
        List<NodeCandidate> nodes = nodeSource.nodes(generation,file);
        if (nodes.isEmpty()) {
            out.put("graphBindingStatus", "UNRESOLVED");
            out.put("graphBindingReason", "no code graph nodes for source file");
            return Map.copyOf(out);
        }
        String priorGraphId = ContractEntryMetadata.stringMetadata(base, ContractEntryMetadata.GRAPH_NODE_ID);
        List<NodeCandidate> strong = priorGraphId == null ? List.of() : nodes.stream().filter(n -> priorGraphId.equals(String.valueOf(n.metadata().get("id")))).toList();
        if (strong.isEmpty() && symbol != null && !symbol.isBlank()) {
            String normalized = normalize(symbol);
            strong = nodes.stream().filter(n -> normalize(n.qualifiedName()).equals(normalized) || normalize(n.symbol()).equals(normalized)).toList();
        }
        if (strong.isEmpty()) {
            Integer line = sourceStartLine(base);
            if (line != null) {
                List<NodeCandidate> atEvidence = nodes.stream().filter(n -> n.startLine() <= line && n.endLine() >= line)
                        .filter(n -> isStructural(n.type())).toList();
                List<NodeCandidate> parserNodes=atEvidence.stream().filter(GraphSymbolBinder::isParserNode).toList();
                if (!parserNodes.isEmpty()) strong = parserNodes;
                else if (!atEvidence.isEmpty()) strong = atEvidence;
            }
        }
        if (strong.isEmpty() && symbol != null && !symbol.isBlank()) {
            String leaf = leaf(symbol);
            List<NodeCandidate> fuzzy = nodes.stream().filter(n -> normalize(n.symbol()).equals(leaf) || normalize(n.qualifiedName()).endsWith("." + leaf)).toList();
            if (fuzzy.size() == 1) strong = fuzzy;
            else if (!fuzzy.isEmpty()) {
                out.put("graphBindingStatus", "AMBIGUOUS");
                out.put("graphBindingCandidates", fuzzy.stream().map(NodeCandidate::id).toList());
                out.put("graphBindingReason", "multiple source-local symbols match; no name-only binding selected");
                return Map.copyOf(out);
            }
        }
        if (strong.size() == 1) {
            NodeCandidate node = strong.get(0);
            if(node.metadata().get("id")!=null) out.put(ContractEntryMetadata.GRAPH_NODE_ID, String.valueOf(node.metadata().get("id")));
            else out.remove(ContractEntryMetadata.GRAPH_NODE_ID);
            out.put("boundCodeNodeId", node.id());
            out.put("boundStartLine",node.startLine()); out.put("boundEndLine",node.endLine());
            out.put("graphBindingStatus", "RESOLVED");
            out.put("graphBindingConfidence", 0.90);
            out.put("graphBindingEvidence", "source file plus exact symbol or source range");
            if (node.qualifiedName() != null && !node.qualifiedName().isBlank()) out.putIfAbsent("boundQualifiedName", node.qualifiedName());
        } else if (strong.size() > 1) {
            List<NodeCandidate> best = narrowOverloads(strong, base);
            if (best.size() == 1) {
                NodeCandidate node = best.get(0);
                out.put("boundCodeNodeId", node.id());
                out.put("boundStartLine",node.startLine()); out.put("boundEndLine",node.endLine());
                if(node.metadata().get("id")!=null) out.put(ContractEntryMetadata.GRAPH_NODE_ID, String.valueOf(node.metadata().get("id")));
                else out.remove(ContractEntryMetadata.GRAPH_NODE_ID);
                out.put("graphBindingStatus", "RESOLVED");
                out.put("graphBindingConfidence", 0.90);
                out.put("graphBindingEvidence", "source range disambiguated overload");
            } else {
                out.put("graphBindingStatus", "AMBIGUOUS");
                out.put("graphBindingCandidates", best.stream().map(NodeCandidate::id).toList());
                out.put("graphBindingReason", "multiple overloads match source evidence");
            }
        } else {
            out.put("graphBindingStatus", "UNRESOLVED");
            out.put("graphBindingReason", "no structural symbol match");
        }
        return Map.copyOf(out);
    }

    private List<NodeCandidate> narrowOverloads(List<NodeCandidate> nodes, Map<String,Object> metadata) {
        Integer line = sourceStartLine(metadata);
        if (line == null) return nodes;
        List<NodeCandidate> onLine = nodes.stream().filter(n -> n.startLine() <= line && n.endLine() >= line).toList();
        if(onLine.isEmpty()) return nodes;
        int narrowest=onLine.stream().mapToInt(n->n.endLine()-n.startLine()).min().orElse(Integer.MAX_VALUE);
        return onLine.stream().filter(n->n.endLine()-n.startLine()==narrowest).toList();
    }

    private Integer sourceStartLine(Map<String,Object> metadata) {
        Object range = metadata.get(ContractEntryMetadata.SOURCE_RANGE);
        if (range instanceof Map<?,?> map && map.get("startLine") instanceof Number n) return n.intValue();
        return null;
    }

    private static boolean isStructural(String type) { return type != null && (type.equalsIgnoreCase("METHOD") || type.equalsIgnoreCase("CLASS") || type.equalsIgnoreCase("FUNCTION") || type.equalsIgnoreCase("method") || type.equalsIgnoreCase("class")); }
    private static boolean isParserNode(NodeCandidate node) { return node.type()!=null && (node.type().equalsIgnoreCase("METHOD")||node.type().equalsIgnoreCase("CLASS")||node.type().equalsIgnoreCase("CONSTRUCTOR")) && !node.metadata().containsKey("id"); }
    private static String leaf(String s) { String value = normalize(s); int p = Math.max(value.lastIndexOf('.'), Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'))); return p < 0 ? value : value.substring(p+1); }
    private static String normalize(String s) { return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
    @FunctionalInterface interface NodeSource { List<NodeCandidate> nodes(java.util.UUID generation,String file); }
    public record NodeCandidate(long id,String type,String symbol,String qualifiedName,int startLine,int endLine,Map<String,Object> metadata) {}
}
