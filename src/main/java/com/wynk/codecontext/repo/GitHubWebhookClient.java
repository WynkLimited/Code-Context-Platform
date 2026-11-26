package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.security.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Uses an independently authorized webhook credential; never falls back to the Git content token. */
@Component
public final class GitHubWebhookClient {
    private final Settings settings;
    private final CodeContextProperties properties;
    private final JsonMapper json;
    private final Exchange exchange;
    private final OutboundUrlGuard.NameResolver resolver;
    private GitHubAppIntegration githubApp;
    @Autowired public void githubApp(GitHubAppIntegration app){this.githubApp=app;}
    public record Hook(String mode,Long repositoryHookId,String callbackUrl,long providerRepositoryId) {}
    public enum Retirement {DELETED,ABSENT}
    static final class ProviderFailure extends java.io.IOException {
        final int status;
        ProviderFailure(int status){super("GitHub webhook management returned HTTP "+status);this.status=status;}
    }
    static final class ProviderIdentityChanged extends IllegalStateException {
        ProviderIdentityChanged(){super("GitHub namespace now identifies a different provider repository");}
    }
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    record Settings(String token,String apiBase,String apiHosts,String callbackBase) {
        @Override public String toString(){return "GitHubWebhookSettings[credentials redacted]";}
    }
    @FunctionalInterface interface Exchange { JsonNode send(String method,URI uri,String body) throws Exception; }

    @Autowired
    public GitHubWebhookClient(CodeContextProperties properties,JsonMapper json,
            @Value("${GITHUB_WEBHOOK_TOKEN:}") String token,
            @Value("${GITHUB_API_BASE_URL:https://api.github.com}") String apiBase,
            @Value("${GITHUB_API_ALLOWED_HOSTS:api.github.com}") String apiHosts,
            @Value("${GITHUB_WEBHOOK_BASE_URL:}") String callbackBase) {
        this(properties,json,new Settings(token,apiBase,apiHosts,callbackBase),null,java.net.InetAddress::getAllByName);
    }
    GitHubWebhookClient(CodeContextProperties properties,JsonMapper json,Settings settings,Exchange exchange,OutboundUrlGuard.NameResolver resolver) {
        this.properties=properties;this.json=json;this.settings=settings;
        this.exchange=exchange==null?this::http:exchange;
        this.resolver=resolver;
    }
    boolean configured(){return githubApp!=null&&githubApp.enabled()?githubApp.webhookConfigured():!settings.token().isBlank()&&!settings.callbackBase().isBlank();}
    String mode(){return githubApp!=null&&githubApp.enabled()?"APP":"REPOSITORY";}
    String configurationHash(RepositoryRecord repository) {
        return digest(mode()+"\n"+settings.apiBase()+"\n"+settings.callbackBase()+"\n"+properties.webhookSecret()
                +"\n"+repository.key()+"\n"+repository.cloneUrl()+"\n"+(githubApp!=null&&githubApp.enabled()?githubApp.configurationIdentity():""));
    }
    boolean repositoryManagementConfigured(){return !settings.token().isBlank();}
    String expectedRepositoryCallback(RepositoryRecord repository){return callback(repository);}
    String signingKeyFingerprint(){return digest(properties.webhookSecret());}
    private static String digest(String value) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    public Hook reconcile(RepositoryRecord repository) throws Exception {
        return reconcile(repository,null);
    }
    Hook reconcile(RepositoryRecord repository,Long expectedProviderId) throws Exception {
        if(!configured())throw new IllegalStateException("Separate webhook-management credential and HTTPS callback URL are required");
        if(githubApp!=null&&githubApp.enabled()) {
            long repositoryId=githubApp.verifyCentralWebhook(repository);
            requireProviderId(expectedProviderId,repositoryId);
            return new Hook("APP",null,githubApp.callbackUrl(),repositoryId);
        }
        URI clone=URI.create(repository.cloneUrl());
        GitHubAppIntegration.requireMatchingProvider(clone,URI.create(settings.apiBase()));
        String path=clone.getPath().replaceFirst("\\.git$","");
        if(!path.equalsIgnoreCase("/"+repository.key()))throw new IllegalArgumentException("Git provider identity differs from the registered repository");
        String callback=callback(repository);
        String base=settings.apiBase().replaceAll("/+$","")+"/repos/"+repository.key()+"/hooks";
        var identity=exchange.send("GET",approved(base.substring(0,base.length()-"/hooks".length())),null);
        long providerRepository=identity.path("id").asLong();
        if(providerRepository<=0||!repository.key().equalsIgnoreCase(identity.path("full_name").asText()))
            throw new IllegalStateException("GitHub did not confirm the registered repository identity");
        requireProviderId(expectedProviderId,providerRepository);
        Long existing=null;
        for(int page=1;page<=10;page++) {
            JsonNode hooks=exchange.send("GET",approved(base+"?per_page=100&page="+page),null);
            if(!hooks.isArray())throw new IllegalStateException("GitHub returned an invalid webhook list");
            for(JsonNode hook:hooks)if(callback.equals(hook.path("config").path("url").asText())) {
                if(existing!=null)throw new IllegalStateException("Multiple matching webhooks require operator reconciliation");
                existing=hook.path("id").asLong();
                if(existing<=0)throw new IllegalStateException("GitHub returned an invalid webhook identity");
            }
            if(hooks.size()<100)break;
            if(page==10)throw new IllegalStateException("Webhook enumeration exceeded its bounded page limit");
        }
        String body=json.writeValueAsString(Map.of("name","web","active",true,"events",List.of("push"),
                "config",Map.of("url",callback,"content_type","json","insecure_ssl","0","secret",properties.webhookSecret())));
        JsonNode result=exchange.send(existing==null?"POST":"PATCH",approved(base+(existing==null?"":"/"+existing)),body);
        long id=result.path("id").asLong();
        if(id<=0||existing!=null&&id!=existing||!result.path("active").asBoolean()||!callback.equals(result.path("config").path("url").asText())
                ||!result.path("events").isArray()||!hasPush(result.path("events")))
            throw new IllegalStateException("GitHub did not confirm the active push webhook");
        return new Hook("REPOSITORY",id,callback,providerRepository);
    }
    private static void requireProviderId(Long expected,long actual) {
        if(expected!=null&&expected!=actual)throw new ProviderIdentityChanged();
    }
    Retirement retireOwnedHook(RepositoryRecord repository,long hookId,String expectedCallback,Long providerRepositoryId,boolean allowAbsent)throws Exception {
        if(!repositoryManagementConfigured()||expectedCallback==null||expectedCallback.isBlank())
            throw new IllegalStateException("separately authorized management and prior callback provenance are required");
        GitHubAppIntegration.requireMatchingProvider(URI.create(repository.cloneUrl()),URI.create(settings.apiBase()));
        String base=settings.apiBase().replaceAll("/+$","")+"/repos/"+repository.key();
        var accessible=exchange.send("GET",approved(base),null);
        if(!repository.key().equalsIgnoreCase(accessible.path("full_name").asText()))
            throw new IllegalStateException("provider repository ownership could not be confirmed");
        if(providerRepositoryId!=null&&providerRepositoryId!=accessible.path("id").asLong())
            throw new ProviderIdentityChanged();
        JsonNode hook;
        try {hook=exchange.send("GET",approved(base+"/hooks/"+hookId),null);}
        catch(ProviderFailure absent){if(absent.status==404&&allowAbsent&&providerRepositoryId!=null)return Retirement.ABSENT;throw absent;}
        if(hook.path("id").asLong()!=hookId||!expectedCallback.equals(hook.path("config").path("url").asText()))
            throw new IllegalStateException("refusing to delete a webhook with different ownership or callback");
        exchange.send("DELETE",approved(base+"/hooks/"+hookId),null);return Retirement.DELETED;
    }
    private static boolean hasPush(JsonNode events) {for(JsonNode event:events)if("push".equals(event.asText()))return true;return false;}
    private String callback(RepositoryRecord repository) {
        URI base=URI.create(settings.callbackBase());
        if(!"https".equals(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)
            throw new IllegalArgumentException("GitHub webhook callback must be an HTTPS URL without credentials, query or fragment");
        return settings.callbackBase().replaceAll("/+$","")+"/webhooks/github/"+repository.key();
    }
    private URI approved(String address) {
        OutboundUrlGuard.requireAllowlistedHttps(settings.apiBase(),List.of(settings.apiHosts().split(",")),resolver);
        URI uri=URI.create(address);
        // Queries are constructed here, never accepted from provider pagination links.
        String withoutQuery=uri.getScheme()+"://"+uri.getAuthority()+uri.getPath();
        OutboundUrlGuard.requireAllowlistedHttps(withoutQuery,List.of(settings.apiHosts().split(",")),resolver);
        return uri;
    }
    private JsonNode http(String method,URI uri,String body) throws Exception {
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+settings.token()).header("Accept","application/vnd.github+json")
                .header("X-GitHub-Api-Version","2022-11-28");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        if(body!=null)request.header("Content-Type","application/json");
        var response=client.sendAsync(request.build(),info->new LimitedBody());
        try {
            var result=response.get(35,java.util.concurrent.TimeUnit.SECONDS);
            if(result.statusCode()<200||result.statusCode()>=300)
                throw new ProviderFailure(result.statusCode());
            return result.body().length==0?json.readTree("null"):json.readTree(result.body());
        } finally {response.cancel(true);}
    }
    @jakarta.annotation.PreDestroy public void stop(){client.shutdownNow();}
    /** Enforce a response-body deadline and memory cap, including chunked/slow provider responses. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>1_048_576-bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new IllegalStateException("GitHub webhook response exceeds 1 MiB"));return;
                }
                byte[] next=new byte[buffer.remaining()];buffer.get(next);bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
