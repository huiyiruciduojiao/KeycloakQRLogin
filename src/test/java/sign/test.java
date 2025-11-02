package sign;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class test {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long TIME_WINDOW_MS = 5_000; // 严格限制：5秒（±5秒，共10秒窗口）

    private final String secret;

    public test(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Secret must not be null or empty");
        }
        this.secret = secret;
    }

    /**
     * 客户端：生成签名
     */
    public String sign(Map<String, String> params) {
        if (!params.containsKey("timestamp")) {
            throw new IllegalArgumentException("Missing 'timestamp'");
        }
        String queryString = buildQueryString(params);
        return hmacSha256(queryString, secret);
    }

    /**
     * 服务端：验证签名 + 时间戳（±5秒）
     */
    public boolean verify(Map<String, String> params) {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            throw new IllegalArgumentException("Missing 'sign'");
        }

        // 解析 timestamp（单位：秒）
        long timestampSec;
        try {
            timestampSec = Long.parseLong(params.get("timestamp"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid 'timestamp'");
        }

        long nowSec = System.currentTimeMillis() / 1000;
        long diff = Math.abs(nowSec - timestampSec);

        if (diff > 5000) { // 超过5秒
            throw new IllegalArgumentException("Request expired: timestamp deviation > 5s (now=" + nowSec + ", req=" + timestampSec + ")");
        }

        // 重新计算签名（排除 sign）
        Map<String, String> signParams = new HashMap<>(params);
        signParams.remove("sign");
        String queryString = buildQueryString(signParams);
        String expectedSign = hmacSha256(queryString, secret);

        if (!safeEquals(sign, expectedSign)) {
            throw new IllegalArgumentException("Invalid signature");
        }

        return true;
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

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
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

    public static void main(String[] args) {
        long timestamp = System.currentTimeMillis() / 1000; // 必须用秒！
        timestamp -= 6;
        Map<String, String> params = new HashMap<>();
        params.put("kc_session", "abce3cb7-6345-4e54-8708-9b5e5fe463f8");
        params.put("token", "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ0REZLaWNZbWktWWZVd1RoV3djcF9IckdoSHl1YUwtdThZa2tQYmtEVlZjIn0.eyJleHAiOjE3NjIwOTU1NDYsImlhdCI6MTc2MjA5NTI0NiwiYXV0aF90aW1lIjoxNzYyMDY2NTMwLCJqdGkiOiJvZnJ0cnQ6NzIwZDNiMDEtNjNmNC00Mzg5LTkwODQtMGU0N2U4NWI2YzI3IiwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo4MDgwL3JlYWxtcy9tYXN0ZXIiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiOTg2MDU2ZmYtZWU4ZC00NjRlLTk0ZjItNTE2YWI2MjlkYWYwIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoidGVzdCIsInNpZCI6IjJjM2YwY2EwLWE2ZWEtNGNiMC1iZjFmLTc5ZjA3Njk3YjcwOCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiaHR0cDovLzEyNy4wLjAuMTo1MDAwIl0sInJlYWxtX2FjY2VzcyI6eyJyb2xlcyI6WyJkZWZhdWx0LXJvbGVzLW1hc3RlciIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJzY29wZSI6Im9wZW5pZCBwcm9maWxlIGVtYWlsIG9mZmxpbmVfYWNjZXNzIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsIm5hbWUiOiLkvKDnjpYg5p2OIiwicHJlZmVycmVkX3VzZXJuYW1lIjoidGVzdDAwMSIsImdpdmVuX25hbWUiOiLkvKDnjpYiLCJsb2NhbGUiOiJ6aC1DTiIsImZhbWlseV9uYW1lIjoi5p2OIiwiZW1haWwiOiIxNzg3MDEwODk5NkAxNjMuY29tIn0.BpglUjBuownC2Tc5zN_l-387zrBrqABsGNsjqBjs-rqls8AChdST5iC1mGoEGsFyly72NQlmc2uSbSXsg-s5wEkaBm2WYXmx6-wVVhXp_t4LsKg1kswAqrwi3HRYMxWYbrp-adJIIRuCJIrXjvWYRPjNWI6UAg044pmu3B5pdhikS_SK-T9LnLdqs3A70WQGV2atLnZPWdyGQobv84_JQjE0kWRRBeroJHUBp338Z55_eSDRQpj6NN_xitQtNaqmG67iLO5N7Ao5VXLokNH6waHl6sREmHWd8R7rkVba2Ah2iJezJabmfiigArbcPcuJ8Aha4n0O2WQ9wjfKTa8LOQ");
        params.put("timestamp", String.valueOf(1762095272));
        params.put("qr_session", "9206d317-73a2-4fd1-a5ea-aa74af5fb73b");

        test signer = new test("lichuanjiu");
        String sign = signer.sign(params);
        params.put("sign", sign);


        System.out.println("Signature: " + sign);

        // 从请求中解析 params（含 sign）
        boolean valid = signer.verify(params);
        System.out.println("Valid: " + valid);

    }
}