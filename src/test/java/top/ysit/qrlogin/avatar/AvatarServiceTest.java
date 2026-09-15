package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AvatarServiceTest {
    @TempDir Path root;
    @Test void batchReturnsStableMappingDefaultsAndOnlyAuthorizedUsers() throws Exception {
        var config = AvatarTestSupport.config(root);
        try (var storage = new LocalAvatarStorage(root, config.maxBytes())) {
            var service = new AvatarService(config, storage, new AvatarUrlSigner(config, Clock.systemUTC()));
            storage.replace("r", "alice", AvatarTestSupport.images());
            var items = service.batch("r", "example", List.of("bob", "alice", "alice", "missing", "disabled"), 128, id -> Set.of("alice", "bob").contains(id));
            assertEquals(List.of("bob", "alice", "missing", "disabled"), items.stream().map(AvatarService.Item::userId).toList());
            assertEquals("DEFAULT", items.get(0).status());
            assertEquals("https://sso.example.test/auth/realms/example/avatars/default/128", items.get(0).url());
            assertEquals("CUSTOM", items.get(1).status()); assertNotNull(items.get(1).expiresAt());
            assertTrue(items.get(1).url().contains("?exp=")); assertFalse(items.get(1).url().contains("alice"));
            assertEquals("UNAVAILABLE", items.get(2).status()); assertNull(items.get(2).url());
            assertEquals("UNAVAILABLE", items.get(3).status());
            assertEquals("DEFAULT", service.item("other", "example", "alice", 128).status());
        }
    }
    @Test void rejectsInvalidIdsAndRawBatchLimitBeforeDeduplication() throws Exception {
        var config = AvatarTestSupport.config(root);
        try (var storage = new LocalAvatarStorage(root, config.maxBytes())) {
            var service = new AvatarService(config, storage, new AvatarUrlSigner(config, Clock.systemUTC()));
            for (List<String> users : Arrays.asList(null, List.<String>of(), List.of(""), Arrays.asList("a", null), Collections.nCopies(101, "a")))
                assertThrows(AvatarException.class, () -> service.batch("r", "example", users, 128, id -> true));
            assertThrows(AvatarException.class, () -> service.batch("r", "example", List.of("a"), 32, id -> true));
        }
    }
}
