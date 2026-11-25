package com.example.volclive.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 直播拉流鉴权（防盗链）。
 *
 * 线上形态一般是：CDN 按配置的密钥对 "路径 + 过期时间" 做一次摘要，
 * 播放地址带 sign/expire 两个参数，边缘节点校验不过直接 403。
 * 这里把这个过程完整实现一遍，方便在本地验证「过期」「篡改」两种攻击。
 */
public final class LiveAuth {

    private final String key;

    public LiveAuth(String key) {
        this.key = key;
    }

    /** sign = md5("/app/stream.expire")，十六进制小写。 */
    public String sign(String uriPath, long expireSeconds) {
        String plain = uriPath + "." + expireSeconds;
        return md5(plain + "." + key);
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String uriPath, String expire, String sign) {
        if (expire == null || sign == null || expire.isEmpty() || sign.isEmpty()) return false;
        long exp;
        try {
            exp = Long.parseLong(expire);
        } catch (NumberFormatException e) {
            return false;
        }
        if (exp < System.currentTimeMillis() / 1000L) return false;
        String expect = sign(uriPath, exp);
        return MessageDigest.isEqual(
                expect.getBytes(StandardCharsets.UTF_8),
                sign.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
