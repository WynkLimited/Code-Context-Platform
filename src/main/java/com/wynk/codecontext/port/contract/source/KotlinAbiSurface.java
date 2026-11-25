package com.wynk.codecontext.port.contract.source;

import kotlin.metadata.*;
import kotlin.metadata.jvm.*;
import org.springframework.asm.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** JVM binary identities plus Kotlin source visibility. No classes are loaded or initialized. */
public record KotlinAbiSurface(List<Member> members,boolean metadataComplete,List<String> limitations) {
    public record Member(String owner,String name,String descriptor,String visibility,int access) {
        public String binaryKey(){return owner+"#"+name+descriptor;}
        public boolean exported(){return visibility.equals("PUBLIC")||visibility.equals("PROTECTED");}
    }
    public record Difference(List<String> removedBinaryMembers,List<String> sourceVisibilityReductions,boolean sourceCompatibilityKnown) {}
    public Difference compareTo(KotlinAbiSurface after) {
        var binary=new HashSet<String>();var exported=new HashSet<String>();
        for(var member:after.members) {
            if((member.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0)binary.add(member.binaryKey());
            if(member.exported())exported.add(member.binaryKey());
        }
        var removed=new TreeSet<String>();var reduced=new TreeSet<String>();
        for(var member:members)if(member.exported()) {
            if(!binary.contains(member.binaryKey()))removed.add(member.binaryKey());
            else if(!exported.contains(member.binaryKey()))reduced.add(member.binaryKey());
        }
        // Unchanged JVM signatures do not prove Kotlin source compatibility (nullability/inline/defaults).
        return new Difference(List.copyOf(removed),List.copyOf(reduced),false);
    }
    public static KotlinAbiSurface read(Path jar) throws Exception {
        var members=new ArrayList<Member>();var complete=new boolean[]{true};
        try(var archive=new JarFile(jar.toFile())) {
            for(var entry:Collections.list(archive.entries()))if(entry.getName().endsWith(".class")) {
                try(var input=archive.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        String owner;int classAccess;boolean classExported=true;boolean parsed;
                        final Map<String,Object> header=new HashMap<>();
                        final Map<String,String> visibility=new HashMap<>();
                        @Override public void visit(int v,int a,String n,String sig,String parent,String[] interfaces){owner=n.replace('/','.');classAccess=a;}
                        @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible) {
                            if(!desc.equals("Lkotlin/Metadata;"))return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String name,Object value){header.put(name,value);}
                                @Override public AnnotationVisitor visitArray(String name) {
                                    var values=new ArrayList<String>();header.put(name,values);
                                    return new AnnotationVisitor(Opcodes.ASM9){@Override public void visit(String n,Object v){values.add(String.valueOf(v));}};
                                }
                            };
                        }
                        void parse() {
                            if(parsed)return;parsed=true;
                            try {
                                var metadata=JvmMetadataUtil.Metadata((Integer)header.getOrDefault("k",1),(int[])header.getOrDefault("mv",new int[]{2,2,0}),strings("d1"),strings("d2"),(String)header.getOrDefault("xs",""),(String)header.getOrDefault("pn",""),(Integer)header.getOrDefault("xi",0));
                                var decoded=KotlinClassMetadata.readStrict(metadata);
                                if(decoded instanceof KotlinClassMetadata.Class type) {
                                    var km=type.getKmClass();classExported=exported(Attributes.getVisibility(km));
                                    for(var ctor:km.getConstructors())put(JvmExtensionsKt.getSignature(ctor),Attributes.getVisibility(ctor));
                                    collect(km.getFunctions(),km.getProperties());
                                } else if(decoded instanceof KotlinClassMetadata.FileFacade facade)collect(facade.getKmPackage().getFunctions(),facade.getKmPackage().getProperties());
                                else if(decoded instanceof KotlinClassMetadata.MultiFileClassPart part)collect(part.getKmPackage().getFunctions(),part.getKmPackage().getProperties());
                                else complete[0]=false;
                            } catch(RuntimeException failed) {complete[0]=false;}
                        }
                        String[] strings(String key){return ((List<?>)header.getOrDefault(key,List.of())).stream().map(String::valueOf).toArray(String[]::new);}
                        void put(JvmMethodSignature signature,Visibility value){if(signature!=null)visibility.put(signature.getName()+signature.getDescriptor(),value.name());}
                        void collect(List<KmFunction> functions,List<KmProperty> properties) {
                            for(var function:functions)put(JvmExtensionsKt.getSignature(function),Attributes.getVisibility(function));
                            for(var property:properties) {
                                put(JvmExtensionsKt.getGetterSignature(property),Attributes.getVisibility(property.getGetter()));
                                if(property.getSetter()!=null)put(JvmExtensionsKt.getSetterSignature(property),Attributes.getVisibility(property.getSetter()));
                            }
                        }
                        @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                            parse();
                            if((access&(Opcodes.ACC_SYNTHETIC|Opcodes.ACC_BRIDGE))!=0||name.equals("<clinit>"))return null;
                            String source=classExported&&(classAccess&Opcodes.ACC_PUBLIC)!=0?visibility.getOrDefault(name+desc,"UNKNOWN"):"PRIVATE";
                            if(source.equals("UNKNOWN")&&(access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0)complete[0]=false;
                            int effectiveAccess=(classAccess&Opcodes.ACC_PUBLIC)!=0?access:access&~(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED);
                            members.add(new Member(owner,name,desc,source,effectiveAccess));return null;
                        }
                    },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                }
            }
        }
        members.sort(Comparator.comparing(Member::binaryKey));
        return new KotlinAbiSurface(List.copyOf(members),complete[0],List.of("Kotlin source compatibility unknown: inline bodies, defaults, nullability, suspend and synthetic bridges require additional analysis"));
    }
    private static boolean exported(Visibility visibility){return visibility==Visibility.PUBLIC||visibility==Visibility.PROTECTED;}
}
