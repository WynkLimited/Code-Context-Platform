package com.wynk.codecontext.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Content changes relative to the selected published manifest, independent of Git ancestry. */
public record PublishedFileDelta(List<String> added,List<String> modified,List<String> deleted,
                                 List<Move> renamed,boolean globalInputsChanged) {
    public record Move(String before,String after) {}
    private static final Set<String> GLOBAL_INPUTS=Set.of("pom.xml","build.gradle","build.gradle.kts",
            "settings.gradle","settings.gradle.kts","gradle.properties","gradle.lockfile","libs.versions.toml",
            "package.json","package-lock.json","yarn.lock","pnpm-lock.yaml","go.mod","go.sum","Cargo.toml","Cargo.lock",
            "pyproject.toml","requirements.txt","tsconfig.json","jsconfig.json",".gitignore",".graphifyignore",".graphifyrc","graphify.yaml","graphify.yml");
    public static PublishedFileDelta between(Map<String,String> published,Map<String,String> requested) {
        var additions=new TreeSet<String>();var modifications=new TreeSet<String>();var deletions=new TreeSet<String>();
        for(var file:requested.entrySet()) {
            if(!published.containsKey(file.getKey()))additions.add(file.getKey());
            else if(!file.getValue().equals(published.get(file.getKey())))modifications.add(file.getKey());
        }
        for(String path:published.keySet())if(!requested.containsKey(path))deletions.add(path);
        var removedByHash=group(deletions,published);var addedByHash=group(additions,requested);var moves=new ArrayList<Move>();
        for(String before:List.copyOf(deletions)) {
            String hash=published.get(before);var candidates=addedByHash.get(hash);
            // Repeated identical blobs cannot prove which path moved. Preserve explicit add/delete
            // changes rather than inventing a source identity from map iteration order.
            if(removedByHash.get(hash).size()==1&&candidates!=null&&candidates.size()==1) {
                String after=candidates.getFirst();moves.add(new Move(before,after));deletions.remove(before);additions.remove(after);
            }
        }
        var changed=new TreeSet<String>();changed.addAll(additions);changed.addAll(modifications);changed.addAll(deletions);
        for(var move:moves){changed.add(move.before());changed.add(move.after());}
        return new PublishedFileDelta(List.copyOf(additions),List.copyOf(modifications),List.copyOf(deletions),List.copyOf(moves),changed.stream().anyMatch(PublishedFileDelta::globalInput));
    }
    public List<String> affectedPaths() {
        var paths=new TreeSet<String>();paths.addAll(added);paths.addAll(modified);paths.addAll(deleted);
        for(var move:renamed){paths.add(move.before());paths.add(move.after());}return List.copyOf(paths);
    }
    public boolean empty(){return added.isEmpty()&&modified.isEmpty()&&deleted.isEmpty()&&renamed.isEmpty();}
    private static Map<String,List<String>> group(Set<String> paths,Map<String,String> hashes) {
        var result=new HashMap<String,List<String>>();for(String path:paths)result.computeIfAbsent(hashes.get(path),ignored->new ArrayList<>()).add(path);return result;
    }
    private static boolean globalInput(String path) {
        String file=path.substring(path.lastIndexOf('/')+1);
        return GLOBAL_INPUTS.contains(file)||file.endsWith(".lockfile")||file.endsWith(".csproj")||file.endsWith(".fsproj")
                ||file.startsWith("tsconfig.")&&file.endsWith(".json")||file.startsWith("requirements-")&&file.endsWith(".txt");
    }
}
