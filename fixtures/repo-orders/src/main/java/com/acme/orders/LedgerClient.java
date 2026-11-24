package com.acme.orders;

import org.springframework.web.client.RestTemplate;
import org.springframework.kafka.core.KafkaTemplate;

public class LedgerClient {
    private final RestTemplate http;
    private final KafkaTemplate<String, Object> kafka;
    public LedgerClient(RestTemplate http, KafkaTemplate<String,Object> kafka) { this.http=http; this.kafka=kafka; }
    public Object getEntry(String id) { return http.getForObject("/entries/{id}", Object.class, id); }
    public void publish(Object event) { kafka.send("ledger.entry-created", event); }
}
