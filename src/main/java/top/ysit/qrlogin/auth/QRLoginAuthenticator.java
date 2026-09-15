package top.ysit.qrlogin.auth;

import org.keycloak.authentication.*;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import top.ysit.qrlogin.core.util.QRCodeUtil;
import jakarta.ws.rs.core.UriBuilder;
import java.util.Map;

public final class QRLoginAuthenticator implements Authenticator {
    private static final String NOTE = "qr-login.transaction";
    private final QRTransactionStore store;
    public QRLoginAuthenticator(QRTransactionStore store) { this.store = store; }
    private QRTransactionStore.Binding binding(AuthenticationFlowContext c) {
        AuthenticationSessionModel s = c.getAuthenticationSession();
        return new QRTransactionStore.Binding(c.getRealm().getId(), s.getParentSession().getId(),
                s.getTabId(), s.getClient().getId(), c.getExecution().getId());
    }
    @Override public void authenticate(AuthenticationFlowContext c) {
        String id = c.getAuthenticationSession().getAuthNote(NOTE);
        QRTransactionStore.Transaction t = store.get(c.getRealm().getId(), id);
        if (t == null || !t.binding().equals(binding(c))) {
            try {
                Map<String, String> cfg = c.getAuthenticatorConfig() == null ? Map.of() : c.getAuthenticatorConfig().getConfig();
                if (cfg == null) cfg = Map.of();
                ClientModel client = c.getAuthenticationSession().getClient();
                t = store.create(binding(c), cfg.get("mobileClientId"), cfg.get("audience"),
                        client.getName() == null || client.getName().isBlank() ? client.getClientId() : client.getName(), //这里的客户端名称即使没有配置，也有可能不是NULL，有可能是一个空串，踩坑指南
                        Integer.parseInt(cfg.getOrDefault("ttlSeconds", "120")));
                c.getAuthenticationSession().setAuthNote(NOTE, t.id());
            } catch (IllegalArgumentException | IllegalStateException e) {
                c.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                        c.form().setError("qrUnavailable").createErrorPage(jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE));
                return;
            }
        }
        show(c, t);
    }
    private void show(AuthenticationFlowContext c, QRTransactionStore.Transaction t) {
        try {
            String endpoint = UriBuilder.fromUri(c.getUriInfo().getBaseUri()).path("realms")
                    .path(c.getRealm().getName()).path("qr-login").build().toString();
            String data = JsonSerialization.writeValueAsString(Map.of("v", 2, "type", "keycloak_qr_login",
                    "transaction", t.id(), "verification_uri", endpoint, "user_code", t.userCode(),
                    "expires_at", t.expiresAt().toEpochMilli()));
            c.challenge(c.form().setAttribute("qrImage", QRCodeUtil.toDataUrl(data, 320))
                    .setAttribute("qrCode", t.userCode()).setAttribute("qrState", t.status().name())
                    .setAttribute("qrExpiry", Long.toString(t.expiresAt().toEpochMilli()))
                    .createForm("qr-login.ftl"));
        } catch (Exception e) {
            c.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }
    @Override public void action(AuthenticationFlowContext c) {
        String id = c.getAuthenticationSession().getAuthNote(NOTE);
        String operation = c.getHttpRequest().getDecodedFormParameters().getFirst("operation");
        if ("dismiss".equals(operation)) {
            store.cancel(id, binding(c));
            c.getAuthenticationSession().removeAuthNote(NOTE);
            status(c, null);
            return;
        }
        if ("cancel".equals(operation) || "restart".equals(operation)) {
            store.cancel(id, binding(c)); c.getAuthenticationSession().removeAuthNote(NOTE);
            if ("cancel".equals(operation)) c.cancelLogin(); else authenticate(c);
            return;
        }
        QRTransactionStore.Transaction t = store.get(c.getRealm().getId(), id);
        if ("status".equals(operation)) { status(c, t); return; }
        if (t == null || !t.binding().equals(binding(c))) {
            c.failureChallenge(AuthenticationFlowError.EXPIRED_CODE,
                    c.form().setError("qrExpired").createErrorPage(jakarta.ws.rs.core.Response.Status.BAD_REQUEST));
            return;
        }
        if (t.status() == QRTransactionStore.Status.DENIED) { show(c, t); return; }
        if (t.status() == QRTransactionStore.Status.CANCELLED
                || t.status() == QRTransactionStore.Status.CONSUMED) { c.cancelLogin(); return; }
        if (t.status() != QRTransactionStore.Status.CONFIRMED) { show(c, t); return; }
        UserModel user = c.getSession().users().getUserById(c.getRealm(), t.subject());
        if (user == null || !user.isEnabled() || (c.getUser() != null && !c.getUser().getId().equals(user.getId()))
                || c.getProtector().isTemporarilyDisabled(c.getSession(), c.getRealm(), user)
                || c.getProtector().isPermanentlyLockedOut(c.getSession(), c.getRealm(), user)) {
            store.cancel(id, binding(c)); c.failure(AuthenticationFlowError.INVALID_USER); return;
        }
        if (store.consume(id, binding(c)) == null) { c.failure(AuthenticationFlowError.EXPIRED_CODE); return; }
        c.getAuthenticationSession().removeAuthNote(NOTE);
        c.setUser(user); c.getEvent().detail("authentication_method", "qr-login"); c.success();
    }
    private void status(AuthenticationFlowContext c, QRTransactionStore.Transaction t) {
        // Never consume or complete authentication from the background request.
        boolean valid = t != null && t.binding().equals(binding(c));
        try {
            String body = JsonSerialization.writeValueAsString(Map.of(
                    "state", valid ? t.status().name() : "EXPIRED",
                    "expiresAt", valid ? t.expiresAt().toEpochMilli() : 0L,
                    "action", c.getActionUrl(c.generateAccessCode()).toString()));
            c.challenge(jakarta.ws.rs.core.Response.ok(body, jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .header("Cache-Control", "no-store").header("Pragma", "no-cache").build());
        } catch (Exception e) { c.failure(AuthenticationFlowError.INTERNAL_ERROR); }
    }
    @Override public boolean requiresUser() { return false; }
    @Override public boolean configuredFor(KeycloakSession s, RealmModel r, UserModel u) { return true; }
    @Override public void setRequiredActions(KeycloakSession s, RealmModel r, UserModel u) {}
    @Override public void close() {}
}
