//package top.ysit.qrlogin.core.store;
//
//import com.webauthn4j.util.exception.NotImplementedException;
//import io.lettuce.core.RedisClient;
//import io.lettuce.core.api.StatefulRedisConnection;
//import io.lettuce.core.api.sync.RedisCommands;
//import top.ysit.qrlogin.core.QRSession;
//import top.ysit.qrlogin.core.QRSessionStatus;
//import top.ysit.qrlogin.core.SessionStore;
//import top.ysit.qrlogin.core.util.JsonUtil;
//
//public class RedisSessionStore implements SessionStore, AutoCloseable {
//    private final RedisClient client;
//    private final StatefulRedisConnection<String, String> conn;
//    private final String ns;
//    private final int ttlSeconds;
//
//    public RedisSessionStore(String uri, String namespace, int ttlSeconds) {
//        this.client = RedisClient.create(uri);
//        this.conn = client.connect();
//        this.ns = namespace;
//        this.ttlSeconds = ttlSeconds;
//    }
//
//    private String key(String id) {
//        return ns + id;
//    }
//
//    @Override
//    public void put(QRSession s) {
//        RedisCommands<String, String> cmd = conn.sync();
//        cmd.setex(key(s.getSessionId()), ttlSeconds, JsonUtil.toJson(s));
//    }
//
//    @Override
//    public QRSession get(String id) {
//        String json = conn.sync().get(key(id));
//        return json == null ? null : JsonUtil.fromJson(json, QRSession.class);
//    }
//
//    @Override
//    public void setScanned(String id) {
//        QRSession s = get(id);
//        if (s == null) return;
//        s.setStatus(QRSessionStatus.SCANNED);
//        put(s);
//    }
//
//    @Override
//    public void setConfirmed(String id, String userId) {
//        QRSession s = get(id);
//        if (s == null) return;
//        s.setStatus(QRSessionStatus.CONFIRMED);
//        s.setUserId(userId);
//        put(s);
//    }
//
//    @Override
//    public void setResponseUrl(String sessionId, String url) {
//        throw new NotImplementedException("Not Impl");
//    }
//
//
//    @Override
//    public void expire(String id) {
//        QRSession s = get(id);
//        if (s == null) return;
//        s.setStatus(QRSessionStatus.EXPIRED);
//        put(s);
//    }
//
//    @Override
//    public void delete(String id) {
//        conn.sync().del(key(id));
//    }
//
//    @Override
//    public void close() {
//        conn.close();
//        client.shutdown();
//    }
//}