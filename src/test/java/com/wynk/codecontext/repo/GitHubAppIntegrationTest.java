package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitHubAppIntegrationTest {
    @TempDir Path temp;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final String TOKEN="ghs_controlled_fixture_123456789";
    private static final Instant NOW=Instant.parse("2026-11-26T00:00:00Z");
    private static final String INSTALLATION="{\"id\":17,\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"},\"events\":[\"push\"],\"suspended_at\":null}";

    @Test void signsBoundedJwtAndMintsOnlyOneRepositoryReadOnlyCredentialsWithShortCache() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        Path pem=writeKey(key.getPrivate().getEncoded(),temp.resolve("app-key.pem"));
        var clock=new MutableClock();var requests=new AtomicInteger();
        var app=new GitHubAppIntegration(props(),json,settings(pem),clock,(method,uri,credential,body)->{
            requests.incrementAndGet();
            if(uri.getPath().equals("/installation/repositories")) {
                assertEquals(TOKEN,credential);
                return json.readTree("{\"total_count\":1,\"repositories\":[{\"id\":81,\"full_name\":\"acme/library\"}]}");
            }
            String[] jwt=credential.split("\\.");assertEquals(3,jwt.length);
            var claims=json.readTree(Base64.getUrlDecoder().decode(jwt[1]));
            assertEquals("fixture-client-id",claims.path("iss").asText());
            assertEquals(clock.instant().getEpochSecond()-60,claims.path("iat").asLong());
            assertEquals(600,claims.path("exp").asLong()-claims.path("iat").asLong());
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key.getPublic());
            verifier.update((jwt[0]+"."+jwt[1]).getBytes(StandardCharsets.US_ASCII));assertTrue(verifier.verify(Base64.getUrlDecoder().decode(jwt[2])));
            if(method.equals("GET"))return json.readTree(INSTALLATION);
            assertEquals("/app/installations/17/access_tokens",uri.getPath());
            var scope=json.readTree(body);assertEquals(List.of("library"),json.convertValue(scope.path("repository_names"),List.class));
            assertEquals(2,scope.path("permissions").size());assertEquals("read",scope.path("permissions").path("contents").asText());
            return json.readTree(issued("read"));
        },host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
        try {
            assertEquals(TOKEN,app.contentToken("https://github.com/acme/library.git"));
            assertEquals(TOKEN,app.contentToken("https://github.com/acme/library.git"));assertEquals(3,requests.get());
            clock.now=NOW.plusSeconds(301);
            assertEquals(TOKEN,app.contentToken("https://github.com/acme/library.git"));assertEquals(6,requests.get());
            assertFalse(settings(pem).toString().contains(pem.toString()));
        }finally {app.stop();}
    }

    @Test void rejectsOverprivilegedOrWiderTokensAndRepositoryControlledKeys() throws Exception {
        assertThrows(IllegalArgumentException.class,()->GitHubAppIntegration.requireMatchingProvider(java.net.URI.create("https://enterprise.example/acme/library.git"),java.net.URI.create("https://api.github.com")),
                "Enterprise credentials must never be sent to the default public GitHub API");
        assertDoesNotThrow(()->GitHubAppIntegration.requireMatchingProvider(java.net.URI.create("https://enterprise.example/acme/library.git"),java.net.URI.create("https://enterprise.example/api/v3")));
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);byte[] key=generator.generateKeyPair().getPrivate().getEncoded();
        Path pem=writeKey(key,temp.resolve("key.pem"));
        for(boolean wide:List.of(false,true)) {
            var app=app(pem,(method,uri,credential,body)->json.readTree(uri.getPath().equals("/installation/repositories")
                    ?"{\"total_count\":2,\"repositories\":[{\"id\":81,\"full_name\":\"acme/library\"},{\"full_name\":\"acme/secret\"}]}"
                    :method.equals("POST")?issued(wide?"read":"write"):INSTALLATION));
            try {assertThrows(IllegalStateException.class,()->app.contentToken("https://github.com/acme/library.git"));}finally{app.stop();}
        }
        Path repositoryKey=writeKey(key,Files.createDirectories(temp.resolve("sources")).resolve("bad-key.pem"));
        Path alias=temp.resolve("linked-key.pem");Files.createSymbolicLink(alias,repositoryKey);
        var untrusted=app(alias,(method,uri,credential,body)->{fail("repository-controlled key must not authenticate any API call");return null;});
        try {
            assertThrows(IllegalStateException.class,untrusted::validateKeyConfiguration,"startup must reject a repository-controlled App key");
            assertThrows(IllegalStateException.class,()->untrusted.contentToken("https://github.com/acme/library.git"));
        }finally{untrusted.stop();}
    }

    @Test void centralAppChecksInstallationAndCallbackWithoutRepositoryWebhookWrites() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        Path pem=writeKey(generator.generateKeyPair().getPrivate().getEncoded(),temp.resolve("key.pem"));
        var calls=new AtomicInteger();
        var app=app(pem,(method,uri,credential,body)->{
            assertTrue(method.equals("GET")||method.equals("POST")&&uri.getPath().equals("/app/installations/17/access_tokens"),"central App mode must never create a repository hook");calls.incrementAndGet();
            return json.readTree(switch(uri.getPath()) {
                case "/repos/acme/library/installation" -> INSTALLATION;
                case "/app/installations/17/access_tokens" -> issued("read");
                case "/installation/repositories" -> "{\"total_count\":1,\"repositories\":[{\"id\":81,\"full_name\":\"acme/library\"}]}";
                case "/app" -> "{\"events\":[\"push\"],\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}";
                case "/app/hook/config" -> "{\"url\":\"https://context.example/webhooks/github\",\"content_type\":\"json\",\"insecure_ssl\":\"0\",\"secret\":\"********\"}";
                default -> throw new AssertionError("unexpected App request");
            });
        });
        var webhook=new GitHubWebhookClient(props(),json,new GitHubWebhookClient.Settings("independently-configured-hook-token","https://api.github.com","api.github.com","https://context.example"),
                (method,uri,body)->{fail("App mode must not fall back to per-repository hook management");return null;},host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
        webhook.githubApp(app);
        try {
            var result=webhook.reconcile(new RepositoryRecord(UUID.randomUUID(),"acme","library","https://github.com/acme/library.git","team","main","LIBRARY","/repos/acme/library"));
            assertEquals("APP",result.mode());assertNull(result.repositoryHookId());assertEquals(6,calls.get());assertEquals(81,result.providerRepositoryId());
            assertEquals(17L,result.installationId());assertEquals("github.com",result.providerHost());
        }finally{webhook.stop();app.stop();}
    }
    private GitHubAppIntegration app(Path pem,GitHubAppIntegration.Exchange exchange){return new GitHubAppIntegration(props(),json,settings(pem),Clock.fixed(NOW,ZoneOffset.UTC),exchange,
            host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});}
    private GitHubAppIntegration.Settings settings(Path pem){return new GitHubAppIntegration.Settings("fixture-client-id",pem.toString(),"https://api.github.com","api.github.com","https://context.example");}
    private Path writeKey(byte[] bytes,Path path)throws Exception {Files.writeString(path,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END PRIVATE KEY-----\n");return path;}
    private String issued(String permission){return "{\"token\":\""+TOKEN+"\",\"expires_at\":\"2026-11-26T01:00:00Z\",\"permissions\":{\"contents\":\""+permission+"\",\"metadata\":\"read\"}}";}
    private CodeContextProperties props(){return new CodeContextProperties(temp.resolve("repos").toString(),temp.resolve("sources").toString(),temp.resolve("zoekt").toString(),"t=k","t=a","fixture-secret","","github.com","https://context.example",1024,1048576,6000,2,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));}
    private static final class MutableClock extends Clock {
        Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
