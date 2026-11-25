package com.example.volclive.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 边缘节点（CDN / 直播边缘）。负责四件事：
 *   1. 拉流鉴权（sign + expire，不通过直接 403）
 *   2. 子 playlist 的滑动窗口裁剪（真实直播只保留最近几个分片）
 *   3. 分片缓存 + 回源 + 预热
 *   4. 出口限速（模拟 4G / 家宽，把"首帧慢"的网络因素也还原出来）
 */
public final class EdgeServer {

    public static final String BASE_PATH = "/live/demo/stream";

    private final HttpServer server;
    private final HttpClient client;
    private final String originBase;
    private final LiveAuth auth;
    private final Metrics metrics = new Metrics();
    private final Map<String, HlsLibrary> libs = new HashMap<>();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final long throttleBytesPerSec;
    private final int playlistWindow;
    private final Path dataDir;

    private void reloadLibs() throws IOException {
        libs.clear();
        if (!Files.isDirectory(dataDir)) return;
        try (var s = Files.list(dataDir)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                if (Files.exists(d.resolve("index.m3u8"))) {
                    libs.put(d.getFileName().toString(), new HlsLibrary(d));
                }
            }
        }
    }

    public EdgeServer(int port, String originBase, Path dataDir, String authKey,
                      long throttleBytesPerSec, int playlistWindow) throws IOException {
        this.originBase = originBase;
        this.auth = new LiveAuth(authKey);
        this.throttleBytesPerSec = throttleBytesPerSec;
        this.playlistWindow = playlistWindow;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.dataDir = dataDir;
        reloadLibs();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.setExecutor(Executors.newFixedThreadPool(256));
        server.createContext("/live/", this::handleLive);
        server.createContext("/admin/prewarm", this::handlePrewarm);
        server.createContext("/admin/reload", ex -> {
            reloadLibs();
            cache.clear();
            byte[] b = ("{\"reloaded\":true,\"qualities\":\"" + String.join(",", libs.keySet()) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.createContext("/metrics", ex -> {
            byte[] b = metrics.toJson().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public Metrics metrics() { return metrics; }

    private void handleLive(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!auth.verify(BASE_PATH, q(ex, "expire"), q(ex, "sign"))) {
                metrics.authFail.incrementAndGet();
                ex.sendResponseHeaders(403, -1);
                ex.close();
                return;
            }
            String rel = path.substring(BASE_PATH.length() + 1); // 720p/index.m3u8
            int slash = rel.indexOf('/');
            if (slash < 0) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            String quality = rel.substring(0, slash);
            String file = rel.substring(slash + 1);

            HlsLibrary lib = libs.get(quality);
            if (lib == null) { metrics.notFound.incrementAndGet(); ex.sendResponseHeaders(404, -1); ex.close(); return; }

            if (file.equals("index.m3u8")) {
                metrics.playlistReq.incrementAndGet();
                byte[] body = lib.renderPlaylist(playlistWindow).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                ex.close();
                return;
            }

            if (lib.byName(file) == null) {
                metrics.notFound.incrementAndGet();
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }

            metrics.segmentReq.incrementAndGet();
            String key = quality + "/" + file;
            byte[] body = cache.get(key);
            if (body != null) {
                metrics.cacheHit.incrementAndGet();
            } else {
                metrics.cacheMiss.incrementAndGet();
                body = fetchOrigin(quality, file);
                if (body == null) { ex.sendResponseHeaders(502, -1); ex.close(); return; }
                metrics.originBytes.addAndGet(body.length);
                cache.put(key, body);
            }
            metrics.edgeBytes.addAndGet(body.length);
            ex.getResponseHeaders().add("Content-Type", "video/mp2t");
            ex.getResponseHeaders().add("X-Cache", "HIT");
            ex.sendResponseHeaders(200, body.length);
            writeThrottled(ex.getResponseBody(), body);
            ex.close();
        } catch (Exception e) {
            try { ex.sendResponseHeaders(500, -1); ex.close(); } catch (IOException ignored) { }
        }
    }

    /** 预热：把 playlist 与最近 N 个分片提前拉到边缘，观众进来时直接命中。 */
    private void handlePrewarm(HttpExchange ex) throws IOException {
        String quality = q(ex, "quality");
        int count = Integer.parseInt(q(ex, "count") == null ? "3" : q(ex, "count"));
        HlsLibrary lib = libs.get(quality);
        int warmed = 0;
        if (lib != null) {
            int from = Math.max(0, lib.size() - count);
            for (int i = from; i < lib.size(); i++) {
                String f = lib.segments().get(i).fileName;
                String key = quality + "/" + f;
                if (!cache.containsKey(key)) {
                    byte[] b = fetchOrigin(quality, f);
                    if (b != null) {
                        cache.put(key, b);
                        metrics.originBytes.addAndGet(b.length);
                        warmed++;
                    }
                }
            }
        }
        byte[] out = ("{\"quality\":\"" + quality + "\",\"warmed\":" + warmed + "}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        ex.close();
    }

    private byte[] fetchOrigin(String quality, String file) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(originBase + "/origin/" + quality + "/" + file))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeThrottled(OutputStream os, byte[] body) throws IOException, InterruptedException {
        if (throttleBytesPerSec <= 0) {
            os.write(body);
            return;
        }
        final int chunk = 16 * 1024;
        long perChunkNanos = (long) (chunk * 1_000_000_000L / throttleBytesPerSec);
        int off = 0;
        while (off < body.length) {
            int n = Math.min(chunk, body.length - off);
            long t0 = System.nanoTime();
            os.write(body, off, n);
            os.flush();
            off += n;
            long spent = System.nanoTime() - t0;
            long wait = perChunkNanos - spent;
            if (wait > 0 && off < body.length) {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            }
        }
    }

    private static String q(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) return kv.substring(i + 1);
        }
        return null;
    }

    static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
