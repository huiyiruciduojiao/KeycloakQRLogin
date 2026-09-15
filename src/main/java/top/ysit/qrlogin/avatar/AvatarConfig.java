package top.ysit.qrlogin.avatar;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Explicit opt-in, with no development secrets or inferred public host. */
record AvatarConfig(boolean enabled, Path root, URI publicBase, Set<String> realms,
                    Set<String> clients, boolean allowRealmClients, Map<String, byte[]> keys, String activeKey,
                    int ttlSeconds, long maxBytes) {
    static AvatarConfig load(Function<String, String> read) {
        if (!bool(read.apply("enabled"), false))
            return new AvatarConfig(false, null, null, Set.of(), Set.of(), false, Map.of(), null, 900, 0);
        Path root = Path.of(required(read, "storage-path"));
        if (!root.isAbsolute()) throw invalid("storage-path must be absolute");
        URI base = URI.create(required(read, "public-base-url"));
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null
                || !base.normalize().equals(base)) throw invalid("public-base-url must be a canonical HTTP or HTTPS URL");
        var keys = new LinkedHashMap<String, byte[]>();
        for (String pair : required(read, "signing-keys").split(",", -1)) {
            String[] parts = pair.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,32}")) throw invalid("invalid signing key entry");
            byte[] key;
            try { key = Base64.getDecoder().decode(parts[1]); }
            catch (IllegalArgumentException e) { throw invalid("signing keys must be Base64"); }
            if (key.length < 32 || keys.putIfAbsent(parts[0], key) != null) throw invalid("short or duplicate signing key");
        }
        String active = required(read, "active-key");
        if (!keys.containsKey(active)) throw invalid("active-key is missing from signing-keys");
        int ttl = Integer.parseInt(value(read, "url-ttl-seconds", "900"));
        long quota = Long.parseLong(value(read, "max-storage-bytes", "1073741824"));
        if (ttl < 60 || ttl > 3600 || quota < 1048576) throw invalid("TTL or storage quota out of range");
        boolean realmClients = bool(read.apply("allow-realm-clients"), false);
        return new AvatarConfig(true, root.normalize(), base, csv(required(read, "realms")),
                realmClients ? Set.of() : csv(required(read, "clients")), realmClients, Map.copyOf(keys), active,
                ttl, quota);
    }
    private static Set<String> csv(String value) {
        Set<String> items = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            if (item.isBlank() || item.trim().equals("*")) throw invalid("empty or wildcard allowlist entry");
            items.add(item.trim());
        }
        return Set.copyOf(items);
    }
    private static boolean bool(String value, boolean fallback) {
        if (value == null) return fallback;
        if (!Set.of("true", "false").contains(value)) throw invalid("boolean must be true or false");
        return Boolean.parseBoolean(value);
    }
    private static String value(Function<String,String> read, String name, String fallback) {
        String value = read.apply(name); return value == null ? fallback : value;
    }
    private static String required(Function<String,String> read, String name) {
        String value = read.apply(name);
        if (value == null || value.isBlank()) throw invalid("missing " + name);
        return value.trim();
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("Avatar configuration: " + reason); }
}
