package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GitHubWebhookClientTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final RepositoryRecord repository=new RepositoryRecord(UUID.randomUUID(),"acme","library",
            "https://github.com/acme/library.git","team","main","LIBRARY","/repos/acme/library");
    private final String callback="https://context.example/webhooks/github/acme/library";

    @Test void reconcilesExistingHookAfterCrashRatherThanCreatingDuplicateAndAlwaysRetainsSecret() throws Exception {
        var methods=new ArrayList<String>();
        var client=client((method,uri,body)->{
            methods.add(method);
            assertEquals("api.github.com",uri.getHost());
            if(uri.getPath().equals("/repos/acme/library"))return json.readTree("{\"id\":81,\"full_name\":\"acme/library\"}");
            if(method.equals("GET"))return json.readTree("[{\"id\":7,\"config\":{\"url\":\""+callback+"\"}}]");
            assertEquals("/repos/acme/library/hooks/7",uri.getPath());
            var config=json.readTree(body);
            assertEquals("webhook-secret",config.path("config").path("secret").asText());
            assertEquals("0",config.path("config").path("insecure_ssl").asText());
            assertEquals(List.of("push"),json.convertValue(config.path("events"),List.class));
            return json.readTree("{\"id\":7,\"active\":true,\"events\":[\"push\"],\"config\":{\"url\":\""+callback+"\"}}");
        });
        assertEquals(7,client.reconcile(repository).repositoryHookId());assertEquals(List.of("GET","GET","PATCH"),methods);
    }
    @Test void createsOnlyWhenNoMatchingHookAndRejectsUnconfirmedOrDuplicateProviderResults() throws Exception {
        var methods=new ArrayList<String>();
        var fresh=client((method,uri,body)->{
            methods.add(method);
            if(uri.getPath().equals("/repos/acme/library"))return json.readTree("{\"id\":81,\"full_name\":\"acme/library\"}");
            return json.readTree(method.equals("GET")?"[]":"{\"id\":8,\"active\":true,\"events\":[\"push\"],\"config\":{\"url\":\""+callback+"\"}}");
        });
        assertEquals(8,fresh.reconcile(repository).repositoryHookId());assertEquals(List.of("GET","GET","POST"),methods);
        var unconfirmed=client((method,uri,body)->json.readTree(uri.getPath().equals("/repos/acme/library")?"{\"id\":81,\"full_name\":\"acme/library\"}":method.equals("GET")?"[]":"{\"id\":8,\"active\":false}"));
        assertThrows(IllegalStateException.class,()->unconfirmed.reconcile(repository));
        var duplicate=client((method,uri,body)->json.readTree(uri.getPath().equals("/repos/acme/library")?"{\"id\":81,\"full_name\":\"acme/library\"}":"[{\"id\":7,\"config\":{\"url\":\""+callback+"\"}},{\"id\":8,\"config\":{\"url\":\""+callback+"\"}}]"));
        assertThrows(IllegalStateException.class,()->duplicate.reconcile(repository));
    }
    @Test void rejectsIdentityMismatchAndNeverBorrowsReadOnlyGitCredential() {
        var calls=new ArrayList<String>();
        var wrong=new RepositoryRecord(repository.id(),"other","repo",repository.cloneUrl(),"team","main","LIBRARY","/repos/other");
        var client=client((method,uri,body)->{calls.add(method);return json.readTree("[]");});
        assertThrows(IllegalArgumentException.class,()->client.reconcile(wrong));assertTrue(calls.isEmpty());
        var missing=new GitHubWebhookClient(props(),json,new GitHubWebhookClient.Settings("","https://api.github.com","api.github.com","https://context.example"),
                (method,uri,body)->{fail("must not use Git token for webhook management");return null;},host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
        assertFalse(missing.configured());assertThrows(IllegalStateException.class,()->missing.reconcile(repository));
    }
    @Test void rejectsOversizedChunkedResponseBeforeAccumulatingIt() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var body=new GitHubWebhookClient.LimitedBody();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long count){}public void cancel(){cancelled.set(true);}});
        body.onNext(List.of(java.nio.ByteBuffer.allocate(1_048_576)));
        body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{1})));
        assertTrue(cancelled.get());
        assertThrows(java.util.concurrent.CompletionException.class,()->body.getBody().toCompletableFuture().join());
    }
    @Test void retiresOnlyThePreviouslyOwnedCallbackAndDoesNotInferAbsenceFromRepositoryDenial() throws Exception {
        var methods=new ArrayList<String>();
        var owned=client((method,uri,body)->{
            methods.add(method);
            if(uri.getPath().equals("/repos/acme/library"))return json.readTree("{\"id\":81,\"full_name\":\"acme/library\"}");
            if(method.equals("GET"))return json.readTree("{\"id\":7,\"config\":{\"url\":\""+callback+"\"}}");
            assertEquals("DELETE",method);assertEquals("/repos/acme/library/hooks/7",uri.getPath());return json.readTree("null");
        });
        assertEquals(GitHubWebhookClient.Retirement.DELETED,owned.retireOwnedHook(repository,7,callback,81L,true));
        assertEquals(List.of("GET","GET","DELETE"),methods);
        var otherCallback=client((method,uri,body)->{
            assertNotEquals("DELETE",method,"a different callback must never be deleted");
            return json.readTree(uri.getPath().equals("/repos/acme/library")?"{\"id\":81,\"full_name\":\"acme/library\"}":"{\"id\":7,\"config\":{\"url\":\"https://other.example/hooks\"}}");
        });
        assertThrows(IllegalStateException.class,()->otherCallback.retireOwnedHook(repository,7,callback,81L,true));
        var denied=client((method,uri,body)->{throw new GitHubWebhookClient.ProviderFailure(404);});
        assertThrows(GitHubWebhookClient.ProviderFailure.class,()->denied.retireOwnedHook(repository,7,callback,81L,true),"repository 404 may mean permission denial, not a deleted hook");
        var absent=client((method,uri,body)->{
            if(uri.getPath().equals("/repos/acme/library"))return json.readTree("{\"id\":81,\"full_name\":\"acme/library\"}");
            throw new GitHubWebhookClient.ProviderFailure(404);
        });
        assertEquals(GitHubWebhookClient.Retirement.ABSENT,absent.retireOwnedHook(repository,7,callback,81L,true));
        assertThrows(GitHubWebhookClient.ProviderFailure.class,()->absent.retireOwnedHook(repository,7,callback,81L,false));
    }
    private GitHubWebhookClient client(GitHubWebhookClient.Exchange exchange) {
        return new GitHubWebhookClient(props(),json,new GitHubWebhookClient.Settings("separate-management-token","https://api.github.com","api.github.com","https://context.example"),exchange,
                host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
    }
    private CodeContextProperties props(){return new CodeContextProperties("/repos","/fixtures","/zoekt","t=k","t=a","webhook-secret","read-only-git-token","github.com","https://context.example",1024,1048576,6000,2,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));}
}
