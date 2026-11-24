package com.acme.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerController {
    @GetMapping("/entries/{id}")
    public Object getEntry(String id) { return id; }
}
