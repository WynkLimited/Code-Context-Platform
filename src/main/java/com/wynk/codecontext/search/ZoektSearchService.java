package com.wynk.codecontext.search;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class ZoektSearchService {
    private final RestClient zoekt; private final RepositoryStore repositories;
    public ZoektSearchService(CodeContextProperties props, RepositoryStore repositories) {
        this.zoekt=RestClient.builder().baseUrl(props.zoekt().webserver()).build(); this.repositories=repositories;
    }
    public String search(String query) {
        String team=String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        List<RepositoryRecord> allowed=repositories.findAccessible(team);
        if(allowed.isEmpty()) return "{\"results\":[]}";
        // Issue one restricted query per repository so repository authorization never depends on client filters.
        var results=new java.util.ArrayList<String>();
        for(RepositoryRecord repo:allowed) {
            var generation=repositories.currentGeneration(repo.id());if(generation.isEmpty())continue;
            String generationName=repo.key()+"@"+generation.get();
            String restricted="("+query+") repo:^"+generationName.replace(".","\\.")+"$";
            try { String body=zoekt.get().uri(uri->uri.path("/api/search").queryParam("q",restricted).build()).retrieve().body(String.class); if(body!=null)results.add(body.replace(generationName,repo.key())); }
            catch(Exception ex){results.add("{\"repository\":\""+repo.key()+"\",\"error\":\"Zoekt unavailable\"}");}
        }
        return "{\"repositoryResults\":["+String.join(",",results)+"]}";
    }
}
