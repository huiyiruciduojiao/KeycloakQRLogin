package top.ysit.qrlogin.core.store;

import top.ysit.qrlogin.config.QRLoginConfig;
import top.ysit.qrlogin.core.QRSession;
import top.ysit.qrlogin.core.QRSessionStatus;
import top.ysit.qrlogin.core.SessionStore;

import java.time.Instant;
import java.util.concurrent.*;

public class InMemorySessionStore implements SessionStore {
    private final ConcurrentMap<String, QRSession> map = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    // 清理周期
    private final long cleanupInterval = 30; // 每 30 秒清理一次
    // 默认会话有效期，单位：秒
    private long sessionTimeout = 120; // 2 分钟

    public InMemorySessionStore(QRLoginConfig cfg) {

        this.sessionTimeout = cfg.getSessionTtlSeconds();

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qr-session-cleaner");
            t.setDaemon(true);
            return t;
        });

        cleaner.scheduleAtFixedRate(this::cleanupExpiredSessions,
                cleanupInterval, cleanupInterval, TimeUnit.SECONDS);
    }

    @Override
    public void put(QRSession s) {
        s.setCreatedAt(Instant.now());
        map.put(s.getSessionId(), s);
    }

    @Override
    public QRSession get(String id) {
        return map.get(id);
    }

    @Override
    public void setConfirmed(String id, String email) {
        map.computeIfPresent(id, (k, v) -> {
            v.setStatus(QRSessionStatus.CONFIRMED);
            v.setEmail(email);
            return v;
        });
    }

    @Override
    public void setScanned(String id) {
        map.computeIfPresent(id, (k, v) -> {
            v.setStatus(QRSessionStatus.SCANNED);
            return v;
        });
    }

    @Override
    public void setResponseUrl(String sessionId, String url) {
        map.computeIfPresent(sessionId, (k, v) -> {
            v.setResponseUrl(url);
            return v;
        });
    }

    @Override
    public void expire(String id) {
        map.computeIfPresent(id, (k, v) -> {
            v.setStatus(QRSessionStatus.EXPIRED);
            return v;
        });
    }

    @Override
    public void delete(String id) {
        map.remove(id);
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        map.values().removeIf(s -> {
            if (s.getStatus() == QRSessionStatus.EXPIRED) return true;
            return s.getCreatedAt() != null &&
                    now.isAfter(s.getCreatedAt().plusSeconds(sessionTimeout));
        });
    }

    @Override
    public String toString() {
        return "InMemorySessionStore{" + "sessions=" + map.size() + '}';
    }
}
