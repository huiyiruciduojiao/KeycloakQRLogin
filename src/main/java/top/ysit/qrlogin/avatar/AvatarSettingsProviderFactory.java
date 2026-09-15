package top.ysit.qrlogin.avatar;

import org.keycloak.Config;
import org.keycloak.component.*;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.ui.extend.*;
import java.nio.file.Path;
import java.util.*;

/** Native Realm settings tab. Component REST supplies manage-realm authorization and admin events. */
public final class AvatarSettingsProviderFactory implements UiTabProviderFactory<Object> {
    public static final String ID = "avatarSettings";
    public static final String TYPE = UiTabProvider.class.getName();
    @Override public String getId() { return ID; }
    @Override public String getPath() { return "/:realm/realm-settings/:tab"; }
    @Override public Map<String,String> getParams() { return Map.of("tab", "avatars"); }
    @Override public String getHelpText() { return "Realm-scoped user avatar settings"; }
    @Override public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(property("enabled", "avatarEnabled", ProviderConfigProperty.BOOLEAN_TYPE, "false"),
                property("public-base-url", "avatarPublicBaseUrl", ProviderConfigProperty.STRING_TYPE, null),
                property("allow-realm-clients", "avatarAllowRealmClients", ProviderConfigProperty.BOOLEAN_TYPE, "false"),
                property("clients", "avatarClients", ProviderConfigProperty.MULTIVALUED_STRING_TYPE, null),
                property("url-ttl-seconds", "avatarUrlTtl", ProviderConfigProperty.INTEGER_TYPE, "900"),
                property("max-storage-bytes", "avatarStorageQuota", ProviderConfigProperty.STRING_TYPE, "1073741824"));
    }
    private ProviderConfigProperty property(String name, String label, String type, String value) {
        return new ProviderConfigProperty(name, label, label + "Help", type, value);
    }
    static ComponentModel find(RealmModel realm) {
        try (var components = realm.getComponentsStream(realm.getId(), TYPE)) {
            var matches = components.filter(c -> ID.equals(c.getProviderId())).limit(2).toList();
            if (matches.size() > 1) throw new AvatarException(503, "ambiguous_avatar_configuration");
            return matches.isEmpty() ? null : matches.get(0);
        }
    }
    static Path storageRoot(String realmId) {
        String home = System.getProperty("kc.home.dir");
        if (home == null || home.isBlank()) throw new IllegalStateException("Keycloak installation directory unavailable");
        return Path.of(home).toAbsolutePath().normalize().resolve("data/avatars").resolve(LocalAvatarStorage.hash(realmId));
    }
    static Map<String,String> values(ComponentModel model, RealmModel realm, Path root) {
        Map<String,String> values = new HashMap<>();
        for (String key : List.of("enabled", "allow-realm-clients", "public-base-url", "url-ttl-seconds", "max-storage-bytes"))
            if (model.get(key) != null) values.put(key, model.get(key));
        values.put("clients", String.join(",", model.getConfig().getOrDefault("clients", List.of())));
        values.put("realms", realm.getName()); values.put("storage-path", root.toString());
        return values;
    }
    @Override public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model) {
        if (!realm.getId().equals(model.getParentId())) throw new ComponentValidationException("avatarInvalidParent");
        ComponentModel previous = find(realm);
        if (previous != null && !Objects.equals(previous.getId(), model.getId())) throw new ComponentValidationException("avatarDuplicateSettings");
        // Remove the retired field on save so existing Realm components remain editable.
        model.getConfig().remove("audience");
        for (String name : model.getConfig().keySet())
            if (!Set.of("enabled", "allow-realm-clients", "public-base-url", "clients", "url-ttl-seconds", "max-storage-bytes", "realm", "tab").contains(name))
                throw new ComponentValidationException("avatarUnknownSetting");
        for (var entry : model.getConfig().entrySet()) {
            if (entry.getValue() == null || entry.getValue().stream().anyMatch(Objects::isNull)
                    || (!entry.getKey().equals("clients") && entry.getValue().size() != 1))
                throw new ComponentValidationException("avatarInvalidSettings");
        }
        var clients = model.getConfig().getOrDefault("clients", List.of());
        if (!Boolean.parseBoolean(model.get("allow-realm-clients", "false"))
                && (clients.size() > 100 || clients.stream().anyMatch(c -> c.isBlank() || c.contains(",") || c.length() > 255)))
            throw new ComponentValidationException("avatarInvalidClients");
        // Validate UI fields without creating keys, writing files, or modifying client mappers during save.
        var values = values(model, realm, Path.of(".").toAbsolutePath());
        values.put("active-key", "validation");
        values.put("signing-keys", "validation:" + Base64.getEncoder().encodeToString(new byte[32]));
        try { AvatarConfig.load(values::get); }
        catch (IllegalArgumentException e) { throw new ComponentValidationException("avatarInvalidSettings"); }
        if (Boolean.parseBoolean(model.get("enabled", "false")) && !Boolean.parseBoolean(model.get("allow-realm-clients", "false"))) {
            for (String id : clients) {
                ClientModel client = session.clients().getClientByClientId(realm, id.trim());
                if (client == null || !client.isEnabled()) throw new ComponentValidationException("avatarInvalidClients");
            }
        }
        model.setName(ID);
    }
    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}
}
