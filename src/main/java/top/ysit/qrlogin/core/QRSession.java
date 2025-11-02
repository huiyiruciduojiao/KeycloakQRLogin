package top.ysit.qrlogin.core;

import org.keycloak.sessions.AuthenticationSessionModel;

import java.time.Instant;

public class QRSession {
    private String sessionId;
    private QRSessionStatus status;
    private String userId;
    private String kcSessionId;
    private Instant expireAt;      // epoch millis
    private Instant createdAt;
    private String email;
    private AuthenticationSessionModel authSession;
    private String responseUrl;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getKcSessionId() {
        return kcSessionId;
    }

    public void setKcSessionId(String kcSessionId) {
        this.kcSessionId = kcSessionId;
    }

    public QRSessionStatus getStatus() {
        return status;
    }

    public void setStatus(QRSessionStatus status) {
        this.status = status;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public AuthenticationSessionModel getAuthSession() {
        return authSession;
    }

    public void setAuthSession(AuthenticationSessionModel authSession) {
        this.authSession = authSession;
    }

    public String getResponseUrl() {
        return responseUrl;
    }

    public void setResponseUrl(String responseUrl) {
        this.responseUrl = responseUrl;
    }
}