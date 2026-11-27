package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MavenArtifactChecksumTest {
    @TempDir Path root;
    @Test void hashesAnArtifactLargerThanTheWorkerHeap() throws Exception {
        Path artifact=root.resolve("large.jar");
        byte[] block=new byte[8192];
        for(int i=0;i<block.length;i++)block[i]=(byte)(i*31);
        var expected=MessageDigest.getInstance("SHA-256");
        try(var output=Files.newOutputStream(artifact)) {
            for(int i=0;i<8192;i++){output.write(block);expected.update(block);}
        }
        Path log=root.resolve("checksum.log");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process worker=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx24m","-cp",classpath,Worker.class.getName(),artifact.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(worker.waitFor(20,TimeUnit.SECONDS),"checksum worker timed out");
            assertEquals(0,worker.exitValue(),()->Files.exists(log)?read(log):"missing worker log");
            assertEquals(HexFormat.of().formatHex(expected.digest()),Files.readString(log).strip());
        } finally {if(worker.isAlive()){worker.destroyForcibly();assertTrue(worker.waitFor(5,TimeUnit.SECONDS));}}
    }
    @Test void distinguishesEqualSizedArtifactContents() throws Exception {
        Path artifact=root.resolve("artifact.jar");Files.writeString(artifact,"abc");
        String first=MavenClasspathResolver.artifactChecksum(artifact);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",first);
        Files.writeString(artifact,"abd");assertNotEquals(first,MavenClasspathResolver.artifactChecksum(artifact));
    }
    @Test void rejectsOversizedArtifactsBeforeReadingThem() throws Exception {
        Path artifact=root.resolve("oversized.jar");
        try(var file=new java.io.RandomAccessFile(artifact.toFile(),"rw")){file.setLength(512L*1024*1024+1);}
        var failure=assertThrows(java.io.IOException.class,()->MavenClasspathResolver.artifactChecksum(artifact));
        assertEquals("Artifact size limit exceeded",failure.getMessage());
    }
    private static String read(Path log){try{return Files.readString(log);}catch(Exception failure){return failure.toString();}}
    public static final class Worker {
        public static void main(String[] args) throws Exception {System.out.println(MavenClasspathResolver.artifactChecksum(Path.of(args[0])));}
    }
}
