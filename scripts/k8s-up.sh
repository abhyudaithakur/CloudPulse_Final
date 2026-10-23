#!/usr/bin/env bash
# Starts CloudPulse in Kubernetes mode on a local kind cluster (Linux/macOS, and CI).
#   ./scripts/k8s-up.sh            build images, create the cluster if needed, deploy, wait
# Then open http://localhost:30080 (dashboard) and http://localhost:30090 (Prometheus).
# Stop with ./scripts/k8s-down.sh. Windows: scripts\k8s-up.ps1.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=cloudpulse
IMAGES=(cloudpulse/api:local cloudpulse/demo-service:local cloudpulse/traffic-generator:local cloudpulse/dashboard:local)

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing '$1'. $2" >&2; exit 1; }; }
need docker "Install Docker and start it."
need kind "Install kind: https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
need kubectl "Install kubectl: https://kubernetes.io/docs/tasks/tools/"

echo "==> Building images (the same ones Docker Compose uses)"
docker compose build api checkout-api traffic-generator dashboard

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "==> Creating kind cluster '$CLUSTER'"
  kind create cluster --config deploy/kind/cluster.yaml --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "==> Loading images into the cluster"
kind load docker-image --name "$CLUSTER" "${IMAGES[@]}"

echo "==> Deploying"
kubectl apply -k deploy/k8s
# A rebuilt image keeps its :local tag, so restart to pick it up (no-op on first deploy).
kubectl -n cloudpulse-demo rollout restart deployment/checkout-api deployment/payment-api deployment/traffic-generator >/dev/null
kubectl -n cloudpulse rollout restart deployment/cloudpulse-api deployment/dashboard >/dev/null

echo "==> Waiting for everything to be ready (first start takes 1-3 minutes)"
kubectl -n cloudpulse rollout status statefulset/postgres --timeout=300s
for d in prometheus cloudpulse-api dashboard; do
  kubectl -n cloudpulse rollout status "deployment/$d" --timeout=300s
done
for d in payment-api checkout-api traffic-generator; do
  kubectl -n cloudpulse-demo rollout status "deployment/$d" --timeout=300s
done

cat <<'MSG'

CloudPulse is running in Kubernetes mode.
  Dashboard   http://localhost:30080
  Prometheus  http://localhost:30090

Try the guarded restart: Overview -> Inject a fault -> payment-api -> Stuck, wait for the
incident, open it, and approve "Restart payment-api".
Check what CloudPulse is allowed to change:
  kubectl auth can-i --list -n cloudpulse-demo --as=system:serviceaccount:cloudpulse:cloudpulse-api
Stop: ./scripts/k8s-down.sh
MSG
