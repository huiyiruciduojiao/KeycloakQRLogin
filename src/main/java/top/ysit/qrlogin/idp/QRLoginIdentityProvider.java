package top.ysit.qrlogin.idp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import org.keycloak.utils.MediaType;
import top.ysit.qrlogin.config.QRLoginConfig;
import top.ysit.qrlogin.core.QRSession;
import top.ysit.qrlogin.core.QRSessionStatus;
import top.ysit.qrlogin.core.SessionStore;
import top.ysit.qrlogin.core.security.SignatureConfig;
import top.ysit.qrlogin.core.security.SignatureUtil;
import top.ysit.qrlogin.core.util.QRCodeUtil;
import top.ysit.qrlogin.core.util.TokenUtil;
import top.ysit.qrlogin.idp.resource.factory.QRLoginEndpointProviderFactory;

import java.security.SignatureException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class QRLoginIdentityProvider extends AbstractIdentityProvider<IdentityProviderModel> implements IdentityProvider<IdentityProviderModel> {
    private final QRLoginConfig cfg;
    private final SessionStore store;

    private final SignatureUtil signatureUtil;

    public QRLoginIdentityProvider(KeycloakSession session, QRLoginConfig cfg, SessionStore store) {

        super(session, cfg);

        this.cfg = cfg;
        this.store = store;
        SignatureConfig signatureConfig = new SignatureConfig(cfg.getHmacSecret(), cfg.getAlgorithm(), cfg.getTimeWindowSeconds());
        try {
            this.signatureUtil = new SignatureUtil(signatureConfig);
        } catch (SignatureException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(callback, realm, event, this);
    }

    /**
     * 在登录页渲染“使用二维码登录”按钮；点击后进入我们的 iframe 页面。
     */
    @Override
    public Response performLogin(AuthenticationRequest request) {
        AuthenticationSessionModel authSession = request.getAuthenticationSession();

        String sessionId = UUID.randomUUID().toString();
        QRSession s = new QRSession();
        s.setSessionId(sessionId);
        s.setStatus(QRSessionStatus.PENDING);
        s.setExpireAt(Instant.now().plusSeconds(cfg.getSessionTtlSeconds()));
        s.setCreatedAt(Instant.now());
        s.setKcSessionId(authSession.getParentSession().getId());
        s.setAuthSession(authSession);

        store.put(s);


        String baseUrl = session.getContext().getUri().getBaseUri().toString();

//        构造请求地址
        String checkUrl = baseUrl + "realms/" + request.getRealm().getName() + "/" + QRLoginEndpointProviderFactory.ID + "/" + "qr/status" + "?qr_session=" + s.getSessionId() + "&kc_session=" + authSession.getParentSession().getId();
        // 构造二维码中的JSON数据
        Map<String, Object> qrData = new HashMap<>();
        qrData.put("type", "qr_login");
        qrData.put("baseUrl", baseUrl + "realms/" + request.getRealm().getName() + "/" + QRLoginEndpointProviderFactory.ID + "/" + "qr/");
        qrData.put("qr_session", s.getSessionId());
        qrData.put("kc_session", authSession.getParentSession().getId());
        qrData.put("algorithm", cfg.getAlgorithm());
        qrData.put("token", ""); // 客户端需要填充
        qrData.put("sign", "");   // 客户端需要填充
        qrData.put("ttl", cfg.getSessionTtlSeconds());
        qrData.put("timestamp", "");
        qrData.put("expiredAt", s.getExpireAt().toEpochMilli());


        String qrJsonData;
        try {
            qrJsonData = JsonSerialization.writeValueAsPrettyString(qrData);
            String imageData = QRCodeUtil.toDataUrl(qrJsonData, 512);

            return Response.ok(Map.of("qr_session", s.getSessionId(),
                            "kc_session", authSession.getParentSession().getId(),
                            "qr_image_data", imageData,
                            "statusUrl", checkUrl,
                            "ttl", cfg.getSessionTtlSeconds(),
                            "interval", cfg.getPollIntervalMs()
                    ))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }


    @Override
    public Response retrieveToken(KeycloakSession keycloakSession, FederatedIdentityModel federatedIdentityModel) {
        return null;
    }

    protected static class Endpoint {
        protected AuthenticationCallback callback;
        protected RealmModel realm;
        protected EventBuilder event;
        protected QRLoginIdentityProvider qrIdp;


        public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event, QRLoginIdentityProvider qrIdp) {
            this.callback = callback;
            this.realm = realm;
            this.event = event;
            this.qrIdp = qrIdp;
        }

        @GET
        public Response authResponse(@QueryParam("kc_session") String kcSessionId, @QueryParam("qr_session") String qrSessionId, @QueryParam("token") String token, @QueryParam("timestamp") String timestamp, @QueryParam("sign") String sign) {

            if (token == null || timestamp == null || sign == null || qrSessionId == null || kcSessionId == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            Map<String, String> params = new HashMap<>();
            params.put("qr_session", qrSessionId);
            params.put("kc_session", kcSessionId);
            params.put("token", token);
            params.put("timestamp", timestamp);
            params.put("sign", sign);
            try {
                qrIdp.signatureUtil.verify(params);
            } catch (SignatureException e) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            QRSession qrs = qrIdp.store.get(qrSessionId);
            if (qrs == null || !qrs.getKcSessionId().equals(kcSessionId) || qrs.getStatus() != QRSessionStatus.CONFIRMED) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            TokenUtil.IntrospectConfig config = new TokenUtil.IntrospectConfig();
            config.setClientId(qrIdp.cfg.getClientId());
            config.setClientSecret(qrIdp.cfg.getSecret());
            String baseUrl = qrIdp.session.getContext().getUri().getBaseUri().toString();
            String realm = qrIdp.session.getContext().getRealm().getName();
            config.setBaseUrl(baseUrl);
            config.setRealm(realm);


            TokenUtil.TokenValidationResult tokenValidationResult = TokenUtil.verifyToken(token,config);
            if (!tokenValidationResult.valid()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            AuthenticationSessionManager asm = new AuthenticationSessionManager(qrIdp.session);
            RootAuthenticationSessionModel root = asm.getCurrentRootAuthenticationSession(qrIdp.session.getContext().getRealm());
            AuthenticationSessionModel authSession = root.getAuthenticationSession(qrs.getAuthSession().getClient(), qrs.getAuthSession().getTabId());

            BrokeredIdentityContext federatedIdentity = new BrokeredIdentityContext(tokenValidationResult.sub(), qrIdp.getConfig());
            federatedIdentity.setIdp(qrIdp);

            federatedIdentity.setUsername(tokenValidationResult.username());
            federatedIdentity.setEmail(tokenValidationResult.email());
            federatedIdentity.setAuthenticationSession(authSession);


            return callback.authenticated(federatedIdentity);

        }

    }
}