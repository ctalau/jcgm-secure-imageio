package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utilities for creating test image data.
 */
final class TestImageUtils {

    private TestImageUtils() {
    }

    /** Creates a small valid PNG as raw bytes. */
    static byte[] createSmallPNG() throws IOException {
        return createPNG(10, 10);
    }

    /** Creates a PNG of the given dimensions as raw bytes. */
    static byte[] createPNG(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    /** Creates a larger PNG to increase processing time. */
    static byte[] createLargePNG() throws IOException {
        return createPNG(2048, 2048);
    }

    /** Returns bytes that are not a valid image in any format. */
    static byte[] createGarbageData() {
        byte[] data = new byte[1024];
        new java.util.Random(42).nextBytes(data);
        return data;
    }

    /** Returns a single byte — too short for any format. */
    static byte[] createSingleByte() {
        return new byte[]{0x42};
    }

    /**
     * Creates a minimal valid CGM binary file.
     * CGM Binary Encoding (ISO 8632-3):
     *   header = (elementClass << 12) | (elementId << 5) | paramLength
     */
    static byte[] createMinimalCGM() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        // BEGIN METAFILE (class=0, id=1) with name "test"
        byte[] name = "test".getBytes(StandardCharsets.US_ASCII);
        writeCGMCommand(dos, 0, 1, new byte[]{(byte) name.length}, name);

        // METAFILE VERSION (class=1, id=1) = 1
        writeCGMCommand(dos, 1, 1, shortBytes(1));

        // METAFILE ELEMENT LIST (class=1, id=11)
        // Format: numPairs(short), then pairs of (class, elementId) each as short
        // Drawing set: (0, -1) = "all delimiter elements"
        writeCGMCommand(dos, 1, 11, shortBytes(1), shortBytes(0), shortBytes(-1));

        // VDC TYPE (class=1, id=3) = integer(0)
        writeCGMCommand(dos, 1, 3, shortBytes(0));

        // BEGIN PICTURE (class=0, id=3) with name "p"
        writeCGMCommand(dos, 0, 3, new byte[]{1}, "p".getBytes(StandardCharsets.US_ASCII));

        // VDC EXTENT (class=2, id=6) = (0,0)-(200,200)
        writeCGMCommand(dos, 2, 6,
                shortBytes(0), shortBytes(0),
                shortBytes(200), shortBytes(200));

        // BEGIN PICTURE BODY (class=0, id=4)
        writeCGMCommand(dos, 0, 4);

        // POLYLINE (class=4, id=1) — line from (0,0) to (200,200)
        writeCGMCommand(dos, 4, 1,
                shortBytes(0), shortBytes(0),
                shortBytes(200), shortBytes(200));

        // END PICTURE (class=0, id=5)
        writeCGMCommand(dos, 0, 5);

        // END METAFILE (class=0, id=2)
        writeCGMCommand(dos, 0, 2);

        return out.toByteArray();
    }

    private static void writeCGMCommand(DataOutputStream dos, int cls, int id,
                                        byte[]... parts) throws IOException {
        int paramLen = 0;
        for (byte[] p : parts) paramLen += p.length;

        if (paramLen <= 30) {
            dos.writeShort((cls << 12) | (id << 5) | paramLen);
        } else {
            dos.writeShort((cls << 12) | (id << 5) | 31);
            dos.writeShort(paramLen);
        }
        for (byte[] p : parts) dos.write(p);
        if (paramLen % 2 != 0) dos.writeByte(0); // pad
    }

    private static byte[] shortBytes(int v) {
        return new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }
}
