# Cross-repo comparative test report

Reference: MultiRepo-Code-Review-Agent `main` @ `65144c6060e97c491546674586ea8ae5020588c7` (read-only).

## Independence

- No compile/test/runtime dependency on MultiRepo (`ArchitecturalIndependenceTest`).
- Comparative fixtures are independently maintained under this repository.

## Scenarios compared (conceptually)

| Scenario | Ground truth | Platform | Reference | Notes |
|---|---|---|---|---|
| Maven-pinned Java caller of removed method | Impacted | Pass (SharedLibrary IT) | Similar | Platform persists generations |
| Version-upgraded consumer | Not impacted for current dep | Pass | Similar | Explicit exclusion |
| Kotlin ABI removal | Impacted K/J callers | Pass (Kotlin IT) | Weaker prod gate historically | Platform superior on kotlinc ABI |
| REST/Kafka route removal | Contract candidates | Pass (shared IT) | Similar | OpenAPI nested $ref unit-only so far |

## Accuracy claims

Precision/recall vs independent ground truth for a labelled corpus is **not yet** production-certified. Fixture passes are engineering evidence only.

## Next

Port additional independent ground-truth cases; run side-by-side only when both products execute the same normalized inputs.
