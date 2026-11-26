package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexWebhookControllerTest {
    private static final String SECRET = "webhook-secret-with-at-least-32-characters";
    private static final UUID REPOSITORY_ID = UUID.fromString("92c67417-3306-44b6-9b8b-11f736345ebc");
    private static final UUID JOB_ID = UUID.fromString("8325b6f0-f1b5-40e4-99db-806e8cfd7cf4");

    @Test
    void rejectsBadSignatureBeforeDatabaseOrIndexing() {
        var fixture = fixture();
        byte[] body = payload("1".repeat(40));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> fixture.controller.github("Fixture", "lib", "sha256=00", "adf3f391-cfa6-4f27-8c4c-467f1b865ab5", "push", body));

        assertEquals(401, failure.getStatusCode().value());
        assertEquals(0, fixture.jdbc.insertCount);
        assertEquals(0, fixture.indexing.enqueueCount);
    }

    @Test
    void persistsDeliveryAndEnqueuesExactlyOnceAcrossWebhookReplay() throws Exception {
        var fixture = fixture();
        UUID deliveryId = UUID.fromString("adf3f391-cfa6-4f27-8c4c-467f1b865ab5");
        byte[] body = payload("1".repeat(40));
        String signature = signature(body);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        var first = fixture.controller.github("Fixture", "lib", signature, deliveryId.toString(), "push", body);
        var replay = fixture.controller.github("Fixture", "lib", signature, deliveryId.toString(), "push", body);

        assertTrue((Boolean) first.get("accepted"));
        assertEquals(JOB_ID.toString(), first.get("jobId"));
        assertFalse((Boolean) replay.get("accepted"));
        assertTrue((Boolean) replay.get("duplicate"));
        assertEquals(1, fixture.indexing.enqueueCount);
        assertEquals(new IndexWebhookController.Delivery(REPOSITORY_ID, hash), fixture.jdbc.deliveries.get(deliveryId));
    }

    @Test
    void rejectsStaleHeadCommitTimestampBeforeEnqueue() throws Exception {
        Instant now = Instant.parse("2026-11-25T14:00:00Z");
        var fixture = fixture(Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15));
        byte[] body = payload("1".repeat(40), "2026-11-25T13:00:00Z");
        String signature = signature(body);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> fixture.controller.github("Fixture", "lib", signature,
                        "bdf3f391-cfa6-4f27-8c4c-467f1b865ab6", "push", body));

        assertEquals(400, failure.getStatusCode().value());
        assertEquals(0, fixture.indexing.enqueueCount);
        assertEquals(0, fixture.jdbc.insertCount);
    }

    @Test
    void acceptsFreshHeadCommitTimestamp() throws Exception {
        Instant now = Instant.parse("2026-11-25T14:00:00Z");
        var fixture = fixture(Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15));
        byte[] body = payload("1".repeat(40), "2026-11-25T13:55:00Z");
        String signature = signature(body);

        var accepted = fixture.controller.github("Fixture", "lib", signature,
                "cdf3f391-cfa6-4f27-8c4c-467f1b865ab7", "push", body);

        assertTrue((Boolean) accepted.get("accepted"));
        assertEquals(1, fixture.indexing.enqueueCount);
    }

    @Test void centralAppIngressUsesIdenticalSignatureAndReplayChecks() throws Exception {
        var fixture=fixture();byte[] body=payload("1".repeat(40));String delivery=UUID.randomUUID().toString();
        var rejected=assertThrows(ResponseStatusException.class,()->fixture.controller.githubApp("sha256=00",delivery,"push",body));
        assertEquals(401,rejected.getStatusCode().value());assertEquals(0,fixture.indexing.enqueueCount);
        assertTrue((Boolean)fixture.controller.githubApp(signature(body),delivery,"push",body).get("accepted"));
        assertTrue((Boolean)fixture.controller.github("Fixture","lib",signature(body),delivery,"push",body).get("duplicate"));
        assertEquals(1,fixture.indexing.enqueueCount,"central and per-repository routes must share durable delivery deduplication");
    }
    @Test void signedAppPingNeedsNoRepositoryAndNeverSchedulesIndexing() throws Exception {
        var fixture=fixture();byte[] body="{\"zen\":\"controlled fixture\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("ping",fixture.controller.githubApp(signature(body),UUID.randomUUID().toString(),"ping",body).get("event"));
        assertEquals(0,fixture.indexing.enqueueCount);assertEquals(0,fixture.jdbc.insertCount);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->fixture.controller.githubApp("sha256=00",UUID.randomUUID().toString(),"ping",body)).getStatusCode().value());
    }

    private static Fixture fixture() {
        return fixture(Clock.systemUTC(), Duration.ZERO);
    }

    private static Fixture fixture(Clock clock, Duration maxPayloadAge) {
        var jdbc = new WebhookJdbc();
        var repository = new RepositoryRecord(REPOSITORY_ID, "Fixture", "lib", "https://github.com/Fixture/lib.git", "team-a", "main", "LIBRARY", "/repos/Fixture/lib");
        RepositoryStore repositories = new RepositoryStore(jdbc) {
            @Override public Optional<RepositoryRecord> find(String owner, String name) {
                return "Fixture".equals(owner) && "lib".equals(name) ? Optional.of(repository) : Optional.empty();
            }
            @Override public Optional<RepositoryRecord> findProviderIdentity(String fullName) {
                return "Fixture/lib".equalsIgnoreCase(fullName)?Optional.of(repository):Optional.empty();
            }
        };
        CodeContextProperties properties = properties();
        PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
        WebhookIndexing indexing = new WebhookIndexing(repositories, properties, transactionManager);
        return new Fixture(new IndexWebhookController(indexing, properties, JsonMapper.builder().build(),
                repositories, jdbc, transactionManager, clock, maxPayloadAge), indexing, jdbc);
    }

    private static CodeContextProperties properties() {
        return new CodeContextProperties("/repos", "/git", "/zoekt", "", "", SECRET, "", "github.com",
                "https://mcp.example", 1024, 1_048_576, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));
    }

    private static byte[] payload(String sha) {
        return payload(sha, null);
    }

    private static byte[] payload(String sha, String headCommitTimestamp) {
        String head = headCommitTimestamp == null ? ""
                : ",\"head_commit\":{\"timestamp\":\"" + headCommitTimestamp + "\"}";
        return ("{\"after\":\"" + sha + "\",\"repository\":{\"full_name\":\"Fixture/lib\"}" + head + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private record Fixture(IndexWebhookController controller, WebhookIndexing indexing, WebhookJdbc jdbc) { }

    private static final class WebhookIndexing extends IndexingService {
        private int enqueueCount;
        private WebhookIndexing(RepositoryStore repositories, CodeContextProperties properties,
                                PlatformTransactionManager transactionManager) {
            super(null, repositories, null, properties, null, null, null, transactionManager,
                    null, null, null, null, null, null);
        }
        @Override public UUID enqueue(String owner, String name, String sha, String delivery) {
            enqueueCount++;
            return JOB_ID;
        }
    }

    private static final class WebhookJdbc extends JdbcTemplate {
        private final java.util.Map<UUID, IndexWebhookController.Delivery> deliveries = new java.util.HashMap<>();
        private int insertCount;

        @Override public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO webhook_deliveries")) {
                insertCount++;
                UUID delivery = (UUID) args[0];
                if (deliveries.containsKey(delivery)) return 0;
                deliveries.put(delivery, new IndexWebhookController.Delivery((UUID) args[1], (String) args[2]));
                return 1;
            }
            return 1;
        }

        @Override @SuppressWarnings("unchecked") public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            throw new UnsupportedOperationException("unexpected scalar query: " + sql);
        }

        @Override @SuppressWarnings("unchecked") public <T> T queryForObject(String sql,
                org.springframework.jdbc.core.RowMapper<T> rowMapper, Object... args) {
            IndexWebhookController.Delivery delivery = deliveries.get((UUID) args[0]);
            if (delivery == null) throw new EmptyResultDataAccessException(1);
            return (T) delivery;
        }
    }
}
