package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.CallSite;

/** Associates a resolved call with its actual dependency classpath entry. */
final class SemanticArtifactOwnership {
    static String find(CallSite call,MavenClasspathResolver.ModuleClasspath classpath) {
        if(classpath==null||call.declaringType()==null)return null;
        if(!"JAR".equals(call.declarationOrigin())&&!"UNKNOWN".equals(call.declarationOrigin()))return null;
        String binary=call.binaryClassName()==null?call.declaringType():call.binaryClassName();
        String entry=binary.replace('.','/')+".class";
        // Match the exact order used by JavaSemanticCallSiteAnalyzer's JarTypeSolvers.
        for(var jar:classpath.jars()) {
            if(!jar.toString().endsWith(".jar"))continue;
            try(var zip=new java.util.zip.ZipFile(jar.toFile())) {
                if(zip.getEntry(entry)==null)continue;
                return classpath.dependencies().stream().filter(dependency->dependency.artifact().toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize()))
                        .map(dependency->dependency.groupId()+":"+dependency.artifactId()+":"+dependency.version()).findFirst().orElse(null);
            } catch(java.io.IOException unreadable) {return null;}
        }
        return null;
    }
}
