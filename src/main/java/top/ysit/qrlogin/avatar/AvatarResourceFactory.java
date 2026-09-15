package top.ysit.qrlogin.avatar;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.keycloak.crypto.KeyUse;

public final class AvatarResourceFactory implements RealmResourceProviderFactory {
    private final Map<String, LocalAvatarStorage> stores = new HashMap<>();
    private final AvatarCorsCache cors = new AvatarCorsCache(Clock.systemUTC());
    @Override public String getId() { return "avatars"; }
    @Override public void init(Config.Scope scope) {}
    private synchronized AvatarService service(String realmId, AvatarConfig config) {
        LocalAvatarStorage store = stores.get(realmId);
        if (store == null) {
            try { store = new LocalAvatarStorage(config.root(), config.maxBytes()); stores.put(realmId, store); }
            catch (IOException | java.nio.channels.OverlappingFileLockException e) {
                org.jboss.logging.Logger.getLogger(getClass()).error("Avatar storage initialization failed; check directory permissions and exclusive node ownership");
                throw new AvatarException(503, "avatar_storage_unavailable");
            }
        }
        store.setQuota(config.maxBytes());
        return new AvatarService(config, store, new AvatarUrlSigner(config, Clock.systemUTC()));
    }
    @Override public RealmResourceProvider create(KeycloakSession session) {
        RealmModel realm = session.getContext().getRealm();
        var model = AvatarSettingsProviderFactory.find(realm);
        AvatarConfig config;
        if (model == null || !Boolean.parseBoolean(model.get("enabled", "false"))) {
            config = AvatarConfig.load(name -> null);
        } else {
            var values = AvatarSettingsProviderFactory.values(model, realm, AvatarSettingsProviderFactory.storageRoot(realm.getId()));
            // Domain-separated derived keys: no signing secrets in avatar settings, API responses, or environment variables.
            var active = session.keys().getActiveKey(realm, KeyUse.SIG, "HS256");
            List<String> keys = new ArrayList<>();
            try (var stream = session.keys().getKeysStream(realm, KeyUse.SIG, "HS256")) {
                stream.filter(key -> key.getStatus().isEnabled()).forEach(key -> {
                    try {
                        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                        mac.init(key.getSecretKey());
                        byte[] derived = mac.doFinal(("keycloak-avatar-url-v1:" + realm.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        // Keycloak key IDs may exceed the UI parser's 32-character label limit.
                        keys.add(LocalAvatarStorage.hash(key.getKid()).substring(0, 32) + ":" + Base64.getEncoder().encodeToString(derived));
                    } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Avatar key derivation failed", e); }
                });
            }
            values.put("signing-keys", String.join(",", keys));
            values.put("active-key", LocalAvatarStorage.hash(active.getKid()).substring(0, 32));
            config = AvatarConfig.load(values::get);
        }
        return new AvatarResource(session, config, () -> service(realm.getId(), config), cors);
    }
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public synchronized void close() {
        for (var store : stores.values()) try { store.close(); }
        catch (IOException e) { org.jboss.logging.Logger.getLogger(getClass()).warn("Avatar storage close failed"); }
        stores.clear();
    }
}
