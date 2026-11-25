package com.example.volclive.bench;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 素材与切片工具：所有分片都是 ffmpeg 现切出来的，
 * 所以「分片到底多长」这件事可以拿 ffprobe 直接对账，不靠嘴说。
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "gen" -> gen(args[1], Integer.parseInt(args[2]));
            case "hls" -> hls(args[1], args[2], Double.parseDouble(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), args[6],
                    args.length > 7 ? args[7] : null);
            case "ladder" -> ladder(args[1], args[2], args[3]);
            case "probe" -> probe(args[1]);
            default -> {
                System.err.println("unknown " + args[0]);
                System.exit(2);
            }
        }
    }

    /** 生成"推流源"：40 秒 720p25，关键帧 10 秒一个（模拟推流端默认配置）。 */
    private static void gen(String out, int seconds) throws Exception {
        new File(out).getParentFile().mkdirs();
        run(List.of("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", String.valueOf(seconds),
                "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main",
                "-b:v", "2000k", "-g", "250", "-keyint_min", "250", "-sc_threshold", "0",
                "-c:a", "aac", "-b:a", "128k",
                "-f", "flv", out));
        System.out.println("source=" + out + " size=" + Files.size(Path.of(out)));
    }

    /**
     * 切 HLS。
     * mode=copy      : 直接 -c copy 切片（推流端 GOP 是多少，切片就被拉成多长）
     * mode=reencode  : 重新编码并按 segSec 强制 IDR（这才是真正的 segSec 分片）
     */
    private static void hls(String src, String outDir, double segSec, int gop, int bitrateK, String mode,
                            String scale) throws Exception {
        Path d = Path.of(outDir);
        if (Files.exists(d)) {
            try (var s = Files.walk(d)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(d);

        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-i", src));
        if (mode.equals("copy")) {
            cmd.addAll(List.of("-c", "copy"));
        } else {
            if (scale != null && !scale.isEmpty()) {
                cmd.addAll(List.of("-vf", "scale=" + scale));
            }
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main",
                    "-b:v", bitrateK + "k", "-maxrate", (bitrateK * 11 / 10) + "k",
                    "-bufsize", (bitrateK * 2) + "k",
                    "-g", String.valueOf(gop), "-keyint_min", String.valueOf(gop), "-sc_threshold", "0",
                    "-force_key_frames", "expr:gte(t,n_forced*" + trim(segSec) + ")",
                    "-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(List.of("-f", "hls", "-hls_time", trim(segSec), "-hls_list_size", "0",
                "-hls_segment_filename", d.resolve("seg%03d.ts").toString(),
                d.resolve("index.m3u8").toString()));
        run(cmd);
        System.out.println("hls=" + outDir + " mode=" + mode + " segSec=" + segSec + " gop=" + gop);
    }

    /** 三档转码阶梯（1080p / 720p / 480p），全部按各自的 GOP 对齐切 2s 分片。 */
    private static void ladder(String src, String outBase, String segSecS) throws Exception {
        double seg = Double.parseDouble(segSecS);
        int gop = (int) Math.round(25 * seg);
        hls(src, outBase + "/720p", seg, gop, 2000, "reencode", null);
        hls(src, outBase + "/480p", seg, gop, 800, "reencode", "854:480");
        hls(src, outBase + "/360p", seg, gop, 400, "reencode", "640:360");
        System.out.println("ladder done at " + outBase);
    }

    /** 用 ffprobe 对账：每个分片到底几秒、多大。 */
    private static void probe(String dir) throws Exception {
        List<Path> segs;
        try (var s = Files.list(Path.of(dir))) {
            segs = s.filter(p -> p.toString().endsWith(".ts")).sorted().toList();
        }
        double min = 1e9, max = 0, sum = 0;
        long bytes = 0;
        StringBuilder sb = new StringBuilder();
        for (Path p : segs) {
            double d = Double.parseDouble(runCapture(List.of("ffprobe", "-v", "error",
                    "-show_entries", "format=duration", "-of", "csv=p=0", p.toString())).trim());
            long sz = Files.size(p);
            min = Math.min(min, d); max = Math.max(max, d); sum += d; bytes += sz;
            sb.append(String.format(Locale.ROOT, "%s %.3f %.0fKB%n", p.getFileName(), d, sz / 1024.0));
        }
        if (segs.isEmpty()) {
            System.out.println("no segments in " + dir);
            return;
        }
        System.out.print(sb);
        System.out.println(String.format(Locale.ROOT,
                "PROBE dir=%s count=%d avg=%.3fs min=%.3fs max=%.3fs total=%.1fs bytes=%.2fMB avgKB=%.0f",
                dir, segs.size(), sum / segs.size(), min, max, sum, bytes / 1048576.0,
                bytes / 1024.0 / segs.size()));
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static void run(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("cmd failed(" + code + "): " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static String runCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }
}
