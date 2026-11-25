#!/usr/bin/env bash
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$ROOT/logs/app.pid" ]; then
  PID=$(cat "$ROOT/logs/app.pid")
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 0.1; done
  fi
  rm -f "$ROOT/logs/app.pid"
fi
echo "stopped"
