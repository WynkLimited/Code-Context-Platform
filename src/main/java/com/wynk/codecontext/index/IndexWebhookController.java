package com.wynk.codecontext.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.RepositoryStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
public class IndexWebhookController {
    private final IndexingService indexing; private final CodeContextProperties config; private final ObjectMapper json;
    private final RepositoryStore repositories; private final JdbcTemplate jdbc;
    public IndexWebhookController(IndexingService i,CodeContextProperties c,ObjectMapper j,RepositoryStore r,JdbcTemplate db){indexing=i;config=c;json=j;repositories=r;jdbc=db;}
    @PostMapping("/webhooks/github/{owner}/{name}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> github(@PathVariable String owner,@PathVariable String name,
            @RequestHeader(value="X-Hub-Signature-256",required=false) String signature,
            @RequestHeader(value="X-GitHub-Delivery",required=false) String delivery,
            @RequestBody byte[] body) throws Exception {
        if(!valid(signature,body,config.webhookSecret()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid webhook signature");
        JsonNode payload=json.readTree(body);String sha=payload.path("after").asText("");
        if(sha.isBlank()||sha.matches("0+"))return Map.of("accepted",false,"reason","push payload has no commit");
        if(repositories.find(owner,name).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"repository not onboarded");
        UUID job=indexing.enqueue(owner,name,sha,delivery);
        return Map.of("accepted",true,"jobId",job.toString());
    }
    @PostMapping("/api/repositories/{owner}/{name}/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> reindex(@PathVariable String owner,@PathVariable String name,
                                      org.springframework.security.core.Authentication auth) {
        var repo=repositories.find(owner,name).filter(r->jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE team=? AND repository_id=?",Integer.class,auth.getPrincipal(),r.id())>0).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        UUID job=indexing.enqueue(owner,name,"HEAD","manual-"+repo.id()+"-"+System.nanoTime());return Map.of("accepted",true,"jobId",job.toString());
    }
    private static boolean valid(String provided,byte[] body,String secret)throws Exception {
        if(provided==null||!provided.startsWith("sha256="))return false;
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] expected=mac.doFinal(body);byte[] actual;try{actual=HexFormat.of().parseHex(provided.substring(7));}catch(IllegalArgumentException ex){return false;}
        return MessageDigest.isEqual(expected,actual);
    }
}
