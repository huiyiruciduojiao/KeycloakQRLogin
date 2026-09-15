package top.ysit.qrlogin.avatar;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Single-process store. Atomic pointer replacement is the commit point, independent of Keycloak user storage. */
final class LocalAvatarStorage implements AvatarStorage {
    private static final System.Logger LOG = System.getLogger(LocalAvatarStorage.class.getName());
    private final Path root;
    private long quota;
    private final FileChannel channel;
    private final FileLock lock;
    private long used;
    private boolean closed;
    synchronized void setQuota(long quota) { this.quota = quota; }

    LocalAvatarStorage(Path root, long quota) throws IOException {
        this.root = root.toAbsolutePath().normalize(); this.quota = quota;
        safeDirectories(this.root);
        Path lockPath = this.root.resolve(".lock");
        rejectSymlink(lockPath);
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Avatar directory is already in use by another node");
            used = measure();
            // Fail at initialization on filesystems without atomic rename support.
            Path probe = Files.createTempFile(this.root, ".atomic-", ".tmp");
            Path moved = probe.resolveSibling(probe.getFileName() + ".moved");
            try { Files.move(probe, moved, StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(probe); Files.deleteIfExists(moved); }
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }
    @Override public synchronized String current(String realm, String user) throws IOException {
        ensureOpen();
        Path pointer = pointer(realm, user);
        rejectSymlink(pointer);
        if (!Files.exists(pointer)) return null;
        if (Files.size(pointer) != 32) throw new IOException("Invalid avatar pointer");
        String asset = Files.readString(pointer, StandardCharsets.US_ASCII);
        if (!validAsset(asset)) throw new IOException("Invalid avatar pointer");
        return asset;
    }
    @Override public synchronized String replace(String realm, String user, Map<Integer, byte[]> images) throws IOException {
        ensureOpen();
        if (!images.keySet().equals(AvatarImages.SIZES)) throw new IllegalArgumentException("All avatar sizes required");
        String old = current(realm, user);
        byte[] owner = user.getBytes(StandardCharsets.UTF_8);
        long added = 32L + owner.length + images.values().stream().mapToLong(b -> b.length).sum();
        if (added > quota - used) throw new AvatarException(507, "storage_capacity_exceeded");
        String asset = UUID.randomUUID().toString().replace("-", "");
        Path directory = object(realm, asset);
        Path pointer = pointer(realm, user);
        safeDirectories(directory); safeDirectories(pointer.getParent());
        Path temp = null;
        boolean committed = false;
        try {
            Files.write(directory.resolve("owner"), owner, StandardOpenOption.CREATE_NEW);
            for (var entry : images.entrySet()) Files.write(directory.resolve(entry.getKey() + ".png"), entry.getValue(), StandardOpenOption.CREATE_NEW);
            // Force file contents before publishing the pointer.
            for (String name : List.of("owner", "64.png", "128.png", "256.png")) force(directory.resolve(name));
            temp = Files.createTempFile(pointer.getParent(), ".pointer-", ".tmp");
            Files.writeString(temp, asset, StandardCharsets.US_ASCII); force(temp);
            Files.move(temp, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            used += added - (old == null ? 0 : 32);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
            if (!committed) { removeObject(directory); used = measure(); }
        }
        if (old != null) reclaim(object(realm, old));
        return asset;
    }
    @Override public synchronized void delete(String realm, String user) throws IOException {
        ensureOpen();
        String old = current(realm, user);
        if (old == null) return;
        Files.delete(pointer(realm, user)); used -= 32;
        reclaim(object(realm, old));
    }
    @Override public synchronized Content read(String realm, String asset, int size) throws IOException {
        ensureOpen(); AvatarImages.validateSize(size);
        if (!validAsset(asset)) return null;
        Path directory = object(realm, asset);
        rejectSymlink(directory);
        if (!Files.exists(directory)) return null;
        Path ownerPath = directory.resolve("owner"); rejectSymlink(ownerPath);
        if (Files.size(ownerPath) > 4096) throw new IOException("Invalid avatar owner");
        String owner = Files.readString(ownerPath, StandardCharsets.UTF_8);
        if (!asset.equals(current(realm, owner))) return null;
        Path image = directory.resolve(size + ".png"); rejectSymlink(image);
        if (Files.size(image) > 1024 * 1024) throw new IOException("Invalid stored avatar");
        return new Content(owner, Files.readAllBytes(image));
    }
    private void reclaim(Path directory) {
        try { used -= removeObject(directory); }
        catch (IOException e) {
            // The pointer is already committed. Keep success semantics; failed reclamation is operator-visible.
            LOG.log(System.Logger.Level.WARNING, "Avatar update committed; obsolete file cleanup failed. Run offline maintenance.");
        }
    }
    private long removeObject(Path directory) throws IOException {
        long removed = 0;
        rejectSymlink(directory);
        for (String name : List.of("owner", "64.png", "128.png", "256.png")) {
            Path file = directory.resolve(name); rejectSymlink(file);
            if (Files.exists(file)) { long size = Files.size(file); Files.delete(file); removed += size; }
        }
        Files.deleteIfExists(directory);
        return removed;
    }
    private long measure() throws IOException {
        long total = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                rejectSymlink(path);
                if (Files.isRegularFile(path)) total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }
    record MaintenanceResult(int objects, long bytes, boolean deleted) {}
    /** Offline only: exclusive process lock is held, and corrupt metadata aborts before any deletion. */
    synchronized MaintenanceResult collectOrphans(boolean delete, java.time.Instant cutoff) throws IOException {
        ensureOpen();
        List<Path> candidates = new ArrayList<>();
        try (var realms = Files.list(root)) {
            for (var iterator = realms.iterator(); iterator.hasNext();) {
                Path realm = iterator.next(); rejectSymlink(realm);
                if (!Files.isDirectory(realm) || !realm.getFileName().toString().matches("[a-f0-9]{64}")) continue;
                Set<String> referenced = new HashSet<>();
                Path users = realm.resolve("users"); rejectSymlink(users);
                if (Files.isDirectory(users)) try (var pointers = Files.list(users)) {
                    for (var it = pointers.iterator(); it.hasNext();) {
                        Path pointer = it.next(); rejectSymlink(pointer);
                        if (!pointer.getFileName().toString().endsWith(".ref")) continue;
                        if (Files.size(pointer) != 32) throw new IOException("Corrupt avatar metadata; maintenance aborted");
                        String asset = Files.readString(pointer, StandardCharsets.US_ASCII);
                        if (!validAsset(asset)) throw new IOException("Corrupt avatar metadata; maintenance aborted");
                        referenced.add(asset);
                    }
                }
                Path objects = realm.resolve("objects"); rejectSymlink(objects);
                if (!Files.isDirectory(objects)) continue;
                try (var entries = Files.list(objects)) {
                    for (var it = entries.iterator(); it.hasNext();) {
                        Path object = it.next(); rejectSymlink(object);
                        if (Files.isDirectory(object) && validAsset(object.getFileName().toString())
                                && !referenced.contains(object.getFileName().toString())
                                && Files.getLastModifiedTime(object).toInstant().isBefore(cutoff)) candidates.add(object);
                    }
                }
            }
        }
        long bytes = 0;
        for (Path object : candidates) {
            try (var files = Files.list(object)) {
                for (var iterator = files.iterator(); iterator.hasNext();) {
                    Path file = iterator.next(); rejectSymlink(file);
                    if (!Set.of("owner", "64.png", "128.png", "256.png").contains(file.getFileName().toString()) || !Files.isRegularFile(file))
                        throw new IOException("Unexpected object content; maintenance aborted");
                    bytes += Files.size(file);
                }
            }
        }
        if (delete) { for (Path object : candidates) removeObject(object); used = measure(); }
        return new MaintenanceResult(candidates.size(), bytes, delete);
    }
    private Path pointer(String realm, String user) throws IOException {
        Path realmRoot = root.resolve(hash(realm)); rejectSymlink(realmRoot);
        Path users = realmRoot.resolve("users"); rejectSymlink(users);
        return users.resolve(hash(user) + ".ref");
    }
    private Path object(String realm, String asset) throws IOException {
        if (!validAsset(asset)) throw new IOException("Invalid avatar asset");
        Path realmRoot = root.resolve(hash(realm)); rejectSymlink(realmRoot);
        Path objects = realmRoot.resolve("objects"); rejectSymlink(objects);
        return objects.resolve(asset);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static boolean validAsset(String asset) { return asset != null && asset.matches("[a-f0-9]{32}"); }
    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlinks are not permitted in avatar storage");
    }
    private static void safeDirectories(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) rejectSymlink(current);
        Files.createDirectories(path);
    }
    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("Avatar storage closed"); }
    @Override public synchronized void close() throws IOException {
        if (!closed) { closed = true; try { lock.release(); } finally { channel.close(); } }
    }
}
