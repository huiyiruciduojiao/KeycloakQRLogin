package top.ysit.qrlogin.core.util;

import jakarta.annotation.Nonnull;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TokenUtil {
    public static TokenValidationResult verifyToken(String token, @Nonnull IntrospectConfig config) {
        if (token == null || token.isEmpty()) {
            return TokenValidationResult.invalid("token 为空或无效");
        }
        if (config.baseUrl == null || config.realm == null || config.clientId == null || config.clientSecret == null){
            return TokenValidationResult.invalid("配置错误");
        }

        try {
            // 构造 introspect URL
            String urlString = config.baseUrl + "/realms/" + config.realm + "/protocol/openid-connect/token/introspect";
            URL url = new URL(urlString);

            // 设置请求
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            String body = String.format("client_id=%s&client_secret=%s&token=%s", config.clientId, config.clientSecret, token);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }


            JsonObject json = Json.createReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)).readObject();
            boolean active = json.getBoolean("active", false);
            if (!active) {
                return TokenValidationResult.invalid("Token is not active");
            }

            // 安全提取 exp 字段
            Long exp = null;
            if (json.containsKey("exp") && !json.isNull("exp")) {
                exp = json.getJsonNumber("exp").longValue();
            }

            // 提取其他信息
            String username = json.getString("username", null);
            String clientId = json.getString("client_id", null);
            List<String> scope = parseScope(json.getString("scope", null));


//            解析token内容，获取email
            JsonObject payload = tokenToJson(token);
            String email = payload.getString("email", null);
            String sub = json.getString("sub", null);


            return TokenValidationResult.valid(username, clientId, email, sub, scope, exp);

        } catch (Exception e) {
            return TokenValidationResult.error("验证异常: " + e.getMessage());
        }
    }

    private static List<String> parseScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(scope.split(" "));
    }

    public static JsonObject tokenToJson(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        try {
            // JWT token 由三部分组成，用点分隔：header.payload.signature
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }

            // 解码 payload 部分（第二部分）
            String payload = parts[1];
            // 补充可能缺失的填充字符
            switch (payload.length() % 4) {
                case 2:
                    payload += "==";
                    break;
                case 3:
                    payload += "=";
                    break;
            }

            // Base64 解码
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(payload);
            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);

            // 解析为 JsonObject
            return Json.createReader(new java.io.StringReader(decodedString)).readObject();

        } catch (Exception e) {
            // 解析失败时返回 null
            return null;
        }
    }

    public record TokenValidationResult(boolean valid, String error, String username, String clientId, String email,
                                        String sub, List<String> scope, Long expiration) {


        public static TokenValidationResult valid(String username, String clientId, String email, String sub, List<String> scope, Long expiration) {
            return new TokenValidationResult(true, null, username, clientId, email, sub, scope, expiration);
        }

        public static TokenValidationResult invalid(String error) {
            return new TokenValidationResult(false, error, null, null, null, null, null, null);
        }

        public static TokenValidationResult error(String error) {
            return new TokenValidationResult(false, error, null, null, null, null, null, null);
        }
    }

    public static class IntrospectConfig {


        private String baseUrl;
        private String realm;
        private String clientId;
        private String clientSecret;

        public IntrospectConfig(String baseUrl, String realm, String clientId, String clientSecret) {
            this.baseUrl = baseUrl;
            this.realm = realm;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public IntrospectConfig() {
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRealm() {
            return realm;
        }

        public void setRealm(String realm) {
            this.realm = realm;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }
}
