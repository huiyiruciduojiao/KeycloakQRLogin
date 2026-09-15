package top.ysit.qrlogin.auth;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;

public final class QRLoginResourceFactory implements RealmResourceProviderFactory {
    @Override public String getId() { return "qr-login"; }
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new QRLoginResource(session, QRLoginAuthenticatorFactory.store(session));
    }
    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}
}
