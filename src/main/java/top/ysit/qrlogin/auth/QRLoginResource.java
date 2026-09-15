package top.ysit.qrlogin.auth;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.Map;
import java.util.function.Function;

@Produces(MediaType.APPLICATION_JSON)
@jakarta.ws.rs.ext.Provider
public final class QRLoginResource implements RealmResourceProvider {
    private final KeycloakSession session;
    private final QRTransactionStore store;
    private final Function<String, AuthenticationManager.AuthResult> authenticate;
    private final QRMobileAudit audit;

    public QRLoginResource(KeycloakSession session, QRTransactionStore store) {
        this(session, store, audience -> new AppAuthManager.BearerTokenAuthenticator(session)
                .setAudience(audience).authenticate(), new KeycloakQRMobileAudit(session));
    }

    QRLoginResource(KeycloakSession session, QRTransactionStore store,
                    Function<String, AuthenticationManager.AuthResult> authenticate) {
        this(session, store, authenticate, new KeycloakQRMobileAudit(session));
    }

    QRLoginResource(KeycloakSession session, QRTransactionStore store,
                    Function<String, AuthenticationManager.AuthResult> authenticate, QRMobileAudit audit) {
        this.session = session;
        this.store = store;
        this.authenticate = authenticate;
        this.audit = audit;
    }

    @POST
    @Path("transactions/{id}/scan")
    public Response scan(@PathParam("id") String id) {
        return update(id, QRTransactionStore.Status.SCANNED);
    }

    @POST
    @Path("transactions/{id}/confirm")
    public Response confirm(@PathParam("id") String id) {
        return update(id, QRTransactionStore.Status.CONFIRMED);
    }

    @POST
    @Path("transactions/{id}/deny")
    public Response deny(@PathParam("id") String id) {
        return update(id, QRTransactionStore.Status.DENIED);
    }

    private Response update(String id, QRTransactionStore.Status target) {
        QRMobileAudit.Action action = switch (target) {
            case SCANNED -> QRMobileAudit.Action.SCAN;
            case CONFIRMED -> QRMobileAudit.Action.CONFIRM;
            case DENIED -> QRMobileAudit.Action.DENY;
            default -> throw new IllegalArgumentException("Unsupported mobile QR action");
        };
        if (id == null || !id.matches("[A-Za-z0-9_-]{43}"))
            return auditedError(action, QRMobileAudit.Outcome.TRANSACTION_NOT_FOUND,
                    id, null, null, 404, "transaction_not_found");
        String realm = session.getContext().getRealm().getId();
        QRTransactionStore.Transaction t = store.get(realm, id);
        if (t == null) return auditedError(action, QRMobileAudit.Outcome.TRANSACTION_NOT_FOUND,
                id, null, null, 404, "transaction_not_found");
        AuthenticationManager.AuthResult auth = authenticate.apply(t.audience());
        if (auth == null || auth.getUser() == null || auth.getSession() == null || auth.getToken() == null)
            return auditedError(action, QRMobileAudit.Outcome.INVALID_TOKEN,
                    id, t, auth, 401, "invalid_token");
        var client = session.clients().getClientByClientId(session.getContext().getRealm(), t.mobileClient());
        if (!auth.getUser().isEnabled() || auth.getUser().getServiceAccountClientLink() != null
                || client == null || !client.isEnabled()
                || !t.mobileClient().equals(auth.getToken().getIssuedFor()))
            return auditedError(action, QRMobileAudit.Outcome.NOT_ALLOWED,
                    id, t, auth, 403, "not_allowed");
        QRTransactionStore.Transaction result = store.advance(realm, id, auth.getUser().getId(), target);
        if (result == null) return auditedError(action, QRMobileAudit.Outcome.INVALID_TRANSITION,
                id, t, auth, 409, "invalid_transition");
        audit.record(action, QRMobileAudit.Outcome.SUCCESS, id, result, auth);
        return Response.ok(Map.of("status", result.status().name(), "user_code", result.userCode(),
                        "application", result.application(), "expires_at", result.expiresAt().toEpochMilli()))
                .header("Cache-Control", "no-store").build();
    }

    private Response error(int status, String code) {
        return Response.status(status).entity(Map.of("error", code)).header("Cache-Control", "no-store").build();
    }

    private Response auditedError(QRMobileAudit.Action action, QRMobileAudit.Outcome outcome,
                                  String id, QRTransactionStore.Transaction transaction,
                                  AuthenticationManager.AuthResult authentication,
                                  int status, String code) {
        audit.record(action, outcome, id, transaction, authentication);
        return error(status, code);
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {
    }
}
