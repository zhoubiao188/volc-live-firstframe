package com.example.volclive.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * 源站：模拟直播中心节点上的切片存储。
 * 带宽给足（不限速），所有差异都发生在边缘节点。
 */
public final class OriginServer {

    private final HttpServer server;
    private final Path baseDir;

    public OriginServer(int port, Path baseDir) throws IOException {
        this.baseDir = baseDir;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        this.server.setExecutor(Executors.newFixedThreadPool(64));
        this.server.createContext("/origin/", this::handle);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String rel = path.substring("/origin/".length());
        if (rel.contains("..")) {
            ex.sendResponseHeaders(400, -1);
            ex.close();
            return;
        }
        Path f = baseDir.resolve(rel);
        if (!Files.exists(f) || Files.isDirectory(f)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] body = Files.readAllBytes(f);
        String ct = rel.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t";
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.getResponseHeaders().add("X-Origin-Node", "cn-beijing-center");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }
}
