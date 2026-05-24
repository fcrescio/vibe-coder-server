# vibe-coder-server Helm chart

Self-hostable Android development server orchestrating Claude Code, Gradle,
and Git — packaged for Kubernetes.

> Single-tenant by design (see [CLAUDE.md §1](../../CLAUDE.md)). This chart
> targets the same single-user installation as `docker compose`, not a
> clustered SaaS. `replicas` is locked at 1 and workspace + pg data live on
> ReadWriteOnce PVCs.

## Quick install

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.password=$(openssl rand -hex 24)

# Get logs
kubectl logs deploy/vibe -f

# Port-forward (no ingress)
kubectl port-forward svc/vibe 17880:17880
```

Open <http://localhost:17880> and finish setup (`/setup` admin password).

## External ingress + TLS

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.password=$(openssl rand -hex 24) \
  --set ingress.enabled=true \
  --set ingress.host=vibe.example.com \
  --set ingress.tls.enabled=true \
  --set ingress.tls.secretName=vibe-tls \
  --set env.VIBECODER_CORS_ALLOWED_HOSTS=https://vibe.example.com
```

## External PostgreSQL

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.enabled=false \
  --set env.VIBECODER_DB_HOST=my-pg.example.com \
  --set env.VIBECODER_DB_PORT=5432 \
  --set env.VIBECODER_DB_NAME=vibecoder \
  --set env.VIBECODER_DB_USER=vibecoder \
  --set-string secretEnv.VIBECODER_DB_PASSWORD=$DB_PASSWORD
```

## `:full` image (Android emulator + noVNC, v0.57.0+)

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.password=$(openssl rand -hex 24) \
  --set fullImage.enabled=true
```

This switches the image tag to `:full` (`:0.57.0-full`), mounts
`/dev/kvm` from the node, runs the container `privileged`, and exposes
`port 6080` (noVNC) on the Service. Prerequisites:

- **Node KVM support** — `ls -l /dev/kvm` must succeed on the node
  (Linux with KVM module loaded). Cloud kubernetes typically requires
  bare-metal or nested-virt nodes.
- **PodSecurity policies** must allow `privileged: true` for the
  namespace (`pod-security.kubernetes.io/enforce: privileged`).

The slim image's `/emulator/vnc/*` reverse proxy still works, so
external clients don't usually need to reach port 6080 directly — it's
exposed mostly for direct debug from cluster-internal tools.

## Limitations

- **Single replica only.** workspace + agent-sessions live on RWO PVC.
- **No HA postgres.** Sidecar is single-instance; use external managed PG
  for prod.

## Values reference

See [values.yaml](values.yaml) — every key is documented inline.
