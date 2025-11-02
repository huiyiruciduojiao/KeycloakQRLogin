// 修改后的 SignatureUtil.java
package top.ysit.qrlogin.core.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.*;

public class SignatureUtil {
    private final SignatureConfig config;

    public SignatureUtil(SignatureConfig config) throws SignatureException {
        if (config == null) {
            throw new SignatureException("Configuration must not be null");
        }
        if (config.getSecret() == null || config.getSecret().isEmpty()) {
            throw new SignatureException("Secret must not be null or empty");
        }
        this.config = config;
    }

    public SignatureUtil(String secret) throws SignatureException {
        this(new SignatureConfig(secret, SignatureConfig.SignatureAlgorithm.HMAC_SHA256, 5));
    }

    /**
     * 客户端：生成签名
     */
    public String sign(Map<String, String> params) throws SignatureException {
        if (!params.containsKey("timestamp")) {
            throw new SignatureException("Missing 'timestamp'");
        }
        String queryString = buildQueryString(params);
        return hmacSha256(queryString, config.getSecret());
    }

    /**
     * 服务端：验证签名 + 时间戳（±timeWindowSeconds秒）
     */
    public boolean verify(Map<String, String> params) throws SignatureException {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            throw new SignatureException("Missing 'sign'");
        }

        // 验证时间戳
        validateTimestamp(params.get("timestamp"));

        // 重新计算签名（排除 sign）
        Map<String, String> signParams = new HashMap<>(params);
        signParams.remove("sign");
        String queryString = buildQueryString(signParams);
        String expectedSign = hmacSha256(queryString, config.getSecret());

        if (!safeEquals(sign, expectedSign)) {
            throw new SignatureException("Invalid signature");
        }

        return true;
    }

    /**
     * 验证时间戳是否在有效时间窗口内
     * @param timestampStr 时间戳字符串（单位：秒）
     * @throws SignatureException 当时间戳无效或超出时间窗口时抛出异常
     */
    public void validateTimestamp(String timestampStr) throws SignatureException {
        long timestampSec;
        try {
            timestampSec = Long.parseLong(timestampStr);
        } catch (Exception e) {
            throw new SignatureException("Invalid 'timestamp'", e);
        }

        long nowSec = System.currentTimeMillis() / 1000;
        long diff = Math.abs(nowSec - timestampSec);

        if (diff > config.getTimeWindowSeconds()) {
            throw new SignatureException("Request expired: timestamp deviation > " +
                    config.getTimeWindowSeconds() + "s (now=" + nowSec + ", req=" + timestampSec + ")");
        }
    }

    private String buildQueryString(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.getOrDefault(key, "");
            if (i > 0) sb.append("&");
            sb.append(key).append("=").append(value);
        }
        return sb.toString();
    }

    private String hmacSha256(String data, String key) throws SignatureException {
        try {
            Mac mac = Mac.getInstance(config.getAlgorithm().toString());
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), config.getAlgorithm().toString());
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SignatureException("HMAC-SHA256 failed", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // 恒定时间比较（防时序攻击）
    private boolean safeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return Arrays.equals(aBytes, bBytes); // 在大多数 JVM 实现中足够安全
    }
}
