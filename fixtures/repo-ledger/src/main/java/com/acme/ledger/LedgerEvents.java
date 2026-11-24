package com.acme.ledger;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerEvents {
    @KafkaListener(topics = "ledger.entry-created")
    public void onEntryCreated(Object event) { }
}
