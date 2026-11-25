package com.example.volclive.live;

import java.util.concurrent.atomic.AtomicLong;

public final class Metrics {
    public final AtomicLong playlistReq = new AtomicLong();
    public final AtomicLong segmentReq = new AtomicLong();
    public final AtomicLong cacheHit = new AtomicLong();
    public final AtomicLong cacheMiss = new AtomicLong();
    public final AtomicLong originBytes = new AtomicLong();
    public final AtomicLong edgeBytes = new AtomicLong();
    public final AtomicLong authFail = new AtomicLong();
    public final AtomicLong notFound = new AtomicLong();

    public String toJson() {
        long seg = segmentReq.get();
        long hit = cacheHit.get();
        double hr = seg == 0 ? 0 : (100.0 * hit / seg);
        return String.format(
                "{\"playlistReq\":%d,\"segmentReq\":%d,\"cacheHit\":%d,\"cacheMiss\":%d,"
                        + "\"cacheHitRate\":%.1f,\"originMB\":%.2f,\"edgeMB\":%.2f,"
                        + "\"authFail\":%d,\"notFound\":%d}",
                playlistReq.get(), seg, hit, cacheMiss.get(), hr,
                originBytes.get() / 1048576.0, edgeBytes.get() / 1048576.0,
                authFail.get(), notFound.get());
    }
}
