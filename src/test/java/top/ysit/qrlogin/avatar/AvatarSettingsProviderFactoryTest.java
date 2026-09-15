package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import org.keycloak.component.*;
import org.keycloak.models.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarSettingsProviderFactoryTest {
    private final AvatarSettingsProviderFactory factory = new AvatarSettingsProviderFactory();
    private final KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    private final RealmModel realm = mock(RealmModel.class);
    private ComponentModel form() {
        when(realm.getId()).thenReturn("realm-id"); when(realm.getName()).thenReturn("example");
        when(realm.getComponentsStream("realm-id", AvatarSettingsProviderFactory.TYPE)).thenAnswer(inv -> Stream.empty());
        var client = mock(ClientModel.class); when(client.isEnabled()).thenReturn(true);
        when(session.clients().getClientByClientId(realm, "account-console")).thenReturn(client);
        var model = new ComponentModel(); model.setParentId("realm-id"); model.setProviderId(AvatarSettingsProviderFactory.ID);
        model.put("enabled", "true"); model.put("public-base-url", "https://sso.test/auth");
        model.put("clients", "account-console");
        return model;
    }
    @Test void validatesNativeFormAndExposesNoSecretOrHostPathFields() {
        var model = form(); model.put("public-base-url", "http://sso.test");
        model.put("audience", "legacy"); factory.validateConfiguration(session, realm, model);
        assertFalse(model.getConfig().containsKey("audience"));
        assertEquals("avatarSettings", model.getName());
        assertEquals("/:realm/realm-settings/:tab", factory.getPath());
        assertEquals("avatars", factory.getParams().get("tab"));
        assertEquals(Set.of("enabled", "allow-realm-clients", "public-base-url", "clients", "url-ttl-seconds", "max-storage-bytes"),
                factory.getConfigProperties().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void rejectsUnknownClientsWrongParentAndInjectedSecrets() {
        var model = form(); model.put("clients", "unknown");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
        model.put("clients", "account-console"); model.setParentId("other");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
        model.setParentId("realm-id"); model.put("signing-keys", "injected");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
    }
    @Test void rejectsInvalidValuesAndDuplicateConfigsButAllowsCurrentUpdate() {
        var model = form(); model.put("url-ttl-seconds", "99999");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
        model.put("url-ttl-seconds", "900");
        var existing = new ComponentModel(); existing.setId("old"); existing.setProviderId(AvatarSettingsProviderFactory.ID);
        when(realm.getComponentsStream("realm-id", AvatarSettingsProviderFactory.TYPE)).thenAnswer(inv -> Stream.of(existing));
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
        model.setId("old"); assertDoesNotThrow(() -> factory.validateConfiguration(session, realm, model));
        when(realm.getComponentsStream("realm-id", AvatarSettingsProviderFactory.TYPE)).thenAnswer(inv -> Stream.of(existing, existing));
        assertThrows(AvatarException.class, () -> AvatarSettingsProviderFactory.find(realm));
    }
    @Test void disablingDoesNotRequireMissingClientOrUrlAndDoesNotTouchFiles() {
        var model = form(); model.put("enabled", "false"); model.getConfig().remove("clients"); model.getConfig().remove("public-base-url");
        assertDoesNotThrow(() -> factory.validateConfiguration(session, realm, model));
    }
    @Test void realmClientsAllowsEmptyListAndRestoresValidationWhenDisabled() {
        var model = form(); model.getConfig().remove("clients"); model.put("allow-realm-clients", "true");
        assertDoesNotThrow(() -> factory.validateConfiguration(session, realm, model));
        model.put("allow-realm-clients", "false");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
        model.put("allow-realm-clients", "invalid");
        assertThrows(ComponentValidationException.class, () -> factory.validateConfiguration(session, realm, model));
    }
}
