#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# compile first — required on a clean checkout (same as GitHub Actions)
exec mvn -q compile exec:java -Dexec.cleanupDaemonThreads=false
