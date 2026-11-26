package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.RepositoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
public class IndexWebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexWebhookController.class);
    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    /** Default maximum age for push event timestamps when the payload includes one. */
    static final Duration DEFAULT_MAX_PAYLOAD_AGE = Duration.ofMinutes(15);
    private final IndexingService indexing;
    private final CodeContextProperties config;
    private final JsonMapper json;
    private final RepositoryStore repositories;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration maxPayloadAge;
    private final com.wynk.codecontext.repo.GitHubAppAccess appAccess;

    // Return controlled protocol errors directly. sendError would redispatch a HMAC-authenticated
    // webhook to the API-key-protected /error path and incorrectly replace e.g. 409 with 401.
    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> protocolFailure(ResponseStatusException failure) {
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode()).body(Map.of(
                "status",failure.getStatusCode().value(),"error",failure.getReason()==null?"request rejected":failure.getReason()));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> persistenceFailure(org.springframework.dao.DataAccessException failure) {
        Throwable root=failure;while(root.getCause()!=null)root=root.getCause();
        LOGGER.error("github.webhook.persistence_failed category={} sqlState={}",failure.getClass().getSimpleName(),root instanceof java.sql.SQLException sql?sql.getSQLState():"unknown");
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error","webhook persistence unavailable"));
    }

    @Autowired
    public IndexWebhookController(IndexingService indexing, CodeContextProperties config, JsonMapper json,
                                  RepositoryStore repositories, JdbcTemplate jdbc,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager,
                                  com.wynk.codecontext.repo.GitHubAppAccess appAccess) {
        this(indexing, config, json, repositories, jdbc, transactionManager, Clock.systemUTC(), DEFAULT_MAX_PAYLOAD_AGE,appAccess);
    }

    IndexWebhookController(IndexingService indexing, CodeContextProperties config, JsonMapper json,
                           RepositoryStore repositories, JdbcTemplate jdbc,
                           org.springframework.transaction.PlatformTransactionManager transactionManager,
                           Clock clock, Duration maxPayloadAge,com.wynk.codecontext.repo.GitHubAppAccess appAccess) {
        this.indexing = indexing;
        this.config = config;
        this.json = json;
        this.repositories = repositories;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maxPayloadAge = maxPayloadAge == null ? DEFAULT_MAX_PAYLOAD_AGE : maxPayloadAge;
        this.appAccess=java.util.Objects.requireNonNull(appAccess);
    }

    @PostMapping("/webhooks/github/{owner}/{name}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> github(@PathVariable String owner, @PathVariable String name,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody byte[] body) {
        if (!valid(signature, body, config.webhookSecret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
        }
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "webhook payload exceeds 1 MiB");
        }
        if("ping".equals(event))return ping(delivery);
        if (!"push".equals(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported GitHub webhook event");
        }
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(delivery);
        } catch (RuntimeException invalidDelivery) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid GitHub delivery ID is required");
        }

        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (Exception malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed GitHub push payload");
        }
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub push payload must be an object");
        }
        String sha = payload.path("after").asText("");
        if (!sha.matches("[0-9a-fA-F]{40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub push payload has an invalid commit");
        }
        rejectStalePayload(payload);

        var repository = repositories.find(owner, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repository not onboarded"));
        String payloadRepository = payload.path("repository").path("full_name").asText("");
        if (!payloadRepository.isBlank() && !payloadRepository.equalsIgnoreCase(owner + "/" + name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "push repository does not match webhook route");
        }
        String payloadHash = sha256(body);
        return transactions.execute(status -> {
            boolean fresh=appAccess.claim(deliveryId,"push",payloadHash);
            if(!fresh&&jdbc.queryForList("SELECT delivery_id FROM webhook_deliveries WHERE delivery_id=?",deliveryId).isEmpty())
                return Map.of("accepted",false,"duplicate",true);
            int inserted = jdbc.update("""
                    INSERT INTO webhook_deliveries(delivery_id,repository_id,payload_sha256,signing_key_fingerprint)
                    VALUES(?,?,?,?) ON CONFLICT(delivery_id) DO NOTHING
                    """, deliveryId, repository.id(), payloadHash,sha256(config.webhookSecret().getBytes(StandardCharsets.UTF_8)));
            if (inserted == 0) {
                Delivery previous = jdbc.queryForObject("""
                        SELECT repository_id,payload_sha256 FROM webhook_deliveries WHERE delivery_id=?
                        """, (rs, row) -> new Delivery(rs.getObject(1, UUID.class), rs.getString(2).strip()), deliveryId);
                if (!repository.id().equals(previous.repositoryId) || !payloadHash.equals(previous.payloadHash)) {
                    LOGGER.warn("github.webhook.delivery_replay_mismatch deliveryId={} repository={}", deliveryId, repository.key());
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "delivery ID was already used for a different payload");
                }
                LOGGER.info("github.webhook.duplicate deliveryId={} repository={}", deliveryId, repository.key());
                return Map.of("accepted", false, "duplicate", true);
            }

            if (sha.matches("0{40}")) {
                LOGGER.info("github.webhook.deleted_ref deliveryId={} repository={}", deliveryId, repository.key());
                return Map.of("accepted", false, "reason", "push payload has no commit");
            }
            UUID job = indexing.enqueue(owner, name, sha.toLowerCase(java.util.Locale.ROOT), deliveryId.toString());
            jdbc.update("UPDATE webhook_deliveries SET job_id=? WHERE delivery_id=?", job, deliveryId);
            LOGGER.info("github.webhook.accepted deliveryId={} repository={} jobId={}", deliveryId, repository.key(), job);
            return Map.of("accepted", true, "jobId", job.toString());
        });
    }

    /** Central GitHub App ingress uses the same HMAC, replay and repository authorization checks. */
    @PostMapping("/webhooks/github")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> githubApp(
            @RequestHeader(value="X-Hub-Signature-256",required=false) String signature,
            @RequestHeader(value="X-GitHub-Delivery",required=false) String delivery,
            @RequestHeader(value="X-GitHub-Event",required=false) String event,
            @RequestBody byte[] body) {
        if(!valid(signature,body,config.webhookSecret()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid webhook signature");
        if(body.length>MAX_PAYLOAD_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"webhook payload exceeds 1 MiB");
        if("ping".equals(event))return ping(delivery);
        if(java.util.List.of("installation","installation_repositories").contains(event==null?"":event)) {
            UUID id;
            try {id=UUID.fromString(delivery);}catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"a valid GitHub delivery ID is required");}
            JsonNode payload;
            try {payload=json.readTree(body);}catch(Exception malformed){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"malformed installation payload");}
            return appAccess.receive(id,event,sha256(body),payload);
        }
        String identity;
        try {identity=json.readTree(body).path("repository").path("full_name").asText("");}
        catch(Exception malformed){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"malformed GitHub push payload");}
        if(!identity.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"a valid GitHub repository identity is required");
        var repository=repositories.findProviderIdentity(identity)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"repository not onboarded"));
        return github(repository.owner(),repository.name(),signature,delivery,event,body);
    }
    private Map<String,Object> ping(String delivery) {
        try {UUID.fromString(delivery);}catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"a valid GitHub delivery ID is required");}
        return Map.of("accepted",true,"event","ping");
    }

    @PostMapping("/api/repositories/{owner}/{name}/index")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reindex(@PathVariable String owner, @PathVariable String name,
                                       org.springframework.security.core.Authentication auth) {
        var repo = repositories.find(owner, name).filter(r -> jdbc.queryForObject(
                "SELECT count(*) FROM team_repository_access WHERE team=? AND repository_id=?", Integer.class,
                auth.getPrincipal(), r.id()) > 0).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UUID job = indexing.enqueue(owner, name, "HEAD", "manual-" + repo.id() + "-" + System.nanoTime());
        return Map.of("accepted", true, "jobId", job.toString());
    }

    /**
     * When GitHub includes a trusted event timestamp ({@code head_commit.timestamp} or
     * {@code repository.pushed_at}), reject deliveries older than {@link #maxPayloadAge}.
     * Payloads without a parseable timestamp remain accepted so replay protection still
     * depends on delivery-id idempotency for those events.
     */
    void rejectStalePayload(JsonNode payload) {
        if (maxPayloadAge.isZero() || maxPayloadAge.isNegative()) return;
        Instant eventTime = parseEventTimestamp(payload);
        if (eventTime == null) return;
        Instant cutoff = clock.instant().minus(maxPayloadAge);
        if (eventTime.isBefore(cutoff)) {
            LOGGER.warn("github.webhook.stale_timestamp eventTime={} cutoff={} maxAge={}",
                    eventTime, cutoff, maxPayloadAge);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook event timestamp is too old");
        }
    }

    private static Instant parseEventTimestamp(JsonNode payload) {
        for (String path : new String[] {"head_commit.timestamp", "repository.pushed_at"}) {
            String raw = textAt(payload, path);
            if (raw.isBlank()) continue;
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignored) {
                // GitHub may omit fractional seconds; Instant.parse requires ISO-8601.
            }
            try {
                return Instant.parse(raw.endsWith("Z") ? raw : raw + "Z");
            } catch (DateTimeParseException ignored) {
                LOGGER.debug("github.webhook.unparseable_timestamp path={} value={}", path, raw);
            }
        }
        return null;
    }

    private static String textAt(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String part : dottedPath.split("\\.")) {
            if (current == null || current.isMissingNode()) return "";
            current = current.path(part);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("").strip();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean valid(String provided, byte[] body, String secret) {
        if (provided == null || !provided.startsWith("sha256=") || secret == null || secret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(provided.substring(7));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception invalidSignature) {
            return false;
        }
    }

    record Delivery(UUID repositoryId, String payloadHash) { }
}
