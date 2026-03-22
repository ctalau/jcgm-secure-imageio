package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Utilities for creating test image data.
 * Uses PNG (universally supported by ImageIO) so that the secure wrapper
 * can be tested independently of any third-party decoder like jcgm-image.
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
}
