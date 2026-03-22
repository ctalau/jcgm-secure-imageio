package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Permission;

/**
 * Subprocess entry point for secure image rendering.
 * Format-agnostic — delegates to whatever ImageIO readers are on the classpath
 * (e.g. jcgm-image for CGM files).
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
        installSecurityRestrictions();

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

    private static void writeError(DataOutputStream out, String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeInt(msgBytes.length);
        out.write(msgBytes);
        out.flush();
    }

    @SuppressWarnings("removal")
    private static void installSecurityRestrictions() {
        // SecurityManager is deprecated in Java 17 but still functional.
        // The subprocess is started with -Djava.security.manager=allow.
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(Permission perm) {
                    // Allow reading system properties
                    if (perm instanceof java.util.PropertyPermission
                            && "read".equals(perm.getActions())) {
                        return;
                    }

                    // Allow runtime permissions needed for classloading and I/O
                    if (perm instanceof RuntimePermission) {
                        String name = perm.getName();
                        if (name.startsWith("access") || name.equals("getenv.*")
                                || name.equals("createClassLoader")
                                || name.equals("getClassLoader")
                                || name.equals("setSecurityManager")
                                || name.startsWith("loadLibrary")
                                || name.equals("readFileDescriptor")
                                || name.equals("writeFileDescriptor")) {
                            return;
                        }
                    }

                    // Allow reading files (classpath JARs, font files, etc.)
                    if (perm instanceof FilePermission) {
                        if ("read".equals(perm.getActions())) {
                            return;
                        }
                        throw new SecurityException("File write/execute denied: " + perm);
                    }

                    // Deny network access
                    if (perm instanceof java.net.SocketPermission
                            || perm instanceof java.net.NetPermission) {
                        throw new SecurityException("Network access denied: " + perm);
                    }
                }

                @Override
                public void checkPermission(Permission perm, Object context) {
                    checkPermission(perm);
                }
            });
        } catch (Exception e) {
            System.err.println("WARNING: Could not install SecurityManager: " + e.getMessage());
        }
    }
}
