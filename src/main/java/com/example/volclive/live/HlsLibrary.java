package com.example.volclive.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个已经切好的 HLS 媒体目录（对应直播里的"某一路转码输出"）。
 * 只做两件事：解析 m3u8 里的分片列表、按需拼出子 playlist。
 */
public final class HlsLibrary {

    public static final class Segment {
        public final int index;
        public final double duration;
        public final String fileName;
        public final long sizeBytes;

        Segment(int index, double duration, String fileName, long sizeBytes) {
            this.index = index;
            this.duration = duration;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
        }
    }

    private final Path dir;
    private final List<Segment> segments;

    public HlsLibrary(Path dir) throws IOException {
        this.dir = dir;
        this.segments = parse(dir.resolve("index.m3u8"), dir);
    }

    private static List<Segment> parse(Path playlist, Path dir) throws IOException {
        List<Segment> list = new ArrayList<>();
        if (!Files.exists(playlist)) {
            return list;
        }
        double pending = -1;
        int idx = 0;
        for (String raw : Files.readAllLines(playlist, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                String v = line.substring("#EXTINF:".length());
                int comma = v.indexOf(',');
                pending = Double.parseDouble(comma > 0 ? v.substring(0, comma) : v);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                Path f = dir.resolve(line);
                long size = Files.exists(f) ? Files.size(f) : 0;
                list.add(new Segment(idx++, pending < 0 ? 0 : pending, line, size));
                pending = -1;
            }
        }
        return list;
    }

    public Path dir() { return dir; }

    public List<Segment> segments() { return segments; }

    public int size() { return segments.size(); }

    public double totalDuration() {
        double d = 0;
        for (Segment s : segments) d += s.duration;
        return d;
    }

    public long totalBytes() {
        long b = 0;
        for (Segment s : segments) b += s.sizeBytes;
        return b;
    }

    /**
     * 生成 media playlist。window 是滑动窗口大小（真实直播只会保留最近几个分片，
     * 不会把整场直播的切片都吐给播放器）。
     */
    public String renderPlaylist(int window) {
        // 直播场景里"正在录制的那一片"还没封口，不会出现在 playlist 里。
        // 这里同样把尾部不足半个目标时长的分片裁掉，避免播放器拿到 0.08s 的残片。
        int end = segments.size();
        double target = Math.max(0.001, maxDuration());
        while (end > 1 && segments.get(end - 1).duration < target * 0.5) {
            end--;
        }
        int from = Math.max(0, end - Math.max(1, window));
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(maxDuration())).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(from).append('\n');
        sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:0\n");
        for (int i = from; i < end; i++) {
            Segment s = segments.get(i);
            sb.append(String.format("#EXTINF:%.3f,%n", s.duration));
            sb.append(s.fileName).append('\n');
        }
        if (end >= segments.size()) {
            sb.append("#EXT-X-ENDLIST\n");
        }
        return sb.toString();
    }

    private double maxDuration() {
        double m = 0;
        for (Segment s : segments) m = Math.max(m, s.duration);
        return m;
    }

    public Segment byName(String name) {
        for (Segment s : segments) {
            if (s.fileName.equals(name)) return s;
        }
        return null;
    }
}
