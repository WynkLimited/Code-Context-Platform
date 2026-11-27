package com.wynk.codecontext.port.contract.source;

import com.wynk.codecontext.port.contract.*;
import com.wynk.codecontext.port.contract.ContractModels.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Adds compiler-derived exported JVM signatures to the owning Maven artifact contract. */
public final class KotlinAbiContracts {
    private KotlinAbiContracts() {}
    public static ContractIndex enrich(ContractIndex index,Map<String,KotlinAbiSurface> modules) {
        var exports=new ArrayList<ContractExport>();
        for(var entry:index.exports()) {
            String path=entry.sourceFile();
            String module=path==null||!path.contains("/")?"":path.substring(0,path.lastIndexOf('/'));
            var abi=entry.type()==ContractType.MAVEN?modules.get(module):null;
            if(abi==null){exports.add(entry);continue;}
            var metadata=new LinkedHashMap<>(entry.metadata());
            var keys=abi.members().stream().filter(KotlinAbiSurface.Member::exported).map(KotlinAbiSurface.Member::binaryKey).sorted().toList();
            metadata.put("kotlinJvmAbi",keys);
            metadata.put("kotlinMetadataComplete",abi.metadataComplete());
            metadata.put("kotlinSourceCompatibility","UNKNOWN");
            metadata.put("kotlinAbiLimitations",abi.limitations());
            String signature=String.valueOf(metadata.getOrDefault("signature",""));
            signature=signature+(keys.isEmpty()?"":"\n"+String.join("\n",keys.stream().map(k->KotlinAbiSurface.BINARY_SIGNATURE_PREFIX+k).toList()));
            metadata.put("signature",signature);
            try {
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((entry.contentHash()+"\n"+signature+"\n"+abi.metadataComplete()).getBytes(StandardCharsets.UTF_8)));
                metadata.put("contractFingerprint",hash);
                exports.add(new ContractExport(entry.contract(),entry.type(),entry.sourceFile(),entry.symbol(),hash,metadata));
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        var enriched=new ContractIndex(index.owner(),index.repository(),index.commit(),index.branch(),index.indexSchemaVersion(),index.extractorVersions(),index.compatibilityRulesVersion(),index.generatorVersion(),index.generatedAt(),"",exports,index.imports());
        return enriched.withIndexHash(ContractIndexHasher.hash(enriched));
    }
}
