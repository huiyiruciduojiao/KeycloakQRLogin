package top.ysit.qrlogin.avatar;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

final class AvatarTestSupport {
    static final class MutableClock extends Clock {
        Instant now = Instant.ofEpochMilli(0);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static AvatarConfig config(Path root) {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 37);
        return new AvatarConfig(true, root, URI.create("https://sso.example.test/auth"), Set.of("example"),
                Set.of("web", "account-console"), false, Map.of("v1", key), "v1", 900, 10_000_000);
    }
    static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff3344);
        var stream = new ByteArrayOutputStream(); ImageIO.write(image, "png", stream); image.flush();
        return stream.toByteArray();
    }
    static Map<Integer,byte[]> images() throws IOException { return AvatarImages.process(new ByteArrayInputStream(png(200, 100))); }
}
