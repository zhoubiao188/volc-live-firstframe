#!/usr/bin/env bash
# 启动源站(:9090) + 边缘节点(:8080)。出口限速默认 4 Mbps，模拟移动端网络。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-19.jdk/Contents/Home}
JAVA="$JAVA_HOME/bin/java"
THROTTLE=${THROTTLE:-500000}   # B/s，500000 = 4 Mbps
WINDOW=${WINDOW:-6}
LIVE_KEY=${LIVE_KEY:-volc-demo-key}

mkdir -p "$ROOT/logs"
[ -f "$ROOT/logs/app.pid" ] && kill "$(cat "$ROOT/logs/app.pid")" 2>/dev/null || true
rm -f "$ROOT/logs/app.log"

cd "$ROOT"
nohup "$JAVA" -cp "$ROOT/target/classes" com.example.volclive.App \
  --port 8080 --origin-port 9090 --data data/live \
  --throttle "$THROTTLE" --window "$WINDOW" --key "$LIVE_KEY" \
  >> "$ROOT/logs/app.log" 2>&1 &
echo $! > "$ROOT/logs/app.pid"

for _ in $(seq 1 50); do
  if grep -q ready "$ROOT/logs/app.log" 2>/dev/null; then
    cat "$ROOT/logs/app.log"
    exit 0
  fi
  sleep 0.1
done
echo "启动失败，日志："; cat "$ROOT/logs/app.log"; exit 1
