package top.ysit.qrlogin.avatar;

import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.utils.WebOriginsUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Preflight-only origin snapshots: unauthenticated OPTIONS requests must not rescan realm clients on every call.
 * Snapshots only advise browsers; the per-request origin check in authenticate() stays authoritative and fresh.
 */
final class AvatarCorsCache {
    private static final Duration TTL = Duration.ofSeconds(60);
    private record Snapshot(long computedAt, Set<String> origins) {}
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Clock clock;

    AvatarCorsCache(Clock clock) { this.clock = clock; }

    synchronized boolean allows(KeycloakSession session, RealmModel realm, AvatarConfig config, boolean allClients, String origin) {
        if (origin == null) return false;
        String allowlist = allClients ? "" : config.clients().stream().sorted().collect(Collectors.joining(","));
        String key = realm.getId() + '|' + allClients + '|' + config.publicBase() + '|' + allowlist;
        long now = clock.millis();
        Snapshot snapshot = snapshots.get(key);
        if (snapshot == null || snapshot.computedAt() + TTL.toMillis() <= now) {
            snapshot = new Snapshot(now, scan(session, realm, config, allClients));
            snapshots.put(key, snapshot);
            snapshots.values().removeIf(entry -> entry.computedAt() + TTL.toMillis() <= now);
        }
        return snapshot.origins().contains(origin);
    }

    private static Set<String> scan(KeycloakSession session, RealmModel realm, AvatarConfig config, boolean allClients) {
        Set<String> origins = new HashSet<>();
        origins.add(config.publicBase().getScheme() + "://" + config.publicBase().getRawAuthority());
        try (var clients = allClients
                ? session.clients().getClientsStream(realm)
                : config.clients().stream().map(name -> session.clients().getClientByClientId(realm, name))) {
            clients.filter(client -> client != null && client.isEnabled())
                    .forEach(client -> origins.addAll(WebOriginsUtils.resolveValidWebOrigins(session, client)));
        }
        return Set.copyOf(origins);
    }
}
