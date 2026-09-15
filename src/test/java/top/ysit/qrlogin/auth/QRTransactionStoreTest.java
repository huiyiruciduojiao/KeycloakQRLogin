package top.ysit.qrlogin.auth;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.ysit.qrlogin.auth.QRTransactionStore.Status.*;

class QRTransactionStoreTest {
    private final MutableClock clock = new MutableClock();
    private final QRTransactionStore store = new QRTransactionStore(clock, 10);
    private final QRTransactionStore.Binding binding = new QRTransactionStore.Binding("r", "root", "tab", "client", "exec");
    private QRTransactionStore.Transaction create() { return store.create(binding, "mobile", "qr-api", "Portal", 120); }
    @Test void scanBindsSubjectAndConsumptionIsSingleUse() {
        var t = create();
        assertNull(store.advance("r", t.id(), "alice", CONFIRMED));
        assertNotNull(store.advance("r", t.id(), "alice", SCANNED));
        assertNull(store.advance("r", t.id(), "bob", SCANNED));
        assertNull(store.advance("r", t.id(), "bob", CONFIRMED));
        assertNull(store.advance("r", t.id(), "bob", DENIED));
        assertNotNull(store.advance("r", t.id(), "alice", CONFIRMED));
        assertNotNull(store.advance("r", t.id(), "alice", CONFIRMED));
        assertEquals("alice", store.consume(t.id(), binding).subject());
        assertNull(store.consume(t.id(), binding));
        assertNull(store.advance("r", t.id(), "alice", SCANNED));
        assertNull(store.advance("r", t.id(), "alice", CONFIRMED));
    }
    @Test void enforcesExactExpiryAtEveryTransition() {
        var t = create();
        store.advance("r", t.id(), "alice", SCANNED);
        store.advance("r", t.id(), "alice", CONFIRMED);
        clock.now = t.expiresAt();
        assertNull(store.get("r", t.id()));
        assertNull(store.advance("r", t.id(), "alice", CONFIRMED));
        assertNull(store.consume(t.id(), binding));
    }
    @Test void isolatesRealmAndAllBrowserBindingFields() {
        var t = create();
        assertNull(store.get("other", t.id()));
        assertNull(store.advance("other", t.id(), "alice", SCANNED));
        store.advance("r", t.id(), "alice", SCANNED);
        store.advance("r", t.id(), "alice", CONFIRMED);
        for (var wrong : java.util.List.of(
                new QRTransactionStore.Binding("x", "root", "tab", "client", "exec"),
                new QRTransactionStore.Binding("r", "x", "tab", "client", "exec"),
                new QRTransactionStore.Binding("r", "root", "x", "client", "exec"),
                new QRTransactionStore.Binding("r", "root", "tab", "x", "exec"),
                new QRTransactionStore.Binding("r", "root", "tab", "client", "x"))) {
            store.cancel(t.id(), wrong);
            assertNull(store.consume(t.id(), wrong));
        }
        assertNotNull(store.consume(t.id(), binding));
    }
    @Test void denialAndCancellationAreTerminal() {
        var t = create();
        store.advance("r", t.id(), "alice", SCANNED);
        assertNotNull(store.advance("r", t.id(), "alice", DENIED));
        assertNull(store.advance("r", t.id(), "alice", CONFIRMED));
        assertNull(store.consume(t.id(), binding));
        t = create();
        store.cancel(t.id(), binding);
        assertNull(store.advance("r", t.id(), "alice", SCANNED));
    }
    @Test void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        var t = create();
        store.advance("r", t.id(), "alice", SCANNED);
        store.advance("r", t.id(), "alice", CONFIRMED);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 32; i++) jobs.add(() -> store.consume(t.id(), binding) != null);
            int winners = 0;
            for (var result : executor.invokeAll(jobs)) if (result.get()) winners++;
            assertEquals(1, winners);
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentScannersBindOnlyOneUser() throws Exception {
        var t = create();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 32; i++) {
                String user = "user-" + i;
                jobs.add(() -> store.advance("r", t.id(), user, SCANNED) != null);
            }
            int winners = 0;
            for (var result : executor.invokeAll(jobs)) if (result.get()) winners++;
            assertEquals(1, winners);
        } finally { executor.shutdownNow(); }
    }
    @Test void boundsCapacityAndReclaimsExpiredEntries() {
        var bounded = new QRTransactionStore(clock, 1);
        var t = bounded.create(binding, "mobile", "api", "Portal", 30);
        assertThrows(IllegalStateException.class, () -> bounded.create(binding, "mobile", "api", "Portal", 30));
        clock.now = t.expiresAt();
        assertNotNull(bounded.create(binding, "mobile", "api", "Portal", 30));
    }
    @Test void oneRealmCannotExhaustTheNodeWidePool() {
        var bounded = new QRTransactionStore(clock, 4, 2);
        var realmA = new QRTransactionStore.Binding("realm-a", "root-1", "tab", "client", "exec");
        var realmB = new QRTransactionStore.Binding("realm-b", "root-2", "tab", "client", "exec");
        bounded.create(realmA, "mobile", "api", "Portal", 30);
        bounded.create(realmA, "mobile", "api", "Portal", 30);
        assertThrows(IllegalStateException.class,
                () -> bounded.create(realmA, "mobile", "api", "Portal", 30));
        assertNotNull(bounded.create(realmB, "mobile", "api", "Portal", 30));
        assertNotNull(bounded.create(realmB, "mobile", "api", "Portal", 30));
    }
    @Test void rejectsInvalidStoreCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new QRTransactionStore(null, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new QRTransactionStore(clock, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new QRTransactionStore(clock, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new QRTransactionStore(clock, 10, 11));
    }
    @Test void refusesMissingPolicyAndInvalidTtl() {
        assertThrows(IllegalArgumentException.class, () -> store.create(binding, null, "api", "Portal", 120));
        assertThrows(IllegalArgumentException.class, () -> store.create(binding, "mobile", " ", "Portal", 120));
        assertThrows(IllegalArgumentException.class, () -> store.create(binding, "mobile", "api", "Portal", 0));
        assertThrows(IllegalArgumentException.class, () -> store.create(binding, "mobile", "api", "Portal", 301));
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
