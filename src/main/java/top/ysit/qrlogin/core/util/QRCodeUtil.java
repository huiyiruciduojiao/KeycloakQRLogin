package top.ysit.qrlogin.core.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

public final class QRCodeUtil {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private QRCodeUtil() {
    }

    public static String toDataUrl(String text, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(toPng(matrix));
    }

    private static byte[] toPng(BitMatrix matrix) throws IOException {
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        try (DataOutputStream png = new DataOutputStream(pngBytes)) {
            png.write(PNG_SIGNATURE);

            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(13);
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(matrix.getWidth());
                header.writeInt(matrix.getHeight());
                header.writeByte(1); // one-bit grayscale
                header.writeByte(0); // grayscale color type
                header.writeByte(0); // DEFLATE compression
                header.writeByte(0); // adaptive filtering
                header.writeByte(0); // no interlace
            }
            writeChunk(png, "IHDR", headerBytes.toByteArray());

            int rowBytes = (matrix.getWidth() + 7) / 8;
            byte[] scanlines = new byte[(rowBytes + 1) * matrix.getHeight()];
            int offset = 0;
            for (int y = 0; y < matrix.getHeight(); y++) {
                scanlines[offset++] = 0; // PNG filter: None
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (!matrix.get(x, y)) {
                        scanlines[offset + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                    }
                }
                offset += rowBytes;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(compressedBytes)) {
                compressed.write(scanlines);
            }
            writeChunk(png, "IDAT", compressedBytes.toByteArray());
            writeChunk(png, "IEND", new byte[0]);
        }
        return pngBytes.toByteArray();
    }

    private static void writeChunk(DataOutputStream png, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);

        png.writeInt(data.length);
        png.write(typeBytes);
        png.write(data);
        png.writeInt((int) crc.getValue());
    }
}
