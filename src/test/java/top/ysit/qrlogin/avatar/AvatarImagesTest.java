package top.ysit.qrlogin.avatar;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class AvatarImagesTest {
    @Test void decodesAndReencodesAllSizesAndDefault() throws Exception {
        var images = AvatarTestSupport.images();
        assertEquals("true", System.getProperty("java.awt.headless"));
        for (int size : AvatarImages.SIZES) {
            var decoded = ImageIO.read(new ByteArrayInputStream(images.get(size)));
            assertEquals(size, decoded.getWidth()); assertEquals(size, decoded.getHeight());
            assertNotNull(ImageIO.read(new ByteArrayInputStream(AvatarImages.defaultImage(size))));
        }
    }
    @Test void rejectsOversizeUnsupportedAndHugeDimensionsBeforeDecoding() throws Exception {
        assertEquals(413, assertThrows(AvatarException.class, () -> AvatarImages.process(new ByteArrayInputStream(new byte[AvatarImages.MAX_UPLOAD + 1]))).status);
        assertEquals(415, assertThrows(AvatarException.class, () -> AvatarImages.process(new ByteArrayInputStream("<svg/>".getBytes()))).status);
        byte[] png = AvatarTestSupport.png(10, 10);
        java.nio.ByteBuffer.wrap(png, 16, 8).putInt(100000).putInt(100000);
        assertEquals("image_dimensions_exceeded", assertThrows(AvatarException.class, () -> AvatarImages.process(new ByteArrayInputStream(png))).code);
        assertThrows(AvatarException.class, () -> AvatarImages.defaultImage(100));
    }
    @Test void acceptsFourMegapixelsButRejectsMore() throws Exception {
        assertEquals(AvatarImages.SIZES, AvatarImages.process(new ByteArrayInputStream(AvatarTestSupport.png(2000, 2000))).keySet());
        assertEquals(413, assertThrows(AvatarException.class,
                () -> AvatarImages.process(new ByteArrayInputStream(AvatarTestSupport.png(2001, 2000)))).status);
    }
}
