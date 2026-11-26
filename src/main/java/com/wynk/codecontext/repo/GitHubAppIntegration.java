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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** App JWTs stay server-side. Git credentials are scoped to one repository and read-only. */
@Component
public final class GitHubAppIntegration {
    record Settings(String clientId,String privateKeyFile,String apiBase,String apiHosts,String callbackBase) {
        @Override public String toString(){return "GitHubAppSettings[credentials redacted]";}
    }
    @FunctionalInterface interface Exchange {JsonNode send(String method,URI address,String credential,String body)throws Exception;}
    private record Credential(String token,Instant expires) {
        @Override public String toString(){return "GitHubAppCredential[redacted]";}
    }
    private final Settings settings;
    private final CodeContextProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final Exchange exchange;
    private final OutboundUrlGuard.NameResolver resolver;
    private final Map<String,Credential> tokens=new java.util.concurrent.ConcurrentHashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public GitHubAppIntegration(CodeContextProperties properties,JsonMapper json,
            @Value("${GITHUB_APP_CLIENT_ID:${GITHUB_APP_ID:}}") String clientId,
            @Value("${GITHUB_APP_PRIVATE_KEY_FILE:}") String keyFile,
            @Value("${GITHUB_API_BASE_URL:https://api.github.com}") String apiBase,
            @Value("${GITHUB_API_ALLOWED_HOSTS:api.github.com}") String apiHosts,
            @Value("${GITHUB_WEBHOOK_BASE_URL:}") String callbackBase) {
        this(properties,json,new Settings(clientId,keyFile,apiBase,apiHosts,callbackBase),Clock.systemUTC(),null,java.net.InetAddress::getAllByName);
    }
    GitHubAppIntegration(CodeContextProperties properties,JsonMapper json,Settings settings,Clock clock,
            Exchange exchange,OutboundUrlGuard.NameResolver resolver) {
        this.properties=properties;this.json=json;this.settings=settings;this.clock=clock;
        this.exchange=exchange==null?this::http:exchange;this.resolver=resolver;
    }
    public boolean enabled(){return !settings.clientId().isBlank()||!settings.privateKeyFile().isBlank();}
    public boolean configured(){return !settings.clientId().isBlank()&&!settings.privateKeyFile().isBlank();}
    boolean webhookConfigured(){return configured()&&!settings.callbackBase().isBlank();}
    @jakarta.annotation.PostConstruct public void validateKeyConfiguration() {
        if(!enabled())return;
        try {jwt();}catch(Exception invalid){throw new IllegalStateException("GitHub App identifier and mounted RSA PKCS8 key configuration are invalid");}
    }

    public synchronized String contentToken(String cloneUrl) {
        try {
            requireConfigured();String repository=repository(cloneUrl);
            Instant now=clock.instant();tokens.values().removeIf(token->!token.expires().isAfter(now));
            var cached=tokens.get(repository);if(cached!=null)return cached.token();
            if(tokens.size()>=512)throw new IllegalStateException("GitHub App credential cache capacity reached");
            String jwt=jwt();
            var installation=installation(repository,jwt);
            String body=json.writeValueAsString(Map.of("repository_names",List.of(repository.split("/",2)[1]),
                    "permissions",Map.of("contents","read","metadata","read")));
            var issued=call("POST","/app/installations/"+installation+"/access_tokens",jwt,body);
            requireReadOnly(issued.path("permissions"));
            String token=issued.path("token").asText("");
            if(token.length()<20||token.length()>4096||token.chars().anyMatch(c->Character.isISOControl(c)||Character.isWhitespace(c)))
                throw new IllegalStateException("GitHub returned an invalid installation credential");
            Instant expiry=Instant.parse(issued.path("expires_at").asText());
            if(!expiry.isAfter(now.plusSeconds(60)))throw new IllegalStateException("GitHub installation credential is already expiring");
            var scope=call("GET","/installation/repositories?per_page=2",token,null);
            if(scope.path("total_count").asInt()!=1||!scope.path("repositories").isArray()||scope.path("repositories").size()!=1
                    ||!repository.equalsIgnoreCase(scope.path("repositories").get(0).path("full_name").asText()))
                throw new IllegalStateException("GitHub installation credential is not restricted to the requested repository");
            Instant until=expiry.minusSeconds(60).isBefore(now.plusSeconds(300))?expiry.minusSeconds(60):now.plusSeconds(300);
            tokens.put(repository,new Credential(token,until));return token;
        }catch(Exception failure){throw new IllegalStateException("GitHub App read-only repository authorization failed ("+failure.getClass().getSimpleName()+")");}
    }
    public void verifyCentralWebhook(RepositoryRecord record)throws Exception {
        requireConfigured();String repository=repository(record.cloneUrl());
        if(!repository.equalsIgnoreCase(record.key()))throw new IllegalArgumentException("GitHub App repository identity differs from registration");
        String jwt=jwt();installation(repository,jwt);
        var app=call("GET","/app",jwt,null);
        if(!hasPush(app.path("events")))throw new IllegalStateException("GitHub App must subscribe to push events");
        requireReadOnly(app.path("permissions"));
        var hook=call("GET","/app/hook/config",jwt,null);
        URI callback=URI.create(settings.callbackBase());
        if(!"https".equals(callback.getScheme())||callback.getHost()==null||callback.getUserInfo()!=null||callback.getQuery()!=null||callback.getFragment()!=null)
            throw new IllegalArgumentException("GitHub App callback base must be HTTPS without credentials, query or fragment");
        String expected=settings.callbackBase().replaceAll("/+$","")+"/webhooks/github";
        if(!expected.equals(hook.path("url").asText())||!"json".equals(hook.path("content_type").asText())
                ||!"0".equals(hook.path("insecure_ssl").asText())||hook.path("secret").asText("").isBlank())
            throw new IllegalStateException("GitHub App central webhook configuration needs operator correction");
        // The provider masks its secret. Only a fresh HMAC-verified delivery can prove key agreement.
    }
    private long installation(String repository,String jwt)throws Exception {
        var installation=call("GET","/repos/"+repository+"/installation",jwt,null);
        long id=installation.path("id").asLong();
        if(id<=0||!installation.path("suspended_at").isMissingNode()&&!installation.path("suspended_at").isNull())
            throw new IllegalStateException("GitHub App installation is missing or suspended");
        if(!"read".equals(installation.path("permissions").path("contents").asText()))
            throw new IllegalStateException("GitHub App installation must have read-only content permission");
        if(!hasPush(installation.path("events")))throw new IllegalStateException("GitHub App installation does not subscribe to push events");
        return id;
    }
    private static boolean hasPush(JsonNode events){for(var event:events)if("push".equals(event.asText()))return true;return false;}
    private static void requireReadOnly(JsonNode permissions) {
        if(!"read".equals(permissions.path("contents").asText())||!"read".equals(permissions.path("metadata").asText()))
            throw new IllegalStateException("GitHub App must grant read-only contents and metadata");
        for(var permission:permissions.properties())if(!List.of("contents","metadata").contains(permission.getKey())
                &&!"none".equals(permission.getValue().asText()))
            throw new IllegalStateException("GitHub App has permissions outside the approved read-only content scope");
    }
    private String repository(String cloneUrl) {
        var uri=OutboundUrlGuard.requireAllowlistedHttps(cloneUrl,List.of(properties.gitAllowedHosts().split(",")),resolver);
        requireMatchingProvider(uri,URI.create(settings.apiBase()));
        String path=uri.getPath().replaceFirst("\\.git$","");
        if(!path.matches("/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("invalid GitHub repository path");
        return path.substring(1);
    }
    static void requireMatchingProvider(URI clone,URI api) {
        String expected="api.github.com".equalsIgnoreCase(api.getHost())?"github.com":api.getHost();
        if(!"https".equalsIgnoreCase(clone.getScheme())||clone.getHost()==null||expected==null||!expected.equalsIgnoreCase(clone.getHost()))
            throw new IllegalArgumentException("Git clone host does not match the approved GitHub API provider");
    }
    private void requireConfigured(){if(!configured())throw new IllegalStateException("GitHub App identifier and mounted private key are required");}
    String jwt()throws Exception {
        requireConfigured();Path path=Path.of(settings.privateKeyFile()).toRealPath();
        for(String root:List.of(properties.repositoryRoot(),properties.localGitRoot())) {
            Path directory=Path.of(root).toAbsolutePath().normalize();if(Files.exists(directory))directory=directory.toRealPath();
            if(path.startsWith(directory))throw new IllegalStateException("GitHub App private key must not come from repository source storage");
        }
        byte[] pem;
        try(var input=Files.newInputStream(path)){pem=input.readNBytes(65_537);}
        if(pem.length>65_536)throw new IllegalStateException("GitHub App key exceeds its bounded size");
        String key=new String(pem,StandardCharsets.US_ASCII);
        if(!key.startsWith("-----BEGIN PRIVATE KEY-----"))throw new IllegalStateException("GitHub App requires an RSA PKCS8 PEM private key");
        byte[] der=Base64.getMimeDecoder().decode(key.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").strip());
        var privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        if(((java.security.interfaces.RSAKey)privateKey).getModulus().bitLength()<2048)
            throw new IllegalStateException("GitHub App RSA private key must be at least 2048 bits");
        var encoder=Base64.getUrlEncoder().withoutPadding();long now=clock.instant().getEpochSecond();
        String header=encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII));
        String claims=encoder.encodeToString(json.writeValueAsBytes(Map.of("iss",settings.clientId(),"iat",now-60,"exp",now+540)));
        String payload=header+"."+claims;var signer=Signature.getInstance("SHA256withRSA");signer.initSign(privateKey);signer.update(payload.getBytes(StandardCharsets.US_ASCII));
        return payload+"."+encoder.encodeToString(signer.sign());
    }
    private JsonNode call(String method,String route,String credential,String body)throws Exception {
        var base=OutboundUrlGuard.requireAllowlistedHttps(settings.apiBase(),List.of(settings.apiHosts().split(",")),resolver);
        URI uri=URI.create(base.toString().replaceAll("/+$","")+route);
        OutboundUrlGuard.requireAllowlistedHttps(uri.getScheme()+"://"+uri.getAuthority()+uri.getPath(),List.of(settings.apiHosts().split(",")),resolver);
        return exchange.send(method,uri,credential,body);
    }
    private JsonNode http(String method,URI address,String credential,String body)throws Exception {
        var request=HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+credential)
                .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        if(body!=null)request.header("Content-Type","application/json");
        var pending=http.sendAsync(request.build(),info->new GitHubWebhookClient.LimitedBody());
        try {
            var response=pending.get(35,java.util.concurrent.TimeUnit.SECONDS);
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("GitHub App API returned HTTP "+response.statusCode());
            return json.readTree(response.body());
        }finally {pending.cancel(true);}
    }
    @jakarta.annotation.PreDestroy public void stop(){http.shutdownNow();tokens.clear();}
}
