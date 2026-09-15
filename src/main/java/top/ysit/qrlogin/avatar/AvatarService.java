package top.ysit.qrlogin.avatar;

import jakarta.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

final class AvatarService {
    record Item(String userId, String status, String url, String version, String expiresAt) {}
    private final AvatarConfig config;
    final AvatarStorage storage;
    final AvatarUrlSigner signer;
    // Bound concurrent decoders independently of Keycloak's HTTP worker count.
    private static final Semaphore decoders = new Semaphore(2);
    AvatarService(AvatarConfig config, AvatarStorage storage, AvatarUrlSigner signer) {
        this.config = config; this.storage = storage; this.signer = signer;
    }
    List<Item> batch(String realmId, String realmName, List<String> users, int size, Predicate<String> visible) throws IOException {
        AvatarImages.validateSize(size);
        if (users == null || users.isEmpty() || users.size() > 100) throw new AvatarException(400, "invalid_user_ids");
        for (String id : users) if (id == null || id.isBlank() || id.length() > 1024)
            throw new AvatarException(400, "invalid_user_ids");
        List<Item> result = new ArrayList<>();
        for (String id : new LinkedHashSet<>(users))
            result.add(visible.test(id) ? item(realmId, realmName, id, size) : new Item(id, "UNAVAILABLE", null, null, null));
        return result;
    }
    Item item(String realmId, String realmName, String userId, int size) throws IOException {
        AvatarImages.validateSize(size);
        String asset = storage.current(realmId, userId);
        if (asset == null) return new Item(userId, "DEFAULT", base(realmName).path("default").path(Integer.toString(size)).build().toString(), "default-v1", null);
        long expires = signer.expires();
        String signature = signer.sign("image", realmId, asset, Integer.toString(size), expires, config.activeKey());
        String url = base(realmName).path("content").path(asset).path(Integer.toString(size))
                .queryParam("exp", expires).queryParam("kid", config.activeKey()).queryParam("sig", signature).build().toString();
        return new Item(userId, "CUSTOM", url, asset, Instant.ofEpochSecond(expires).toString());
    }
    Item upload(String realmId, String realmName, String userId, InputStream input) throws IOException {
        if (!decoders.tryAcquire()) throw new AvatarException(429, "image_processing_busy");
        try { storage.replace(realmId, userId, AvatarImages.process(input)); }
        finally { decoders.release(); }
        return item(realmId, realmName, userId, 128);
    }
    private UriBuilder base(String realmName) {
        return UriBuilder.fromUri(config.publicBase()).path("realms").segment(realmName).path("avatars");
    }
}
