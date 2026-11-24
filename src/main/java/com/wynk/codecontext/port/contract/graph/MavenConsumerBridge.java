package com.wynk.codecontext.port.contract.graph;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;

import java.util.*;

/** Routes consumers of a changed Maven artifact, including directly dependent reactor artifacts. */
public final class MavenConsumerBridge {
    private MavenConsumerBridge() {}
    public static List<String> consumerMatchKeys(ContractIndex originHead, ContractChange change) {
        return change == null ? List.of() : consumerMatchKeys(originHead, change.identity());
    }
    public static List<String> consumerMatchKeys(ContractIndex originHead, ContractIdentity identity) {
        if (identity == null) return List.of();
        if (identity.type() != ContractType.MAVEN || originHead == null) return List.of(identity.canonicalKey());
        var keys = new LinkedHashSet<String>(); keys.add(identity.canonicalKey());
        Map<String,String> artifactByPom = new HashMap<>();
        for (ContractExport export : originHead.exportsOf(ContractType.MAVEN))
            if (export.sourceFile() != null && export.sourceFile().endsWith("pom.xml")) artifactByPom.put(normalize(export.sourceFile()), export.canonicalKey());
        for (ContractImport imp : originHead.importsOf(ContractType.MAVEN))
            if (identity.canonicalKey().equals(imp.canonicalKey())) {
                String artifact = artifactByPom.get(normalize(imp.sourceFile())); if (artifact != null) keys.add(artifact);
            }
        return List.copyOf(keys);
    }
    public static List<String> consumerImportFiles(ContractIndex consumer, List<String> keys) {
        var files=new TreeSet<String>();
        if (consumer != null && keys != null) for(String key:keys) {
            files.addAll(consumer.importingFiles(key));
            if(key.startsWith("rest:")) for(ContractImport imp:consumer.importsOf(ContractType.REST))
                if(restMatches(key,imp.canonicalKey())&&imp.sourceFile()!=null) files.add(imp.sourceFile());
        }
        return List.copyOf(files);
    }
    public static boolean consumerImports(ContractIndex consumer,List<String> keys) { return !consumerImportFiles(consumer,keys).isEmpty(); }
    private static boolean restMatches(String provider,String consumer) {
        int p1=provider.indexOf(':'),p2=provider.indexOf(':',p1+1),c1=consumer.indexOf(':'),c2=consumer.indexOf(':',c1+1);
        if(p1<0||p2<0||c1<0||c2<0)return provider.equals(consumer);
        String pm=provider.substring(p1+1,p2),cm=consumer.substring(c1+1,c2);
        return provider.substring(p2+1).equals(consumer.substring(c2+1))&&(pm.equals("*")||cm.equals("*")||pm.equals(cm));
    }
    private static String normalize(String path) { return path == null ? "" : path.replace('\\','/').toLowerCase(Locale.ROOT); }
}
