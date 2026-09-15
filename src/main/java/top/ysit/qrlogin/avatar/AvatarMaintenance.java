package top.ysit.qrlogin.avatar;

import java.nio.file.*;
import java.time.*;

/** Offline maintenance. Does not connect to Keycloak or delete current avatar references. */
public final class AvatarMaintenance {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--delete")))
            throw new IllegalArgumentException("Usage: AvatarMaintenance <absolute-storage-directory> [--delete]");
        Path root = Path.of(args[0]);
        if (!root.isAbsolute() || !Files.isDirectory(root)) throw new IllegalArgumentException("Existing absolute storage directory required");
        try (var storage = new LocalAvatarStorage(root, Long.MAX_VALUE)) {
            var result = storage.collectOrphans(args.length == 2, Instant.now().minus(Duration.ofHours(24)));
            System.out.printf("%s: %d obsolete objects, %d bytes%n", result.deleted() ? "Deleted" : "Dry run", result.objects(), result.bytes());
        }
    }
}
