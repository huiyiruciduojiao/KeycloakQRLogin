package top.ysit.qrlogin.idp.resource.endpoint;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.utils.MediaType;
import top.ysit.qrlogin.config.QRLoginConfig;
import top.ysit.qrlogin.core.QRSession;
import top.ysit.qrlogin.core.QRSessionStatus;
import top.ysit.qrlogin.core.SessionStore;
import top.ysit.qrlogin.core.security.SignatureConfig;
import top.ysit.qrlogin.core.security.SignatureUtil;
import top.ysit.qrlogin.core.util.TokenUtil;
import top.ysit.qrlogin.idp.QRLoginIdentityProviderFactory;

import java.net.URI;
import java.security.SignatureException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;


public class QRLoginEndpoint extends RealmsResource implements RealmResourceProvider {
    private final KeycloakSession session;
    private final SessionStore store;
    private final SignatureUtil signatureUtil;

    private final QRLoginConfig qrLoginConfig;

    public QRLoginEndpoint(KeycloakSession session, EventBuilder event, SessionStore store, QRLoginConfig qrLoginConfig) throws SignatureException {
        this.session = session;
        this.store = store;
        this.qrLoginConfig = qrLoginConfig;
        SignatureConfig config = new SignatureConfig(
                qrLoginConfig.getHmacSecret(),
                qrLoginConfig.getAlgorithm(),
                qrLoginConfig.getTimeWindowSeconds()
        );
        this.signatureUtil = new SignatureUtil(config);
    }


    @POST
    @Path("qr/scan")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scan(Map<String, String> body) {
        ValidationResult result = validateRequest(body);
        if (!result.valid()) {
            return Response.ok(
                    Map.of("error", result.errorMessage()),
                    MediaType.APPLICATION_JSON
            ).build();
        }
        if (result.qrSessionObj().getStatus() != QRSessionStatus.PENDING) {
            return Response.ok(
                    Map.of("error", "Qr not found"),
                    MediaType.APPLICATION_JSON
            ).build();
        }

        // 确认特定逻辑
        this.store.setScanned(result.qrSession());
        return Response.ok(Map.of("status", "ok")).build();
    }


    @POST
    @Path("qr/confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(Map<String, String> body) {
        ValidationResult result = validateRequest(body);
        if (!result.valid()) {
            return Response.ok(
                    Map.of("error", result.errorMessage()),
                    MediaType.APPLICATION_JSON
            ).build();
        }
        if (result.qrSessionObj().getStatus() != QRSessionStatus.SCANNED) {
            return Response.ok(
                    Map.of("error", "Qr not found"),
                    MediaType.APPLICATION_JSON
            ).build();
        }
        RealmModel realm = session.getContext().getRealm();
        String providerAlias = QRLoginIdentityProviderFactory.PROVIDER_ID;

        //签名参数
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> params = new HashMap<>();
        params.put("qr_session", result.qrSession);
        params.put("kc_session", result.kcSession);
        params.put("token", body.get("token"));
        params.put("timestamp", String.valueOf(timestamp));
        String signature;
        try {
            signature = signatureUtil.sign(params);
        } catch (SignatureException e) {
            return Response.ok(
                    Map.of("error", "Signature error"),
                    MediaType.APPLICATION_JSON
            ).build();
        }


        URI base = session.getContext().getUri().getBaseUri();
        URI callbackUri = UriBuilder.fromUri(base)
                .path("realms")
                .path(realm.getName())
                .path("broker")
                .path(providerAlias)
                .path("endpoint")
                .queryParam("qr_session", result.qrSession)
                .queryParam("kc_session", result.kcSession)
                .queryParam("token", body.get("token"))
                .queryParam("timestamp", timestamp)
                .queryParam("sign", signature)
                .build();

        String callbackUrl = callbackUri.toString();
        this.store.setResponseUrl(result.qrSession(), callbackUrl);
        this.store.setConfirmed(result.qrSession(), result.tokenResult().email());
        return Response.ok(Map.of("status", "ok")).build();
    }


    @GET
    @Path("qr/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("kc_session") String kcSession, @QueryParam("qr_session") String qrSession, @QueryParam("timestamp") String timestamp) {
        if (kcSession == null || qrSession == null || timestamp == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            signatureUtil.validateTimestamp(timestamp);
        } catch (SignatureException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        QRSession qrs = this.store.get(qrSession);
        if (qrs == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!kcSession.equals(qrs.getKcSessionId())) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        if (Instant.now().isAfter(qrs.getExpireAt())) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of(
                "status", qrs.getStatus(),
                "url", qrs.getStatus() == QRSessionStatus.CONFIRMED ? qrs.getResponseUrl() : ""
        )).build();

    }


    // 在 QRLoginEndpoint 类中添加以下私有方法
    private ValidationResult validateRequest(Map<String, String> body) {
        if (body == null || body.get("timestamp") == null) {
            return ValidationResult.error("Invalid request body");
        }


        String kcSession = body.get("kc_session");
        String qrSession = body.get("qr_session");
        Long timestamp = Long.parseLong(body.get("timestamp"));
        String sign = body.get("sign");
        String token = body.get("token");

        if (kcSession == null || qrSession == null || sign == null || token == null) {
            return ValidationResult.error("Invalid request body");
        }

        Map<String, String> params = new HashMap<>();
        params.put("qr_session", qrSession);
        params.put("kc_session", kcSession);
        params.put("token", token);
        params.put("timestamp", String.valueOf(timestamp));
        params.put("sign", sign);

        try {
            if (!signatureUtil.verify(params)) {
                return ValidationResult.error("Invalid signature");
            }
        } catch (SignatureException e) {
            throw new RuntimeException(e);
        }


        QRSession qrs = this.store.get(qrSession);
        if (qrs == null) {
            return ValidationResult.error("QR session not found");
        }

        if (!kcSession.equals(qrs.getKcSessionId())) {
            return ValidationResult.error("Invalid kcSession");
        }

        TokenUtil.TokenValidationResult tokenValidationResult = TokenUtil.verifyToken(token);
        if (!tokenValidationResult.valid()) {
            return ValidationResult.error("Invalid token");
        }

        return ValidationResult.success(kcSession, qrSession, qrs, tokenValidationResult);
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {

    }

    private record ValidationResult(boolean valid, String errorMessage, String kcSession, String qrSession,
                                    QRSession qrSessionObj, TokenUtil.TokenValidationResult tokenResult) {

        public static ValidationResult error(String errorMessage) {
            return new ValidationResult(false, errorMessage, null, null, null, null);
        }

        public static ValidationResult success(String kcSession, String qrSession, QRSession qrSessionObj, TokenUtil.TokenValidationResult tokenResult) {
            return new ValidationResult(true, null, kcSession, qrSession, qrSessionObj, tokenResult);
        }
    }
}
