#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
prefix="${IMAGE_PREFIX:-otel-demo}"
tag="${IMAGE_TAG:-1.0.0}"
for service in order-service inventory-service; do
  docker build -f deployment/Dockerfile --build-arg "SERVICE=$service" -t "$prefix/$service:$tag" .
done
docker build -f deployment/Dockerfile.frontend -t "$prefix/frontend:$tag" .
