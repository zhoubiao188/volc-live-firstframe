package com.example.volclive.player;

import com.example.volclive.live.LiveAuth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 播放器起播模拟：完全按真实播放器的顺序走网络，不做任何内部方法调用。
 *
 * 首帧 = 请求 playlist + 下载「起播缓冲」所需的 N 个分片 + 解码第一个分片的首帧。
 * 三段分别计时，方便定位瓶颈到底在协议、在带宽，还是在解码。
 */
public final class PlayerSim {

    private static final String BASE = "/live/demo/stream";
    private static final String KEY = System.getenv().getOrDefault("LIVE_KEY", "volc-demo-key");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: play|concurrent ...");
            System.exit(2);
        }
        PlayerSim p = new PlayerSim();
        switch (args[0]) {
            case "play" -> {
                String base = args[1];
                String quality = args[2];
                int startup = Integer.parseInt(args[3]);
                String label = args[4];
                Result r = p.playOnce(base, quality, startup);
                System.out.println(r.toJson(label));
            }
            case "concurrent" -> {
                String base = args[1];
                String quality = args[2];
                int startup = Integer.parseInt(args[3]);
                int threads = Integer.parseInt(args[4]);
                String label = args[5];
                p.runConcurrent(base, quality, startup, threads, label);
            }
            default -> {
                System.err.println("unknown cmd " + args[0]);
                System.exit(2);
            }
        }
    }

    public static final class Result {
        public long playlistMs;
        public long downloadMs;
        public long decodeMs;
        public long firstFrameMs;
        public int segments;
        public int startIndex;
        public long bytes;
        public double segDuration;
        public int status;

        public String toJson(String label) {
            return String.format(Locale.ROOT,
                    "{\"label\":\"%s\",\"status\":%d,\"playlistMs\":%d,\"downloadMs\":%d,"
                            + "\"decodeMs\":%d,\"firstFrameMs\":%d,\"segments\":%d,\"startIndex\":%d,"
                            + "\"bytes\":%d,\"segDuration\":%.2f}",
                    label, status, playlistMs, downloadMs, decodeMs, firstFrameMs,
                    segments, startIndex, bytes, segDuration);
        }
    }

    public Result playOnce(String edgeBase, String quality, int startup) throws Exception {
        long t0 = System.nanoTime();
        Result r = new Result();

        String playlistUrl = edgeBase + BASE + "/" + quality + "/index.m3u8" + signedQuery();
        HttpResponse<String> pl = http.send(
                HttpRequest.newBuilder(URI.create(playlistUrl)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        r.status = pl.statusCode();
        long tPl = System.nanoTime();
        if (pl.statusCode() != 200) {
            r.playlistMs = (tPl - t0) / 1_000_000L;
            r.firstFrameMs = r.playlistMs;
            return r;
        }

        List<String> segs = new ArrayList<>();
        List<Double> durs = new ArrayList<>();
        double pending = 0;
        for (String line : pl.body().split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int c = v.indexOf(',');
                pending = Double.parseDouble(c > 0 ? v.substring(0, c) : v);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segs.add(line);
                durs.add(pending);
            }
        }
        if (segs.isEmpty()) {
            r.playlistMs = (tPl - t0) / 1_000_000L;
            r.firstFrameMs = r.playlistMs;
            return r;
        }

        int take = Math.min(Math.max(1, startup), segs.size());
        int from = segs.size() - take;
        r.startIndex = from;
        r.segments = take;
        r.segDuration = durs.get(from);

        Path tmp = Files.createTempDirectory("player");
        Path firstSeg = null;
        long bytes = 0;
        for (int i = from; i < from + take; i++) {
            String url = edgeBase + BASE + "/" + quality + "/" + segs.get(i) + signedQuery();
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                r.status = resp.statusCode();
                break;
            }
            bytes += resp.body().length;
            Path f = tmp.resolve(segs.get(i));
            Files.write(f, resp.body());
            if (firstSeg == null) firstSeg = f;
        }
        long tDl = System.nanoTime();
        r.bytes = bytes;

        if (firstSeg != null) {
            r.decodeMs = decodeFirstFrame(firstSeg);
        }
        long t1 = System.nanoTime();

        r.playlistMs = (tPl - t0) / 1_000_000L;
        r.downloadMs = (tDl - tPl) / 1_000_000L;
        r.firstFrameMs = (t1 - t0) / 1_000_000L;
        return r;
    }

    /** 真实解码：把分片交给 ffmpeg，解出第一帧就退出。 */
    private long decodeFirstFrame(Path seg) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-v", "error", "-i", seg.toAbsolutePath().toString(),
                    "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-y", "/dev/null");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            long t = System.nanoTime();
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor();
            return (System.nanoTime() - t) / 1_000_000L;
        } catch (Exception e) {
            return -1;
        }
    }

    private void runConcurrent(String edgeBase, String quality, int startup, int threads, String label)
            throws Exception {
        List<Result> results = new ArrayList<>();
        List<Result> sync = java.util.Collections.synchronizedList(results);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    Result r = playOnce(edgeBase, quality, startup);
                    if (r.status != 200) errors.incrementAndGet();
                    sync.add(r);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        ready.await();
        long t0 = System.nanoTime();
        go.countDown();
        done.await();
        long wall = (System.nanoTime() - t0) / 1_000_000L;

        long[] v = sync.stream().filter(r -> r.status == 200)
                .mapToLong(r -> r.firstFrameMs).sorted().toArray();
        if (v.length == 0) {
            System.out.println("{\"label\":\"" + label + "\",\"error\":\"all failed\"}");
            return;
        }
        System.out.println(String.format(Locale.ROOT,
                "{\"label\":\"%s\",\"concurrency\":%d,\"ok\":%d,\"errors\":%d,\"wallMs\":%d,"
                        + "\"p50\":%d,\"p90\":%d,\"p99\":%d,\"min\":%d,\"max\":%d,\"avg\":%d}",
                label, threads, v.length, errors.get(), wall,
                pct(v, 50), pct(v, 90), pct(v, 99), v[0], v[v.length - 1],
                (long) java.util.Arrays.stream(v).average().orElse(0)));
    }

    private static long pct(long[] sorted, double p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static String signedQuery() {
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String sign = new LiveAuth(KEY).sign(BASE, exp);
        return "?expire=" + exp + "&sign=" + sign;
    }

    public static void dumpPlaylist(String edgeBase, String quality) throws IOException, InterruptedException {
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(
                        URI.create(edgeBase + BASE + "/" + quality + "/index.m3u8" + signedQuery()))
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println(r.body());
    }
}
