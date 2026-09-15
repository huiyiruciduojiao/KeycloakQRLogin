package top.ysit.qrlogin.avatar;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class AvatarImages {
    static {
        // Keycloak is a server process. Force the JDK's headless AWT backend before
        // BufferedImage/ImageIO initialize so minimal Linux hosts do not load X11.
        System.setProperty("java.awt.headless", "true");
    }
    static final int MAX_UPLOAD = 5 * 1024 * 1024;
    static final long MAX_PIXELS = 4_000_000;
    static final Set<Integer> SIZES = Set.of(64, 128, 256);
    static void validateSize(int size) { if (!SIZES.contains(size)) throw new AvatarException(400, "invalid_size"); }
    static Map<Integer, byte[]> process(InputStream stream) throws IOException {
        byte[] input = stream.readNBytes(MAX_UPLOAD + 1);
        if (input.length > MAX_UPLOAD) throw new AvatarException(413, "image_too_large");
        try (var data = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(data);
            if (!readers.hasNext()) throw new AvatarException(415, "unsupported_image");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg").contains(format)) throw new AvatarException(415, "unsupported_image");
                // APNG is explicitly rejected; never silently retain animation metadata.
                if (format.equals("png") && animatedPng(input)) throw new AvatarException(415, "unsupported_image");
                reader.setInput(data, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
                    throw new AvatarException(413, "image_dimensions_exceeded");
                BufferedImage decoded = reader.read(0);
                try {
                    Map<Integer, byte[]> output = new LinkedHashMap<>();
                    for (int size : new int[]{64, 128, 256}) output.put(size, square(decoded, size));
                    return output;
                } finally { decoded.flush(); }
            } finally { reader.dispose(); }
        } catch (javax.imageio.IIOException | IllegalArgumentException e) {
            throw new AvatarException(400, "invalid_image");
        }
    }
    private static boolean animatedPng(byte[] bytes) {
        for (long offset = 8; offset + 12 <= bytes.length;) {
            int p = (int) offset;
            long length = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, p, 4).getInt());
            String type = new String(bytes, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals("acTL")) return true;
            offset += length + 12;
        }
        return false;
    }
    private static byte[] square(BufferedImage original, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, size, size);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int edge = Math.min(original.getWidth(), original.getHeight());
            int x = (original.getWidth() - edge) / 2, y = (original.getHeight() - edge) / 2;
            graphics.drawImage(original, 0, 0, size, size, x, y, x + edge, y + edge, null);
        } finally { graphics.dispose(); }
        try { return png(image); } finally { image.flush(); }
    }
    static byte[] defaultImage(int size) throws IOException {
        validateSize(size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(232, 238, 246)); g.fillRect(0, 0, size, size);
            g.setColor(new Color(111, 130, 158));
            g.fillOval(size * 5 / 16, size * 3 / 16, size * 6 / 16, size * 6 / 16);
            g.fillOval(size * 2 / 16, size * 10 / 16, size * 12 / 16, size * 12 / 16);
        } finally { g.dispose(); }
        try { return png(image); } finally { image.flush(); }
    }
    private static byte[] png(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
        return output.toByteArray();
    }
}
