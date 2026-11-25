#!/usr/bin/env bash
# 生成推流素材 + 六种切片参数组合（全部由 ffmpeg 现切，分片时长可用 ffprobe 对账）
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-19.jdk/Contents/Home}
JAVA="$JAVA_HOME/bin/java"
CP="$ROOT/target/classes"

SRC="$ROOT/data/src/source.flv"
VAR="$ROOT/data/variants"
LIVE="$ROOT/data/live"

mkdir -p "$ROOT/data/src" "$VAR" "$LIVE"

echo "== 1/7 生成推流源：40s / 1280x720 / 2Mbps / 关键帧间隔 10s（推流端默认配置）"
if [ ! -f "$SRC" ]; then
  $JAVA -cp "$CP" com.example.volclive.bench.Bench gen "$SRC" 40
else
  echo "   已存在，跳过"
fi

echo "== 2/7 S0 基线：hls_time=6，直接 -c copy 切片（GOP 10s 会绑架分片时长）"
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/720p-hls6-copy" 6 250 2000 copy

echo "== 3/7 S1 只改 hls_time=2，仍然 -c copy（很多人的第一反应）"
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/720p-hls2-copy" 2 250 2000 copy

echo "== 4/7 S2 重新编码 + 强制 IDR 对齐：真正的 2s 分片"
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/720p-2s" 2 50 2000 reencode 1280:720

echo "== 5/7 S4 0.5s 小分片（低延迟模式）"
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/720p-0.5s" 0.5 12 2000 reencode 1280:720

echo "== 6/7 转码阶梯 480p / 360p（同样 2s 对齐）"
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/480p-2s" 2 50 800 reencode 854:480
$JAVA -cp "$CP" com.example.volclive.bench.Bench hls "$SRC" "$VAR/360p-2s" 2 50 400 reencode 640:360

echo "== 7/7 铺一份默认档给边缘节点"
rm -rf "$LIVE/720p"
cp -R "$VAR/720p-2s" "$LIVE/720p"

echo
echo "分片时长对账（ffprobe，真数据）："
for v in 720p-hls6-copy 720p-hls2-copy 720p-2s 720p-0.5s 480p-2s 360p-2s; do
  $JAVA -cp "$CP" com.example.volclive.bench.Bench probe "$VAR/$v" | tail -1
done
