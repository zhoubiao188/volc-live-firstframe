package com.example.volclive;

import com.example.volclive.live.EdgeServer;
import com.example.volclive.live.OriginServer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 启动一个最小的"直播链路"：
 *   源站 :9090  ← 切片存储（不限速）
 *   边缘 :8080  ← 鉴权 / 缓存 / 预热 / 出口限速
 */
public final class App {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        int port = Integer.parseInt(a.getOrDefault("port", "8080"));
        int originPort = Integer.parseInt(a.getOrDefault("origin-port", "9090"));
        Path data = Path.of(a.getOrDefault("data", "data/live"));
        long throttle = Long.parseLong(a.getOrDefault("throttle", "500000")); // B/s，500000 = 4Mbps
        int window = Integer.parseInt(a.getOrDefault("window", "6"));
        String key = a.getOrDefault("key", System.getenv().getOrDefault("LIVE_KEY", "volc-demo-key"));

        OriginServer origin = new OriginServer(originPort, data);
        origin.start();

        EdgeServer edge = new EdgeServer(port, "http://127.0.0.1:" + originPort, data, key, throttle, window);
        edge.start();

        System.out.printf("origin=http://127.0.0.1:%d  edge=http://127.0.0.1:%d%n", originPort, port);
        System.out.printf("throttle=%d B/s (%.1f Mbps)  playlistWindow=%d  data=%s%n",
                throttle, throttle * 8 / 1e6, window, data.toAbsolutePath());
        System.out.println("ready");
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                m.put(args[i].substring(2), args[++i]);
            }
        }
        return m;
    }
}
