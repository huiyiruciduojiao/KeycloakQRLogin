package top.ysit.qrlogin.idp.resource.factory;

import org.keycloak.Config;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;
import top.ysit.qrlogin.core.SessionStore;
import top.ysit.qrlogin.core.util.QRLoginStoreUtil;
import top.ysit.qrlogin.idp.resource.endpoint.QRLoginEndpoint;

import java.security.SignatureException;

public class QRLoginEndpointProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "qr-login-endpoint";

    @Override
    public RealmResourceProvider create(KeycloakSession keycloakSession) {
        KeycloakContext context = keycloakSession.getContext();
        RealmModel realm = context.getRealm();
        EventBuilder event = new EventBuilder(realm, keycloakSession, context.getConnection());
        SessionStore store = QRLoginStoreUtil.getSharedSessionStore(keycloakSession, realm);
        try {
            return new QRLoginEndpoint(keycloakSession, event, store,QRLoginStoreUtil.getQRLoginConfig(keycloakSession));
        } catch (SignatureException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void init(Config.Scope scope) {

    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return ID;
    }
}
