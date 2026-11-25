#!/usr/bin/env bash
# 直播首帧优化 · 全场景实测（客户端视角，全部走真实 HTTP + 真实 ffmpeg 解码）
#
# 前置：./scripts/prepare.sh   （切好六种参数的 HLS）
#       ./scripts/start.sh     （边缘节点出口限速 4 Mbps，模拟移动端）
# 用法：./bench/bench.sh
#
# 场景：
#   S0 基线：hls_time=6 + -c copy，起播缓冲 3 片
#   S1 只把 hls_time 改成 2，仍然 -c copy —— 分片时长纹丝不动
#   S2 重新编码强制 IDR 对齐，真 2s 分片，起播缓冲 3 片
#   S3 起播缓冲从 3 片降到 1 片
#   S4 0.5s 小分片 + 单片起播（低延迟模式）
#   S5 转码阶梯：同样的 2s 分片，720p / 480p / 360p 首帧差多少
#   S6 100 人同时进直播间：不预热 vs 预热，看回源放大
#   S7 拉流鉴权：无签名 / 过期 / 篡改

set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-19.jdk/Contents/Home}
JAVA="$JAVA_HOME/bin/java"
CP="$ROOT/target/classes"
EDGE=${EDGE:-http://127.0.0.1:8080}
VAR="$ROOT/data/variants"
LIVE="$ROOT/data/live"
OUT="$ROOT/logs/bench-result.txt"
KEY=${LIVE_KEY:-volc-demo-key}

mkdir -p "$ROOT/logs"
PLAY="$JAVA -cp $CP com.example.volclive.player.PlayerSim"

hr() { printf '=%.0s' {1..82}; echo; }
m()  { curl -s "$EDGE/metrics"; }
seg_stat() { # 输出 segmentReq/cacheHit/cacheMiss
  m | python3 -c 'import sys,json;d=json.load(sys.stdin);print("%d %d %d"%(d["segmentReq"],d["cacheHit"],d["cacheMiss"]))'
}

# 换一套切片参数上线（等价于控制台改转码模板后刷新边缘缓存）
use_variant() {
  rm -rf "$LIVE/$2"
  cp -R "$VAR/$1" "$LIVE/$2"
  curl -s "$EDGE/admin/reload" > /dev/null
}

{
hr
echo "直播首帧优化 · 火山引擎视频直播 Java 实战压测"
echo "机器：$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)  JDK：$($JAVA -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
echo "ffmpeg：$(ffmpeg -version 2>/dev/null | head -1 | cut -d' ' -f1-3)"
echo "边缘出口限速：4 Mbps（每个连接独立限速，等价于每个观众自己的 4G 带宽）"
echo "首帧 = 请求 playlist + 下载起播缓冲所需分片 + 解码第一个分片首帧"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
hr

# JIT / 磁盘预热，结果不作为数据
use_variant 720p-2s 720p
$PLAY play "$EDGE" 720p 1 warmup > /dev/null

echo
echo "【S0】基线：hls_time=6 + -c copy，起播缓冲 3 片"
use_variant 720p-hls6-copy 720p
$PLAY play "$EDGE" 720p 3 S0

echo
echo "【S1】只改 hls_time=2，仍然 -c copy（分片时长会不会变？）"
use_variant 720p-hls2-copy 720p
$PLAY play "$EDGE" 720p 3 S1

echo
echo "【S2】重新编码 + force_key_frames 强制 IDR，真 2s 分片，起播 3 片"
use_variant 720p-2s 720p
$PLAY play "$EDGE" 720p 3 S2

echo
echo "【S3】起播缓冲 3 片 → 1 片（播放器策略）"
$PLAY play "$EDGE" 720p 1 S3

echo
echo "【S3x3】重复三次看稳定性"
for i in 1 2 3; do $PLAY play "$EDGE" 720p 1 "S3-r$i"; done

echo
echo "【S4】0.5s 小分片 + 单片起播（低延迟模式）"
use_variant 720p-0.5s 720p
$PLAY play "$EDGE" 720p 1 S4

echo
echo "【S5】转码阶梯：同样 2s 分片、单片起播，只换清晰度"
use_variant 720p-2s 720p
use_variant 480p-2s 480p
use_variant 360p-2s 360p
$PLAY play "$EDGE" 720p 1 S5-720p
$PLAY play "$EDGE" 480p 1 S5-480p
$PLAY play "$EDGE" 360p 1 S5-360p

echo
echo "【S6】100 人同时进直播间（0.5s 分片 / 单片起播 / 缓存清空）"
use_variant 720p-0.5s 720p
curl -s "$EDGE/admin/reload" > /dev/null
BEFORE=$(seg_stat)
$PLAY concurrent "$EDGE" 720p 1 100 S6-cold
AFTER=$(seg_stat)
echo "  不预热：分片请求 $(echo "$AFTER" | cut -d' ' -f1-1) 次（本轮 $(($(echo "$AFTER"|cut -d' ' -f1) - $(echo "$BEFORE"|cut -d' ' -f1)))），命中 $(($(echo "$AFTER"|cut -d' ' -f2) - $(echo "$BEFORE"|cut -d' ' -f2)))，回源 $(($(echo "$AFTER"|cut -d' ' -f3) - $(echo "$BEFORE"|cut -d' ' -f3)))"
curl -s "$EDGE/admin/prewarm?quality=720p&count=3" > /dev/null
BEFORE=$(seg_stat)
$PLAY concurrent "$EDGE" 720p 1 100 S6-warm
AFTER=$(seg_stat)
echo "  预热后：分片请求 $(($(echo "$AFTER"|cut -d' ' -f1) - $(echo "$BEFORE"|cut -d' ' -f1))) 次，命中 $(($(echo "$AFTER"|cut -d' ' -f2) - $(echo "$BEFORE"|cut -d' ' -f2)))，回源 $(($(echo "$AFTER"|cut -d' ' -f3) - $(echo "$BEFORE"|cut -d' ' -f3)))"

echo
echo "【S7】拉流鉴权（防盗链）"
P="/live/demo/stream/720p/index.m3u8"
NOW=$(date +%s)
EXP_OK=$((NOW + 3600))
EXP_BAD=$((NOW - 60))
SIGN_OK=$(python3 -c "import hashlib;print(hashlib.md5(('/live/demo/stream.%d.%s'%($EXP_OK,'$KEY')).encode()).hexdigest())")
SIGN_BAD=$(python3 -c "import hashlib;print(hashlib.md5(('/live/demo/stream.%d.%s'%($EXP_OK,'wrong-key')).encode()).hexdigest())")
echo "  无签名                 -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "$EDGE$P")"
echo "  签名正确但已过期        -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "$EDGE$P?expire=$EXP_BAD&sign=$SIGN_OK")"
echo "  签名被篡改（换密钥）    -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "$EDGE$P?expire=$EXP_OK&sign=$SIGN_BAD")"
echo "  签名正确且在有效期      -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "$EDGE$P?expire=$EXP_OK&sign=$SIGN_OK")"

echo
hr
echo "边缘节点指标：$(m)"
hr
} 2>&1 | tee "$OUT"

echo "结果已写入 $OUT"
