package top.ysit.qrlogin.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Single-node, bounded store. Every state transition is serialized and checked against TTL. */
public final class QRTransactionStore {
    static final int DEFAULT_GLOBAL_CAPACITY = 10000;
    static final int DEFAULT_REALM_CAPACITY = 1000;

    public enum Status { PENDING, SCANNED, CONFIRMED, CONSUMED, DENIED, CANCELLED }
    public record Binding(String realm, String root, String tab, String client, String execution) {}
    public record Transaction(String id, Binding binding, String mobileClient, String audience,
                              String userCode, String application, Instant expiresAt, Status status, String subject) {
        Transaction transition(Status next, String user) {
            return new Transaction(id, binding, mobileClient, audience, userCode, application, expiresAt, next, user);
        }
    }
    private final Map<String, Transaction> transactions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final int capacity;
    private final int realmCapacity;

    public QRTransactionStore() { this(Clock.systemUTC(), DEFAULT_GLOBAL_CAPACITY, DEFAULT_REALM_CAPACITY); }
    public QRTransactionStore(Clock clock, int capacity) { this(clock, capacity, capacity); }
    public QRTransactionStore(Clock clock, int capacity, int realmCapacity) {
        if (clock == null || capacity < 1 || realmCapacity < 1 || realmCapacity > capacity) {
            throw new IllegalArgumentException("Invalid QR store capacity");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.realmCapacity = realmCapacity;
    }

    public synchronized Transaction create(Binding binding, String mobileClient, String audience,
                                           String application, int ttl) {
        if (ttl < 30 || ttl > 300 || mobileClient == null || mobileClient.isBlank()
                || audience == null || audience.isBlank()) throw new IllegalArgumentException("Invalid QR configuration");
        transactions.values().removeIf(t -> !clock.instant().isBefore(t.expiresAt()));
        long realmSize = transactions.values().stream()
                .filter(t -> t.binding().realm().equals(binding.realm()))
                .count();
        if (realmSize >= realmCapacity) throw new IllegalStateException("QR realm capacity reached");
        if (transactions.size() >= capacity) throw new IllegalStateException("QR capacity reached");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Transaction t = new Transaction(id, binding, mobileClient, audience,
                String.format("%04d", random.nextInt(10000)), application,
                clock.instant().plusSeconds(ttl), Status.PENDING, null);
        transactions.put(id, t);
        return t;
    }

    public synchronized Transaction get(String realm, String id) {
        Transaction t = transactions.get(id);
        if (t == null || !t.binding().realm().equals(realm)) return null;
        if (!clock.instant().isBefore(t.expiresAt())) { transactions.remove(id); return null; }
        return t;
    }

    public synchronized Transaction advance(String realm, String id, String subject, Status target) {
        Transaction t = get(realm, id);
        if (t == null || subject == null || subject.isBlank()) return null;
        boolean scan = target == Status.SCANNED && t.status() == Status.PENDING;
        boolean decision = (target == Status.CONFIRMED || target == Status.DENIED)
                && t.status() == Status.SCANNED && subject.equals(t.subject());
        boolean retry = target == t.status() && subject.equals(t.subject())
                && (target == Status.SCANNED || target == Status.CONFIRMED || target == Status.DENIED);
        if (!scan && !decision && !retry) return null;
        Transaction next = t.transition(target, subject);
        transactions.put(id, next);
        return next;
    }

    public synchronized Transaction consume(String id, Binding binding) {
        Transaction t = get(binding.realm(), id);
        if (t == null || !t.binding().equals(binding) || t.status() != Status.CONFIRMED) return null;
        transactions.put(id, t.transition(Status.CONSUMED, t.subject()));
        return t;
    }

    public synchronized void cancel(String id, Binding binding) {
        Transaction t = get(binding.realm(), id);
        if (t != null && t.binding().equals(binding) && t.status() != Status.CONSUMED)
            transactions.put(id, t.transition(Status.CANCELLED, t.subject()));
    }
    public synchronized void clear() { transactions.clear(); }
}
