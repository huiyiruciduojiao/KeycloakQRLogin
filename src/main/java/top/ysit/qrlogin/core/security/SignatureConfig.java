package top.ysit.qrlogin.core.security;

public class SignatureConfig {
    private String secret;
    private SignatureAlgorithm algorithm;
    private long timeWindowSeconds;

    public SignatureConfig() {
        // 默认配置
        this.algorithm = SignatureAlgorithm.HMAC_SHA256;
        this.timeWindowSeconds = 5;
    }

    public SignatureConfig(String secret, SignatureAlgorithm algorithm, long timeWindowSeconds) {
        this.secret = secret;
        this.algorithm = algorithm;
        this.timeWindowSeconds = timeWindowSeconds;
    }

    // Getters and Setters
    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public SignatureAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(SignatureAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public long getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(long timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public enum SignatureAlgorithm {
        HMAC_SHA256("HmacSHA256"),
        HMAC_SHA1("HmacSHA1"),
        HMAC_SHA384("HmacSHA384"),
        HMAC_SHA512("HmacSHA512");

        private final String algorithmName;

        SignatureAlgorithm(String algorithmName) {
            this.algorithmName = algorithmName;
        }

        public static java.util.List<String> toStringList() {

            return java.util.Arrays.stream(values())
                    .map(SignatureAlgorithm::toString)
                    .toList();
        }

        public String getAlgorithmName() {
            return algorithmName;
        }

        @Override
        public String toString() {
            return algorithmName;
        }
    }
}
