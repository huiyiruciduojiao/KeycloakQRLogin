package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAvatarStorageTest {
    @TempDir Path root;
    @Test void persistsAcrossRestartAndInvalidatesOldImages() throws Exception {
        String latest;
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            assertNull(store.current("r", "u"));
            String first = store.replace("r", "u", AvatarTestSupport.images());
            assertEquals("u", store.read("r", first, 128).userId());
            assertNull(store.read("other", first, 128));
            latest = store.replace("r", "u", AvatarTestSupport.images());
            assertNull(store.read("r", first, 128)); assertNotEquals(first, latest);
        }
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            assertEquals(latest, store.current("r", "u")); assertNotNull(store.read("r", latest, 256));
            store.delete("r", "u"); store.delete("r", "u");
            assertNull(store.current("r", "u")); assertNull(store.read("r", latest, 128));
        }
    }
    @Test void opaqueUserIdsAreNotFilesystemPathsAndSecondNodeIsRejected() throws Exception {
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            String user = "f:directory:../../someone";
            String asset = store.replace("../realm", user, AvatarTestSupport.images());
            assertEquals(user, store.read("../realm", asset, 64).userId());
            assertNull(store.current("../realm", "someone")); assertNull(store.read("../realm", "../../outside", 64));
            assertThrows(Exception.class, () -> new LocalAvatarStorage(root, 10_000_000));
        }
    }
    @Test void storageCapacityFailurePreservesExistingAvatar() throws Exception {
        var images = AvatarTestSupport.images();
        long quota = 32 + 1 + images.values().stream().mapToInt(b -> b.length).sum();
        try (var store = new LocalAvatarStorage(root, quota)) {
            String first = store.replace("r", "u", images);
            assertEquals(507, assertThrows(AvatarException.class, () -> store.replace("r", "u", images)).status);
            assertEquals(first, store.current("r", "u")); assertNotNull(store.read("r", first, 64));
            store.delete("r", "u"); assertNotNull(store.replace("r", "u", images));
        }
    }
    @Test void concurrentChangesLeaveOneCompleteCurrentVersion() throws Exception {
        var images = AvatarTestSupport.images();
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            var executor = Executors.newFixedThreadPool(6);
            try {
                List<Callable<String>> operations = new ArrayList<>();
                for (int i = 0; i < 20; i++) operations.add(() -> store.replace("r", "u", images));
                var futures = executor.invokeAll(operations);
                String current = store.current("r", "u");
                int readable = 0;
                for (var future : futures) if (store.read("r", future.get(), 128) != null) readable++;
                assertEquals(1, readable);
                for (int size : AvatarImages.SIZES) assertArrayEquals(images.get(size), store.read("r", current, size).bytes());
                var mixed = executor.invokeAll(List.of(() -> { store.delete("r", "u"); return "deleted"; }, () -> store.replace("r", "u", images)));
                for (var future : mixed) future.get();
                current = store.current("r", "u");
                if (current != null) for (int size : AvatarImages.SIZES) assertNotNull(store.read("r", current, size));
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void corruptedPointerFailsAsStorageErrorRatherThanDefault() throws Exception {
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            store.replace("r", "u", AvatarTestSupport.images());
            Files.writeString(root.resolve(LocalAvatarStorage.hash("r")).resolve("users").resolve(LocalAvatarStorage.hash("u") + ".ref"), "invalid");
            assertThrows(java.io.IOException.class, () -> store.current("r", "u"));
        }
    }
    @Test void offlineMaintenancePreservesReferencedAndRecentAssetsAndSupportsDryRun() throws Exception {
        try (var store = new LocalAvatarStorage(root, 10_000_000)) {
            String current = store.replace("r", "u", AvatarTestSupport.images());
            Path objects = root.resolve(LocalAvatarStorage.hash("r")).resolve("objects");
            Path old = Files.createDirectory(objects.resolve("a".repeat(32)));
            Files.writeString(old.resolve("owner"), "orphan");
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(90000)));
            Path recent = Files.createDirectory(objects.resolve("b".repeat(32)));
            Files.writeString(recent.resolve("owner"), "recent");
            var cutoff = java.time.Instant.now().minusSeconds(86400);
            assertEquals(1, store.collectOrphans(false, cutoff).objects()); assertTrue(Files.exists(old));
            assertEquals(6, store.collectOrphans(true, cutoff).bytes()); assertFalse(Files.exists(old));
            assertTrue(Files.exists(recent)); assertNotNull(store.read("r", current, 128));
        }
    }
}
