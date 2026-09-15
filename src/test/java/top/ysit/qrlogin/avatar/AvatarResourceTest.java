package top.ysit.qrlogin.avatar;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.models.*;
import org.keycloak.http.FormPartValue;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;
import java.io.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarResourceTest {
    @TempDir Path root;
    private final KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    private final UserModel user = mock(UserModel.class);
    private final ClientModel client = mock(ClientModel.class);
    private final UserSessionModel userSession = mock(UserSessionModel.class);
    private final AccessToken token = new AccessToken();
    private final Supplier<AuthenticationManager.AuthResult> bearer = mock(Supplier.class);
    private AvatarConfig config;
    private AvatarService service;
    private AvatarResource resource;
    private AuthenticationManager.AuthResult auth;
    @BeforeEach void setup() throws Exception {
        config = AvatarTestSupport.config(root);
        service = new AvatarService(config, new LocalAvatarStorage(root, config.maxBytes()), new AvatarUrlSigner(config, Clock.systemUTC()));
        when(session.getContext().getRealm().getId()).thenReturn("r");
        when(session.getContext().getRealm().getName()).thenReturn("example");
        when(session.getContext().getRealm().isEnabled()).thenReturn(true);
        var realm = session.getContext().getRealm();
        when(userSession.getRealm()).thenReturn(realm); when(userSession.getId()).thenReturn("session-one");
        when(user.getId()).thenReturn("alice"); when(user.isEnabled()).thenReturn(true);
        when(session.users().getUserById(session.getContext().getRealm(), "alice")).thenReturn(user);
        when(session.clients().getClientByClientId(session.getContext().getRealm(), "web")).thenReturn(client);
        when(client.isEnabled()).thenReturn(true); when(client.getId()).thenReturn("web-id");
        when(client.getWebOrigins()).thenReturn(Set.of("https://app.example.test"));
        token.issuedFor("web");
        auth = new AuthenticationManager.AuthResult(user, userSession, token, client);
        when(bearer.get()).thenReturn(auth);
        var tracing = mock(org.keycloak.tracing.TracingProvider.class, RETURNS_DEEP_STUBS);
        doReturn(tracing).when(session).getProvider(org.keycloak.tracing.TracingProvider.class);
        resource = new AvatarResource(session, config, () -> service, bearer);
    }
    @AfterEach void close() throws Exception { service.storage.close(); }
    private InputStream request(String json) { return new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private void uploadForm() throws Exception {
        var parts = new MultivaluedHashMap<String, FormPartValue>();
        var file = mock(FormPartValue.class); when(file.asInputStream()).thenReturn(new ByteArrayInputStream(AvatarTestSupport.png(100, 200)));
        parts.add("file", file); when(session.getContext().getHttpRequest().getMultiPartFormParameters()).thenReturn(parts);
    }
    @Test void meAndBatchAcceptTokensWithoutAudienceAndDoNotExposeTokens() throws Exception {
        try (Response response = resource.batch(request("{\"userIds\":[\"alice\",\"missing\",\"alice\"]}"))) {
            assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
            var json = JsonSerialization.mapper.valueToTree(response.getEntity());
            assertEquals(2, json.path("items").size()); assertEquals("DEFAULT", json.path("items").get(0).path("status").asText());
            assertEquals("UNAVAILABLE", json.path("items").get(1).path("status").asText());
            assertFalse(json.toString().contains("access_token"));
        }
        assertEquals(200, resource.me(256).getStatus()); verify(bearer, times(2)).get();
    }
    @Test void rejectsMissingSessionDisabledUserServiceAccountAndWrongClientOrRealm() {
        when(bearer.get()).thenReturn(null); assertEquals(401, resource.me(128).getStatus());
        when(bearer.get()).thenReturn(new AuthenticationManager.AuthResult(user, null, token, client));
        assertEquals(401, resource.me(128).getStatus()); when(bearer.get()).thenReturn(auth);
        when(user.isEnabled()).thenReturn(false); assertEquals(403, resource.me(128).getStatus()); when(user.isEnabled()).thenReturn(true);
        when(user.getServiceAccountClientLink()).thenReturn("svc"); assertEquals(403, resource.me(128).getStatus()); when(user.getServiceAccountClientLink()).thenReturn(null);
        when(client.isEnabled()).thenReturn(false); assertEquals(403, resource.me(128).getStatus()); when(client.isEnabled()).thenReturn(true);
        token.issuedFor("other"); assertEquals(403, resource.me(128).getStatus()); token.issuedFor("web");
        var otherRealm = mock(RealmModel.class); when(otherRealm.getId()).thenReturn("other"); when(userSession.getRealm()).thenReturn(otherRealm);
        assertEquals(403, resource.me(128).getStatus());
    }
    @Test void uploadAndDeleteAffectOnlyCallerAndOldUrlsFailEvenWithMatchingEtag() throws Exception {
        uploadForm(); assertEquals(200, resource.upload().getStatus());
        String asset = service.storage.current("r", "alice"); assertNotNull(asset); assertNull(service.storage.current("r", "bob"));
        long expires = service.signer.expires(); String sig = service.signer.sign("image", "r", asset, "128", expires, "v1");
        var image = resource.content(asset, 128, expires, "v1", sig);
        assertEquals(200, image.getStatus()); assertEquals("image/png", image.getMediaType().toString());
        when(session.getContext().getRequestHeaders().getHeaderString("If-None-Match")).thenReturn(image.getHeaderString("ETag"));
        assertEquals(304, resource.content(asset, 128, expires, "v1", sig).getStatus());
        assertEquals(404, resource.content(asset, 256, expires, "v1", sig).getStatus());
        when(user.isEnabled()).thenReturn(false); assertEquals(404, resource.content(asset, 128, expires, "v1", sig).getStatus()); when(user.isEnabled()).thenReturn(true);
        assertEquals(200, resource.delete().getStatus()); assertEquals(404, resource.content(asset, 128, expires, "v1", sig).getStatus());
    }
    @Test void deniesTargetUserFieldAndInvalidRequestsBeforeWriting() throws Exception {
        uploadForm(); session.getContext().getHttpRequest().getMultiPartFormParameters().add("userId", mock(FormPartValue.class));
        assertEquals(400, resource.upload().getStatus()); assertNull(service.storage.current("r", "alice"));
        assertEquals(400, resource.batch(request("not-json")).getStatus());
        assertEquals(400, resource.batch(request("null")).getStatus());
        assertEquals(413, resource.batch(new ByteArrayInputStream(new byte[65537])).getStatus());
        assertEquals(400, resource.me(33).getStatus());
    }
    @Test void clientCorsRequiresExactOrigin() {
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://app.example.test");
        assertEquals("https://app.example.test", resource.me(128).getHeaderString("Access-Control-Allow-Origin"));
        assertEquals(204, resource.options("batch").getStatus());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://evil.test");
        // A real resource is request scoped; use a fresh instance for the unrelated request.
        var fresh = new AvatarResource(session, config, () -> service, bearer);
        assertEquals(403, fresh.me(128).getStatus()); assertNull(fresh.me(128).getHeaderString("Access-Control-Allow-Origin"));
        assertEquals(403, fresh.options("account/me").getStatus());
    }
    @Test void imageCorsCoversSignedDefaultCachedAndPreflightResponses() throws Exception {
        uploadForm(); assertEquals(200, resource.upload().getStatus());
        String asset = service.storage.current("r", "alice"); long expires = service.signer.expires();
        String sig = service.signer.sign("image", "r", asset, "128", expires, "v1");
        when(session.clients().getClientsStream(session.getContext().getRealm())).thenAnswer(i -> java.util.stream.Stream.of(client));
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://app.example.test");
        var image = resource.content(asset, 128, expires, "v1", sig);
        assertEquals(200, image.getStatus()); assertEquals("https://app.example.test", image.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("Origin", image.getHeaderString("Vary")); assertNull(image.getHeaderString("Access-Control-Allow-Credentials"));
        when(session.getContext().getRequestHeaders().getHeaderString("If-None-Match")).thenReturn(image.getHeaderString("ETag"));
        var cached = resource.content(asset, 128, expires, "v1", sig);
        assertEquals(304, cached.getStatus()); assertEquals("https://app.example.test", cached.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("https://app.example.test", resource.defaultImage(128).getHeaderString("Access-Control-Allow-Origin"));
        when(session.getContext().getRequestHeaders().getHeaderString("Access-Control-Request-Method")).thenReturn("GET");
        assertEquals(204, resource.options("content/" + asset + "/128").getStatus());
        assertEquals(404, resource.content(asset, 128, expires, "v1", "bad").getStatus());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://evil.test");
        assertEquals(403, resource.content(asset, 128, expires, "v1", sig).getStatus());
    }
    @Test void realmClientsAllowsUnlistedClientAndPreflightButRejectsDisabledAndUnknown() {
        var all = new AvatarConfig(config.enabled(), config.root(), config.publicBase(), config.realms(), Set.of(), true,
                config.keys(), config.activeKey(), config.ttlSeconds(), config.maxBytes());
        resource = new AvatarResource(session, all, () -> service, bearer);
        token.issuedFor("unlisted");
        when(session.clients().getClientByClientId(session.getContext().getRealm(), "unlisted")).thenReturn(client);
        assertEquals(200, resource.me(128).getStatus());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://app.example.test");
        when(session.clients().getClientsStream(session.getContext().getRealm())).thenAnswer(i -> java.util.stream.Stream.of(client));
        assertEquals(204, resource.options("batch").getStatus());
        when(client.isEnabled()).thenReturn(false);
        assertEquals(403, resource.me(128).getStatus());
        token.issuedFor("missing"); assertEquals(403, resource.me(128).getStatus());
        token.issuedFor(null); assertEquals(403, resource.me(128).getStatus());
    }
    @Test void selfReadBypassesAllowlistButMutationsAndBatchDoNot() throws Exception {
        token.issuedFor("unlisted");
        when(session.clients().getClientByClientId(session.getContext().getRealm(), "unlisted")).thenReturn(client);
        assertEquals(200, resource.me(64).getStatus());
        assertEquals(403, resource.delete().getStatus());
        assertEquals(403, resource.upload().getStatus());
        assertEquals(403, resource.batch(request("{\"userIds\":[\"alice\"]}")).getStatus());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://app.example.test");
        when(session.getContext().getRequestHeaders().getHeaderString("Access-Control-Request-Method")).thenReturn("GET");
        when(session.clients().getClientsStream(session.getContext().getRealm())).thenAnswer(i -> java.util.stream.Stream.of(client));
        assertEquals(204, resource.options("me").getStatus());
        when(client.isEnabled()).thenReturn(false); assertEquals(403, resource.me(64).getStatus());
    }
    @Test void accountConsoleUsesSameBearerContractAndSameOrigin() throws Exception {
        token.issuedFor("account-console");
        when(session.clients().getClientByClientId(session.getContext().getRealm(), "account-console")).thenReturn(client);
        when(client.getWebOrigins()).thenReturn(Set.of());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://sso.example.test");
        uploadForm(); assertEquals(200, resource.upload().getStatus());
        when(bearer.get()).thenReturn(null);
        assertEquals(401, resource.delete().getStatus());
    }
    @Test void disabledFeatureDoesNotInitializeStorage() {
        Supplier<AvatarService> factory = mock(Supplier.class);
        var disabled = new AvatarResource(session, AvatarConfig.load(name -> null), factory, bearer);
        assertEquals(404, disabled.me(128).getStatus()); verifyNoInteractions(factory, bearer);
    }
    @Test void storageFailuresAreNotReportedAsDefaultAvatars() throws Exception {
        AvatarStorage failed = mock(AvatarStorage.class); when(failed.current("r", "alice")).thenThrow(new IOException("private-path"));
        var broken = new AvatarService(config, failed, service.signer);
        var endpoint = new AvatarResource(session, config, () -> broken, bearer);
        var result = endpoint.me(128); assertEquals(503, result.getStatus());
        assertFalse(result.getEntity().toString().contains("private-path"));
    }
    @Test void missingImageRuntimeIsAControlledFailure() {
        var broken = new AvatarResource(session, config,
                () -> { throw new UnsatisfiedLinkError("/private/libawt.so: missing"); }, bearer);
        var result = broken.me(128);
        assertEquals(503, result.getStatus());
        assertEquals("avatar_image_runtime_unavailable", ((Map<?, ?>) result.getEntity()).get("error"));
        assertFalse(result.getEntity().toString().contains("private"));
    }
    @Test void preflightScansRealmClientsOncePerTtl() {
        var clock = new AvatarTestSupport.MutableClock();
        resource = new AvatarResource(session, config, () -> service, bearer, new AvatarCorsCache(clock));
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://app.example.test");
        when(session.getContext().getRequestHeaders().getHeaderString("Access-Control-Request-Method")).thenReturn("GET");
        when(session.clients().getClientsStream(session.getContext().getRealm())).thenAnswer(i -> java.util.stream.Stream.of(client));
        assertEquals(204, resource.options("me").getStatus());
        assertEquals(204, resource.options("me").getStatus());
        verify(session.clients(), times(1)).getClientsStream(session.getContext().getRealm());
        clock.now = clock.now.plusSeconds(61);
        assertEquals(204, resource.options("me").getStatus());
        verify(session.clients(), times(2)).getClientsStream(session.getContext().getRealm());
        // A cached snapshot never widens access: unknown origins stay rejected without rescan.
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn("https://evil.test");
        var rejected = resource.options("me");
        assertEquals(403, rejected.getStatus());
        assertNull(rejected.getHeaderString("Access-Control-Allow-Origin"));
        verify(session.clients(), times(2)).getClientsStream(session.getContext().getRealm());
        when(session.getContext().getRequestHeaders().getHeaderString("Origin")).thenReturn(null);
        var noOrigin = resource.me(64);
        assertEquals(200, noOrigin.getStatus());
        assertNull(noOrigin.getHeaderString("Access-Control-Allow-Origin"));
    }
    @Test void rejectionPathsEmitAuditEventsWithErrorCodeOnly() {
        var codes = new ArrayList<String>();
        resource = new AvatarResource(session, config, () -> service, bearer, new AvatarCorsCache(Clock.systemUTC()), codes::add);
        when(bearer.get()).thenReturn(null);
        assertEquals(401, resource.me(128).getStatus());
        when(bearer.get()).thenReturn(auth);
        token.issuedFor("other");
        assertEquals(403, resource.me(128).getStatus());
        token.issuedFor("web");
        // Input errors are not rejection probes and must not produce events.
        assertEquals(400, resource.me(33).getStatus());
        assertEquals(List.of("invalid_session", "client_not_allowed"), codes);
    }
}
