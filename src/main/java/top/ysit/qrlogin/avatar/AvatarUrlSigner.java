package top.ysit.qrlogin.avatar;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

final class AvatarUrlSigner {
    private final AvatarConfig config;
    private final Clock clock;
    AvatarUrlSigner(AvatarConfig config, Clock clock) { this.config = config; this.clock = clock; }
    long expires() { return clock.instant().getEpochSecond() + config.ttlSeconds(); }
    long remaining(long expires) { return Math.max(0, expires - clock.instant().getEpochSecond()); }
    String sign(String purpose, String realm, String subject, String variant, long expires, String kid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.keys().get(kid), "HmacSHA256"));
            // Length framing prevents ambiguous fields even for opaque federated user IDs.
            for (String field : new String[]{purpose, realm, subject, variant, Long.toString(expires), kid}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable", e); }
    }
    boolean verify(String purpose, String realm, String subject, String variant, long expires, String kid, String signature) {
        long now = clock.instant().getEpochSecond();
        if (expires <= now || expires - now > config.ttlSeconds() || kid == null || !config.keys().containsKey(kid)
                || signature == null || !signature.matches("[A-Za-z0-9_-]{43}")) return false;
        return MessageDigest.isEqual(sign(purpose, realm, subject, variant, expires, kid).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
