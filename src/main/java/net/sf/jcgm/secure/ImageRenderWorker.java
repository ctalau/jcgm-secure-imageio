package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Subprocess entry point for secure image rendering.
 * Format-agnostic — delegates to whatever ImageIO readers are on the classpath
 * (e.g. jcgm-image for CGM files).
 *
 * <p>Security is enforced externally via:
 * <ul>
 *   <li>{@code -Djava.security.manager} with a restrictive policy file
 *       ({@code worker-security.policy}) that only allows reading specific paths</li>
 *   <li>{@code -Xmx} memory limits</li>
 *   <li>Separate classpath (only worker + decoder jars, no application classes)</li>
 * </ul>
 *
 * <p>Protocol (all values big-endian):
 * <ul>
 *   <li>Input:  [4 bytes: image data length][image bytes]</li>
 *   <li>Output success: [1 byte: 0][4 bytes: PNG length][PNG bytes]</li>
 *   <li>Output error:   [1 byte: 1][4 bytes: msg length][UTF-8 message]</li>
 * </ul>
 */
public class ImageRenderWorker {

    private static final int MAX_INPUT_SIZE = 100 * 1024 * 1024; // 100 MB

    public static void main(String[] args) {
        // Deregister our secure wrapper SPI to prevent recursive subprocess spawning.
        // The worker should only use the real decoders (e.g. jcgm-image's CGMImageReader).
        deregisterSecureSpis();

        try {
            DataInputStream in = new DataInputStream(System.in);
            DataOutputStream out = new DataOutputStream(System.out);

            int dataLength = in.readInt();
            if (dataLength <= 0 || dataLength > MAX_INPUT_SIZE) {
                writeError(out, "Invalid image data length: " + dataLength);
                return;
            }

            byte[] imageData = new byte[dataLength];
            in.readFully(imageData);

            // Delegate to standard ImageIO — the actual decoder (e.g. jcgm-image)
            // is picked up from the classpath via SPI
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                writeError(out, "No ImageIO reader found for the provided data");
                return;
            }

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", pngOut);
            byte[] pngData = pngOut.toByteArray();

            out.writeByte(0); // success
            out.writeInt(pngData.length);
            out.write(pngData);
            out.flush();

        } catch (OutOfMemoryError e) {
            try {
                writeError(new DataOutputStream(System.out),
                        "Out of memory: " + e.getMessage());
            } catch (IOException ignored) {
            }
            System.exit(2);
        } catch (Exception e) {
            try {
                writeError(new DataOutputStream(System.out),
                        "Render failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (IOException ignored) {
            }
            System.exit(1);
        }
    }

    private static void deregisterSecureSpis() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        // Collect first, then deregister to avoid ConcurrentModificationException
        java.util.List<ImageReaderSpi> toRemove = new java.util.ArrayList<>();
        Iterator<ImageReaderSpi> spis = registry.getServiceProviders(ImageReaderSpi.class, false);
        while (spis.hasNext()) {
            ImageReaderSpi spi = spis.next();
            if (spi instanceof SecureImageReaderSpi) {
                toRemove.add(spi);
            }
        }
        for (ImageReaderSpi spi : toRemove) {
            registry.deregisterServiceProvider(spi, ImageReaderSpi.class);
        }
    }

    private static void writeError(DataOutputStream out, String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeInt(msgBytes.length);
        out.write(msgBytes);
        out.flush();
    }
}
