package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.models.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarCorsCacheTest {
    @TempDir Path root;
    private final KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    private final ClientModel client = mock(ClientModel.class);
    private final RealmModel realm = mock(RealmModel.class);

    @BeforeEach void setup() {
        when(realm.getId()).thenReturn("r");
        when(client.isEnabled()).thenReturn(true);
        when(client.getWebOrigins()).thenReturn(Set.of("https://app.example.test"));
    }

    @Test void scansRealmClientsOncePerTtl() {
        var clock = new AvatarTestSupport.MutableClock();
        var cache = new AvatarCorsCache(clock);
        var config = AvatarTestSupport.config(root);
        var counter = new AtomicInteger();
        when(session.clients().getClientsStream(realm)).thenAnswer(i -> {
            counter.incrementAndGet();
            return java.util.stream.Stream.of(client);
        });
        assertTrue(cache.allows(session, realm, config, true, "https://app.example.test"));
        assertTrue(cache.allows(session, realm, config, true, "https://app.example.test"));
        assertEquals(1, counter.get());
        clock.now = clock.now.plusSeconds(61);
        assertTrue(cache.allows(session, realm, config, true, "https://app.example.test"));
        assertEquals(2, counter.get());
        assertFalse(cache.allows(session, realm, config, true, "https://evil.test"));
        assertFalse(cache.allows(session, realm, config, true, "*"));
        assertFalse(cache.allows(session, realm, config, true, null));
    }

    @Test void allowlistModeKeysOnConfigurationSoChangesApplyImmediately() {
        var cache = new AvatarCorsCache(new AvatarTestSupport.MutableClock());
        var config = AvatarTestSupport.config(root);
        when(session.clients().getClientByClientId(realm, "web")).thenReturn(client);
        assertTrue(cache.allows(session, realm, config, false, "https://app.example.test"));
        assertTrue(cache.allows(session, realm, config, false, "https://app.example.test"));
        // The configured public base URL origin stays allowed in both modes.
        assertTrue(cache.allows(session, realm, config, false, "https://sso.example.test"));
        var other = new AvatarConfig(config.enabled(), config.root(), config.publicBase(), config.realms(),
                Set.of("other"), config.allowRealmClients(), config.keys(), config.activeKey(), config.ttlSeconds(), config.maxBytes());
        when(session.clients().getClientByClientId(realm, "other")).thenReturn(null);
        // A changed allowlist is a different cache entry; the stale snapshot must not be reused.
        assertFalse(cache.allows(session, realm, other, false, "https://app.example.test"));
    }

    @Test void concurrentMissesComputeOnce() throws Exception {
        var cache = new AvatarCorsCache(new AvatarTestSupport.MutableClock());
        var config = AvatarTestSupport.config(root);
        var counter = new AtomicInteger();
        when(session.clients().getClientsStream(realm)).thenAnswer(i -> {
            counter.incrementAndGet();
            return java.util.stream.Stream.of(client);
        });
        var results = new ArrayList<Future<Boolean>>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return cache.allows(session, realm, config, true, "https://app.example.test");
            }));
            start.countDown();
            for (var result : results) assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(1, counter.get());
        } finally { pool.shutdownNow(); }
    }
}
