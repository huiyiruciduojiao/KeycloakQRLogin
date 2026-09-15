package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AvatarConfigTest {
    @TempDir Path root;
    private Map<String,String> values() {
        return new HashMap<>(Map.of("enabled", "true", "storage-path", root.toString(), "public-base-url", "https://sso.test/auth",
                "realms", "one,two", "clients", "web,account-console", "active-key", "v1",
                "signing-keys", "v1:" + Base64.getEncoder().encodeToString(new byte[32])));
    }
    @Test void disabledByDefaultAndExplicitConfigLoads() {
        assertFalse(AvatarConfig.load(name -> null).enabled());
        var config = AvatarConfig.load(values()::get);
        assertEquals(900, config.ttlSeconds()); assertEquals(Set.of("one", "two"), config.realms());
    }
    @Test void refusesMissingAndInvalidConfiguration() {
        for (String field : List.of("storage-path", "public-base-url", "realms", "clients", "active-key", "signing-keys")) {
            var values = values(); values.remove(field);
            assertThrows(IllegalArgumentException.class, () -> AvatarConfig.load(values::get), field);
        }
        for (var invalid : Map.of("storage-path", "relative", "clients", "*", "realms", "one,", "active-key", "unknown",
                "signing-keys", "v1:AAAA", "url-ttl-seconds", "0", "max-storage-bytes", "1", "enabled", "yes").entrySet()) {
            var values = values(); values.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> AvatarConfig.load(values::get), invalid.getKey());
        }
        for (String url : List.of("/relative", "ftp://sso.test", "https://user:pass@sso.test", "https://sso.test/?q=x", "https://sso.test/#fragment")) {
            var values = values(); values.put("public-base-url", url);
            assertThrows(IllegalArgumentException.class, () -> AvatarConfig.load(values::get));
        }
    }
}
