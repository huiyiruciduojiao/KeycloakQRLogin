package top.ysit.qrlogin.avatar;

import java.io.IOException;
import java.util.Map;

interface AvatarStorage extends AutoCloseable {
    record Content(String userId, byte[] bytes) {}
    String current(String realmId, String userId) throws IOException;
    String replace(String realmId, String userId, Map<Integer, byte[]> images) throws IOException;
    void delete(String realmId, String userId) throws IOException;
    Content read(String realmId, String asset, int size) throws IOException;
    @Override void close() throws IOException;
}
