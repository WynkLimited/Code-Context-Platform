package com.wynk.codecontext.search;

import com.wynk.codecontext.port.git.ProcessRunner;
import org.eclipse.jgit.api.Git;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Normalizes valid JGit pack names for go-git's strict checksum-based pack lookup. */
final class GitPackCompatibility {
    private GitPackCompatibility() {}
    static boolean normalize(Path checkout) throws IOException {
        Path packs;
        try(var git=Git.open(checkout.toFile())) {packs=git.getRepository().getDirectory().toPath().resolve("objects/pack");}
        if(!hasNonCanonicalPack(packs))return false;
        ProcessRunner.run(List.of("git","-c","core.hooksPath=/dev/null","-c","pack.threads=1",
                "-c","pack.windowMemory=64m","-c","gc.auto=0","repack","-a","-d"),checkout,120,Map.of());
        if(hasNonCanonicalPack(packs))throw new IOException("Git pack normalization left non-checksum pack names");
        return true;
    }
    private static boolean hasNonCanonicalPack(Path packs) throws IOException {
        if(!Files.isDirectory(packs))return false;
        try(var files=Files.list(packs)) {
            for(Path index:files.filter(p->p.getFileName().toString().matches("pack-[0-9a-f]{40}\\.idx")).toList()) {
                if(Files.isSymbolicLink(index))throw new IOException("symlinked Git pack index rejected");
                long size=Files.size(index);
                if(size<40)throw new IOException("Git pack index is truncated");
                ByteBuffer checksum=ByteBuffer.allocate(20);
                try(var channel=FileChannel.open(index,StandardOpenOption.READ)) {
                    channel.position(size-40);
                    while(checksum.hasRemaining())if(channel.read(checksum)<0)throw new IOException("Git pack index is truncated");
                }
                String expected="pack-"+HexFormat.of().formatHex(checksum.array())+".idx";
                if(!index.getFileName().toString().equals(expected))return true;
            }
        }
        return false;
    }
}
