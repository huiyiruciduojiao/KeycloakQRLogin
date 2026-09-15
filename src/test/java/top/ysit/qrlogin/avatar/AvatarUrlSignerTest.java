package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class AvatarUrlSignerTest {
    @Test void signatureBindsEveryFieldAndExpiresAtBoundary() {
        var config = AvatarTestSupport.config(Path.of("."));
        var clock = Clock.fixed(Instant.ofEpochSecond(10000), ZoneOffset.UTC);
        var signer = new AvatarUrlSigner(config, clock);
        long expires = signer.expires();
        String sig = signer.sign("image", "realm", "asset", "128", expires, "v1");
        assertTrue(signer.verify("image", "realm", "asset", "128", expires, "v1", sig));
        assertFalse(signer.verify("csrf", "realm", "asset", "128", expires, "v1", sig));
        assertFalse(signer.verify("image", "other", "asset", "128", expires, "v1", sig));
        assertFalse(signer.verify("image", "realm", "other", "128", expires, "v1", sig));
        assertFalse(signer.verify("image", "realm", "asset", "256", expires, "v1", sig));
        assertFalse(signer.verify("image", "realm", "asset", "128", expires + 1, "v1", sig));
        assertFalse(signer.verify("image", "realm", "asset", "128", expires, "missing", sig));
        var expired = new AvatarUrlSigner(config, Clock.offset(clock, Duration.ofSeconds(900)));
        assertFalse(expired.verify("image", "realm", "asset", "128", expires, "v1", sig));
        assertEquals(0, expired.remaining(expires));
    }
    @Test void ambiguousOpaqueIdsCannotChangeSignatureMeaning() {
        var signer = new AvatarUrlSigner(AvatarTestSupport.config(Path.of(".")), Clock.systemUTC());
        long expires = signer.expires();
        assertNotEquals(signer.sign("csrf", "r", "user\nsession", "s", expires, "v1"), signer.sign("csrf", "r", "user", "session\ns", expires, "v1"));
    }
}
