#!/usr/bin/env bash
# Deletes the local kind cluster and everything in it (incident history included).
set -euo pipefail
kind delete cluster --name cloudpulse
