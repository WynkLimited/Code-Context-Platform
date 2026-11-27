# Cross-repository impact architecture

## Product distinction

| | MultiRepo Code Review Agent | Code Context Platform |
|---|---|---|
| Primary job | PR review findings + downstream impact | Continuous reusable code intelligence for MCP agents |
| Trigger | Pull request / change set | Onboarding, webhooks, durable indexing jobs |
| Persistence | Often ephemeral review context | Generations in PostgreSQL + Zoekt + vectors |
| Authority | May gate review | Always advisory (`blocking:false`) |

## Platform pipeline (context creation ≠ query)

1. Onboard / webhook → durable index job  
2. Git mirror sync + exact commit checkout  
3. Zoekt index; Graphify → PG graph; contracts extract  
4. Cross-repo link refresh; embeddings → pgvector  
5. Publish generation  
6. Query-time `impact_analysis` over published generations only  

Avoid reconstructing the full multi-repo dependency graph per MCP request.

## Adopted engineering ideas (independently implemented)

- Maven coordinate routing + consumer match keys  
- Exact JVM call evidence (JavaParser / kotlinc ABI)  
- Version-pinned vs upgraded consumer distinction  
- Direction-aware REST/Kafka schema compatibility  
- Confidence / incompleteness flags instead of false certainty  

## Intentional differences

- Persistent generations and team ACL  
- No LLM as impact authority  
- No shared module with MultiRepo (enforced by `ArchitecturalIndependenceTest`)  

## Remaining gaps

See `docs/CROSS_REPO_IMPACT_PARITY_MATRIX.md` and ledger IDs `XRI-HOP`, `XRI-GRADLE`, `CONTRACT-*`.
