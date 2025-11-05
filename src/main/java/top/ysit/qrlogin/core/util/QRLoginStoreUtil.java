package top.ysit.qrlogin.core.util;

import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import top.ysit.qrlogin.config.QRLoginConfig;
import top.ysit.qrlogin.core.SessionStore;
import top.ysit.qrlogin.core.store.InMemorySessionStore;
import top.ysit.qrlogin.idp.QRLoginIdentityProviderFactory;

public class QRLoginStoreUtil {


    private static SessionStore sharedStore;


    /**
     * 获取共享的 SessionStore 实例
     */
    public static SessionStore getSharedSessionStore(KeycloakSession session, RealmModel realm) {
        if (sharedStore == null) {
            IdentityProviderModel idpModel = getIdentityProviderModel(session);
            if (idpModel != null) {
                QRLoginConfig cfg = new QRLoginConfig(idpModel);
                sharedStore = createStore(cfg);

            }
        }
        return sharedStore;
    }

    public static IdentityProviderModel getIdentityProviderModel(KeycloakSession session) {
        return session.getProvider(IdentityProviderStorageProvider.class).getByAlias(QRLoginIdentityProviderFactory.PROVIDER_ID);
    }

    public static QRLoginConfig getQRLoginConfig(KeycloakSession session) {
        return new QRLoginConfig(getIdentityProviderModel(session));
    }


    /**
     * 根据配置创建 SessionStore 实例
     */
    public static SessionStore createStore(QRLoginConfig cfg) {
        if ("memory".equalsIgnoreCase(cfg.getStoreType())) {
            return new InMemorySessionStore(cfg);
        }
//        return new RedisSessionStore(cfg.getRedisUri(), cfg.getRedisNamespace(), cfg.getSessionTtlSeconds());
        return null;
    }


}