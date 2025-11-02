package top.ysit.qrlogin.core;

public interface SessionStore {
    void put(QRSession s);

    QRSession get(String sessionId);

    void setScanned(String sessionId);

    void setConfirmed(String sessionId, String userId);

    void setResponseUrl(String sessionId, String url);

    void expire(String sessionId);

    void delete(String sessionId);

}
