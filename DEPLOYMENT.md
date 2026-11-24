# Deployment guide

## Local Compose

See [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md). Compose uses separate persistent volumes for PostgreSQL/pgvector, Git clones, Zoekt shards, and Ollama. PostgreSQL may be a single container for local development; no cloud cluster is required.

## Production preparation

1. Build and scan the application image in the target's independent Maven project. Push it to the internal registry and pin the resulting digest in the deployment manifest.
2. Provision PostgreSQL 17 with pgvector 0.8.1 or compatible. The application connects as `code_context_app`, which must not be a superuser. Preinstall `vector` and `pgcrypto` as a database administrator; grant the app role `CONNECT` and `USAGE, CREATE` on the application schema so Flyway can create and own its objects. The production profile now refuses to start when the connected role is a superuser. For production availability, use the organization's PostgreSQL HA operator/service, encrypted storage, PITR/WAL backups. A single StatefulSet in the sample is a pilot template only.
3. Provision persistent Git mirror storage, Zoekt index storage, and Ollama model storage. The sample Zoekt PVC uses `ReadWriteMany`; select a storage class that supports that access mode or place the app and searcher on one node with a suitable shared volume.
4. Create `code-context-secrets` through the organization's secret manager integration. Required keys: `database-password` (runtime role), `database-bootstrap-password` (only for the bundled PostgreSQL pilot template), `team-api-keys`, `admin-api-keys`, `webhook-secret`, and `git-access-token`. Keep the bootstrap credential separate from the app credential. Key strings must be 32+ characters. Set `MCP_ALLOWED_ORIGINS` to exact internal HTTPS origins if browser-based clients use MCP.
5. Review `configmap.yaml`, ingress class, domain, network policies, resource budgets, storage classes, and allowed Git hosts. Restrict outbound egress to approved Git host endpoints with the cluster's CNI/firewall; the standard Kubernetes NetworkPolicy here cannot express GitHub FQDNs.
6. Deploy namespace, storage, PostgreSQL, Ollama, model-pull job, Zoekt, then app and ingress. Check health endpoints, migration logs, model presence, webhook HMAC, first repository generation and MCP tool access before connecting teams.

```sh
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/storage.yaml
kubectl apply -f deploy/k8s/postgres-init.yaml
kubectl apply -f deploy/k8s/postgres.yaml
kubectl apply -f deploy/k8s/ollama.yaml
kubectl apply -f deploy/k8s/zoekt.yaml
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/app.yaml
kubectl apply -f deploy/k8s/ingress.yaml
kubectl apply -f deploy/k8s/network-policy.yaml
```

Secrets are intentionally not included as a manifest. Create the namespace secret before the dependent pods start. `app.yaml` stays at one replica until cross-process per-repository lease locks and generation garbage collection receive multi-instance tests. The sample PostgreSQL StatefulSet is also single instance; replace it with an HA operator/service for production.

The PostgreSQL init script runs only when the data directory is empty. For a previously initialized volume, do not delete data to force initialization: have a DBA provision the non-superuser role, extensions, schema privileges, and any required object ownership/grants, then deploy the app using that role. The script is available at `scripts/postgres-init-app-role.sh` for a reviewed, controlled bootstrap operation. Existing superuser-owned tables may need a DBA-led ownership migration before the runtime role can use them.

## Rollout and rollback

Use rolling image changes only after migration compatibility review. Flyway migrations are forward-only in this implementation. To roll back app code, redeploy the previous image and keep all old data generations; do not run destructive schema downgrades. If an app release changes embedding dimensions or model, schedule a re-embedding plan and a new vector schema migration first.
