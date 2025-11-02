package top.ysit.qrlogin.config;

import org.keycloak.models.IdentityProviderModel;
import top.ysit.qrlogin.core.security.SignatureConfig;

public class QRLoginConfig extends IdentityProviderModel {
    public static final String PROVIDER_ID = "qrlogin";
    private final IdentityProviderModel model;


    public QRLoginConfig(IdentityProviderModel model) {
        this.model = model;
    }

    public int getSessionTtlSeconds() { // 二维码有效期
        return getInt("sessionTtlSeconds", 120);
    }

    public int getPollIntervalMs() { // 轮询间隔（前端提示用）
        return getInt("pollIntervalMs", 1500);
    }

    public String getStoreType() { // redis | memory
        return get("storeType", "redis");
    }

    public String getRedisUri() { // redis://host:port
        return get("redisUri", "redis://127.0.0.1:6379");
    }

    public String getRedisNamespace() { // key 前缀
        return get("redisNamespace", "qrlogin:");
    }

    public String getHmacSecret() { // App 签名校验
        return get("hmacSecret", "change-me");
    }

    public long getTimeWindowSeconds() {
        return getLong("timeWindowSeconds", 5);
    }

    public SignatureConfig.SignatureAlgorithm getAlgorithm(){
        return getAlgorithm("algorithm", SignatureConfig.SignatureAlgorithm.HMAC_SHA256);
    }



    private String get(String k, String def) {
        String v = model.getConfig() == null ? null : model.getConfig().get(k);
        return v == null || v.isBlank() ? def : v;
    }

    private int getInt(String k, int def) {
        try {
            return Integer.parseInt(get(k, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private long getLong(String k, long def) {
        try {
            return Long.parseLong(get(k, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }
    private SignatureConfig.SignatureAlgorithm getAlgorithm(String k, SignatureConfig.SignatureAlgorithm def) {
        try {
            String algorithmName = get(k, def.name());
            try {
                return SignatureConfig.SignatureAlgorithm.valueOf(algorithmName);
            } catch (IllegalArgumentException e) {
                // 如果失败，按algorithmName字段值查找
                for (SignatureConfig.SignatureAlgorithm algorithm : SignatureConfig.SignatureAlgorithm.values()) {
                    if (algorithm.getAlgorithmName().equals(algorithmName)) {
                        return algorithm;
                    }
                }
                return def;
            }
        } catch (Exception e) {
            System.out.println("error" + e);
            return def;
        }
    }

    @Override
    public String getAlias() {
        return model.getAlias();
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

}