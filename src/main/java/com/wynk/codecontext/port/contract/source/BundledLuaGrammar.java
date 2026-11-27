package com.wynk.codecontext.port.contract.source;

import org.treesitter.TSLanguage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Pinned upstream grammar, rebuilt because the published ABI-13 GNU binary rejects valid Lua. */
final class BundledLuaGrammar {
    private static final String ROOT="/native/lua-v0.4.1/";
    private static final int MAX_LIBRARY_BYTES=2*1024*1024;
    private static final class Holder {private static final TSLanguage LANGUAGE=loadVerified();}
    static TSLanguage load(){return Holder.LANGUAGE.copy();}
    private static TSLanguage loadVerified() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String architecture=switch(System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64","x86_64"->"x86_64";
            case "aarch64","arm64"->os.contains("mac")?"arm64":"aarch64";
            default->throw new IllegalStateException("unsupported Lua native architecture");
        };
        String name="tree-sitter-lua-"+(os.contains("mac")?"macos-":os.contains("linux")?"linux-":unsupported())+architecture+(os.contains("mac")?".dylib":".so");
        try {
            String expected=null;
            for(String line:new String(resource("SHA256SUMS",4096),StandardCharsets.US_ASCII).lines().toList()) {
                String[] fields=line.split("\\s+");
                if(fields.length==2&&fields[1].equals(name))expected=fields[0];
            }
            if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IllegalStateException("Lua grammar checksum missing");
            byte[] library=resource(name,MAX_LIBRARY_BYTES);
            if(!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(library))))
                throw new IllegalStateException("Lua grammar checksum mismatch");
            var directory=Files.createTempDirectory("ccp-trusted-lua-");directory.toFile().deleteOnExit();
            var path=directory.resolve(name);Files.write(path,library,StandardOpenOption.CREATE_NEW);path.toFile().deleteOnExit();
            Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
            return TSLanguage.load(path.toAbsolutePath().toString(),"tree_sitter_lua");
        } catch(IOException|java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("verified Lua grammar unavailable",failure);
        }
    }
    private static String unsupported(){throw new IllegalStateException("unsupported Lua native operating system");}
    private static byte[] resource(String name,int maxBytes)throws IOException {
        try(InputStream input=BundledLuaGrammar.class.getResourceAsStream(ROOT+name)) {
            if(input==null)throw new IOException("bundled Lua resource missing");
            byte[] bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes)throw new IOException("bundled Lua resource exceeds limit");
            return bytes;
        }
    }
}
