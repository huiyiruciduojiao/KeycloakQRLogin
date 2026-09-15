package top.ysit.qrlogin.avatar;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.keycloak.models.*;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.protocol.oidc.utils.WebOriginsUtils;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.util.JsonSerialization;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Produces(MediaType.APPLICATION_JSON)
@jakarta.ws.rs.ext.Provider
public final class AvatarResource implements RealmResourceProvider {
    public record BatchRequest(List<String> userIds, Integer size) {}
    private final KeycloakSession session;
    private final AvatarConfig config;
    private final Supplier<AvatarService> services;
    private final Supplier<AuthenticationManager.AuthResult> bearer;
    private final AvatarCorsCache cors;
    private final Consumer<String> rejectionAudit;
    private String corsOrigin;
    AvatarResource(KeycloakSession session, AvatarConfig config, Supplier<AvatarService> services, AvatarCorsCache cors) {
        this(session, config, services, () -> new AppAuthManager.BearerTokenAuthenticator(session).authenticate(), cors);
    }
    AvatarResource(KeycloakSession session, AvatarConfig config, Supplier<AvatarService> services,
                   Supplier<AuthenticationManager.AuthResult> bearer) {
        this(session, config, services, bearer, new AvatarCorsCache(Clock.systemUTC()));
    }
    AvatarResource(KeycloakSession session, AvatarConfig config, Supplier<AvatarService> services,
                   Supplier<AuthenticationManager.AuthResult> bearer, AvatarCorsCache cors) {
        this(session, config, services, bearer, cors, null);
    }
    AvatarResource(KeycloakSession session, AvatarConfig config, Supplier<AvatarService> services,
                   Supplier<AuthenticationManager.AuthResult> bearer, AvatarCorsCache cors, Consumer<String> rejectionAudit) {
        this.session = session; this.config = config; this.services = services; this.bearer = bearer;
        this.cors = cors; this.rejectionAudit = rejectionAudit == null ? this::auditRejectionEvent : rejectionAudit;
    }
    @GET @Path("me") public Response me(@QueryParam("size") @DefaultValue("128") int size) {
        return run(() -> { var auth = authenticate(true); return json(service().item(realmId(), realm().getName(), auth.user().getId(), size)); });
    }
    @PUT @Path("me") @Consumes(MediaType.MULTIPART_FORM_DATA) public Response upload() { return update(false); }
    @DELETE @Path("me") public Response delete() { return update(true); }
    @POST @Path("batch") @Consumes(MediaType.APPLICATION_JSON) public Response batch(InputStream input) {
        return run(() -> {
            authenticate();
            byte[] data = input.readNBytes(64 * 1024 + 1);
            if (data.length > 64 * 1024) throw new AvatarException(413, "request_too_large");
            BatchRequest request;
            try { request = JsonSerialization.readValue(data, BatchRequest.class); }
            catch (IOException e) { throw new AvatarException(400, "invalid_request"); }
            if (request == null) throw new AvatarException(400, "invalid_request");
            return json(Map.of("items", service().batch(realmId(), realm().getName(), request.userIds(),
                    request.size() == null ? 128 : request.size(), this::visible)));
        });
    }
    private Response update(boolean remove) {
        return run(() -> {
            var auth = authenticate();
            String user = auth.user().getId();
            if (remove) {
                service().storage.delete(realmId(), user); audit(auth, "delete");
                return json(service().item(realmId(), realm().getName(), user, 128));
            }
            String length = header("Content-Length");
            if (length != null) {
                try { if (Long.parseLong(length) > AvatarImages.MAX_UPLOAD + 16 * 1024) throw new AvatarException(413, "image_too_large"); }
                catch (NumberFormatException e) { throw new AvatarException(400, "invalid_request"); }
            }
            var parts = session.getContext().getHttpRequest().getMultiPartFormParameters();
            if (parts == null || parts.size() != 1 || parts.get("file") == null || parts.get("file").size() != 1)
                throw new AvatarException(400, "expected_single_file");
            AvatarService.Item result;
            try (InputStream image = parts.getFirst("file").asInputStream()) {
                result = service().upload(realmId(), realm().getName(), user, image);
            }
            audit(auth, "upload"); return json(result);
        });
    }
    @GET @Path("content/{asset}/{size}") @Produces("image/png")
    public Response content(@PathParam("asset") String asset, @PathParam("size") int size,
                            @QueryParam("exp") @DefaultValue("0") long expires,
                            @QueryParam("kid") String kid, @QueryParam("sig") String signature) {
        return run(() -> {
            enabled(); imageCors(); AvatarImages.validateSize(size);
            var service = service();
            if (!LocalAvatarStorage.validAsset(asset) || !service.signer.verify("image", realmId(), asset, Integer.toString(size), expires, kid, signature))
                throw new AvatarException(404, "avatar_unavailable");
            var image = service.storage.read(realmId(), asset, size);
            if (image == null || !visible(image.userId())) throw new AvatarException(404, "avatar_unavailable");
            return image(image.bytes(), asset + "-" + size, Math.min(300, service.signer.remaining(expires)));
        });
    }
    @GET @Path("default/{size}") @Produces("image/png") public Response defaultImage(@PathParam("size") int size) {
        return run(() -> { enabled(); imageCors(); return image(AvatarImages.defaultImage(size), "default-v1-" + size, 86400); });
    }
    @OPTIONS @Path("{path:.*}") public Response options(@PathParam("path") String path) {
        return run(() -> {
            enabled();
            boolean imagePath = path.matches("content/[a-f0-9]{32}/(64|128|256)|default/(64|128|256)");
            if (imagePath && !"GET".equals(header("Access-Control-Request-Method"))) throw new AvatarException(403, "origin_not_allowed");
            if (!imagePath && !Set.of("me", "batch").contains(path)) throw new AvatarException(403, "origin_not_allowed");
            String origin = header("Origin");
            if (origin == null || origin.equals("null")) throw new AvatarException(403, "origin_not_allowed");
            boolean selfRead = path.equals("me") && "GET".equals(header("Access-Control-Request-Method"));
            if (cors.allows(session, realm(), config, imagePath || selfRead || config.allowRealmClients(), origin)) corsOrigin = origin;
            if (corsOrigin == null) throw new AvatarException(403, "origin_not_allowed");
            return response(Response.noContent().header("Access-Control-Allow-Methods", imagePath ? "GET, OPTIONS" : "GET, PUT, DELETE, POST, OPTIONS")
                    .header("Access-Control-Allow-Headers", imagePath ? "If-None-Match" : "Authorization, Content-Type").header("Access-Control-Max-Age", "300"));
        });
    }
    private AuthenticationManager.AuthResult authenticate() {
        return authenticate(false);
    }
    private AuthenticationManager.AuthResult authenticate(boolean selfRead) {
        enabled();
        var auth = bearer.get();
        if (auth == null || auth.user() == null || auth.session() == null || auth.token() == null)
            throw new AvatarException(401, "invalid_session");
        if (!realmId().equals(auth.session().getRealm().getId()) || !visible(auth.user().getId()))
            throw new AvatarException(403, "not_allowed");
        String clientId = auth.token().getIssuedFor();
        if (clientId == null || (!selfRead && !config.allowRealmClients() && !config.clients().contains(clientId))) throw new AvatarException(403, "client_not_allowed");
        ClientModel client = session.clients().getClientByClientId(realm(), clientId);
        if (client == null || !client.isEnabled()) throw new AvatarException(403, "client_not_allowed");
        String origin = header("Origin");
        if (origin != null) {
            if (!allowedOrigin(client, origin)) throw new AvatarException(403, "origin_not_allowed");
            corsOrigin = origin;
        }
        return auth;
    }
    private boolean allowedOrigin(ClientModel client, String origin) {
        // Wildcards are deliberately not honored for these APIs; '+' is resolved by Keycloak against redirect URIs.
        String serverOrigin = config.publicBase().getScheme() + "://" + config.publicBase().getRawAuthority();
        return origin.equals(serverOrigin) || (!origin.equals("null") && !origin.equals("*") && WebOriginsUtils.resolveValidWebOrigins(session, client).contains(origin));
    }
    private void imageCors() {
        String origin = header("Origin");
        if (origin == null) return;
        // Signed URLs carry no client ID and may originate from unrestricted GET /me.
        if ("null".equals(origin) || "*".equals(origin) || !cors.allows(session, realm(), config, true, origin))
            throw new AvatarException(403, "origin_not_allowed");
        corsOrigin = origin;
    }
    private boolean visible(String id) {
        var user = session.users().getUserById(realm(), id);
        return user != null && user.isEnabled() && user.getServiceAccountClientLink() == null;
    }
    private void enabled() {
        if (!config.enabled() || !config.realms().contains(realm().getName()) || !realm().isEnabled())
            throw new AvatarException(404, "avatars_disabled");
    }
    private AvatarService service() { return services.get(); }
    private RealmModel realm() { return session.getContext().getRealm(); }
    private String realmId() { return realm().getId(); }
    private String header(String name) { return session.getContext().getRequestHeaders().getHeaderString(name); }
    private Response json(Object entity) { return response(Response.ok(entity)); }
    private Response response(Response.ResponseBuilder builder) {
        builder.type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff");
        if (corsOrigin != null) builder.header("Access-Control-Allow-Origin", corsOrigin).header("Vary", "Origin");
        return builder.build();
    }
    private Response image(byte[] bytes, String version, long maxAge) {
        String etag = '"' + version + '"';
        // Authorization and version checks must precede a 304 response.
        Response.ResponseBuilder builder = etag.equals(header("If-None-Match")) ? Response.status(304) : Response.ok(bytes, "image/png");
        builder.header("Vary", "Origin");
        if (corsOrigin != null) builder.header("Access-Control-Allow-Origin", corsOrigin).header("Access-Control-Expose-Headers", "ETag");
        return builder.header("ETag", etag).header("Cache-Control", "private, max-age=" + maxAge + ", must-revalidate")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer").build();
    }
    private Response run(Operation operation) {
        corsOrigin = null;
        try { return operation.run(); }
        catch (AvatarException e) {
            // Rejection probes are auditable without user details; input and storage errors are not.
            if (e.status == 401 || e.status == 403 || e.status == 404) rejectionAudit.accept(e.code);
            return response(Response.status(e.status).entity(Map.of("error", e.code)));
        }
        catch (IOException e) {
            org.jboss.logging.Logger.getLogger(getClass()).error("Avatar storage or request I/O failed");
            return response(Response.status(503).entity(Map.of("error", "avatar_storage_unavailable")));
        }
        catch (LinkageError e) {
            // Do not expose native library paths. This remains a controlled API
            // failure if a custom JDK lacks the headless java.desktop runtime.
            org.jboss.logging.Logger.getLogger(getClass()).error(
                    "Avatar image runtime unavailable; verify the JDK java.desktop headless runtime", e);
            return response(Response.status(503).entity(Map.of("error", "avatar_image_runtime_unavailable")));
        }
    }
    private void auditRejectionEvent(String code) {
        new EventBuilder(realm(), session, session.getContext().getConnection()).event(EventType.UPDATE_PROFILE_ERROR)
                .detail("avatar_action", "reject").error(code);
    }
    private void audit(AuthenticationManager.AuthResult auth, String action) {
        new EventBuilder(realm(), session, session.getContext().getConnection()).event(EventType.UPDATE_PROFILE)
                .user(auth.user()).session(auth.session()).detail("avatar_action", action).success();
    }
    @FunctionalInterface private interface Operation { Response run() throws IOException; }
    @Override public Object getResource() { return this; }
    @Override public void close() {}
}
