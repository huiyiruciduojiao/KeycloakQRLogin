package top.ysit.qrlogin.auth;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;
import top.ysit.qrlogin.core.util.QRCodeUtil;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QRCodeUtilTest {
    @Test
    void producesReadablePngWithoutProductionAwtRenderer() throws Exception {
        String payload = "{\"v\":2,\"transaction\":\"production-headless-test\"}";
        String dataUrl = QRCodeUtil.toDataUrl(payload, 320);

        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);

        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(320, image.getWidth());
        assertEquals(320, image.getHeight());
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        assertEquals(payload, decoded);
    }
}
