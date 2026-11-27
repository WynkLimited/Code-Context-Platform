package com.wynk.codecontext.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Retrieval groups do not replace declaration/graph identities. Only adjacent declarations
 * separated by whitespace in the same named scope may share an embedding input. */
final class TinyChunkGroups {
    record Definition(String kind,String signature,String owner,JavaSourceText.Slice source) {}
    record Group(List<Definition> members,JavaSourceText.Slice source) {
        String signatures(){return String.join(" | ",members.stream().map(Definition::signature).toList());}
    }
    static List<Group> merge(String source,List<Definition> definitions,int maxChars) {
        var ordered=definitions.stream().sorted(Comparator.comparingInt(d->d.source().startOffset())).toList();
        var groups=new ArrayList<Group>();
        for(var definition:ordered) {
            if(!groups.isEmpty()) {
                var previous=groups.getLast();var last=previous.members().getLast();
                var a=previous.source();var b=definition.source();
                boolean tiny=a.lastLine()-a.firstLine()+1<5||b.lastLine()-b.firstLine()+1<5;
                if(tiny&&definition.owner()!=null&&definition.owner().equals(last.owner())
                        &&b.startOffset()>=a.endOffset()&&b.endOffset()-a.startOffset()<=Math.max(512,maxChars)
                        &&source.substring(a.endOffset(),b.startOffset()).isBlank()) {
                    var members=new ArrayList<>(previous.members());members.add(definition);
                    groups.set(groups.size()-1,new Group(List.copyOf(members),new JavaSourceText.Slice(
                            source.substring(a.startOffset(),b.endOffset()),a.firstLine(),b.lastLine(),a.startOffset(),b.endOffset())));
                    continue;
                }
            }
            groups.add(new Group(List.of(definition),definition.source()));
        }
        return List.copyOf(groups);
    }
}
