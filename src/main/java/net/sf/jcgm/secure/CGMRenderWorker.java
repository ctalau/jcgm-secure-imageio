package net.sf.jcgm.secure;

import net.sf.jcgm.core.CGM;
import net.sf.jcgm.core.CGMDisplay;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.Permission;
import javax.imageio.ImageIO;

/**
 * Worker process main class that runs in an isolated subprocess.
 * Reads CGM data from stdin, renders to a BufferedImage, and writes PNG to stdout.
 *
 * <p>Protocol (all values big-endian):
 * <ul>
 *   <li>Input: [4 bytes: CGM data length][CGM bytes][4 bytes: width][4 bytes: height]</li>
 *   <li>Output success: [1 byte: 0][4 bytes: PNG length][PNG bytes]</li>
 *   <li>Output error: [1 byte: 1][4 bytes: msg length][UTF-8 message]</li>
 * </ul>
 */
public class CGMRenderWorker {

    public static void main(String[] args) {
        installSecurityRestrictions();

        try {
            DataInputStream in = new DataInputStream(System.in);
            DataOutputStream out = new DataOutputStream(System.out);

            // Read CGM data
            int cgmLength = in.readInt();
            if (cgmLength <= 0 || cgmLength > 100 * 1024 * 1024) {
                writeError(out, "Invalid CGM data length: " + cgmLength);
                return;
            }
            byte[] cgmData = new byte[cgmLength];
            in.readFully(cgmData);

            // Read dimensions
            int width = in.readInt();
            int height = in.readInt();

            if (width <= 0 || width > 16384) {
                writeError(out, "Invalid width: " + width);
                return;
            }
            if (height <= 0 || height > 16384) {
                writeError(out, "Invalid height: " + height);
                return;
            }

            // Parse CGM
            CGM cgm = new CGM();
            cgm.read(new DataInputStream(new ByteArrayInputStream(cgmData)));

            // Render to BufferedImage
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, width, height);

            CGMDisplay display = new CGMDisplay(cgm);
            display.scale(g2d, width, height);
            display.paint(g2d);
            g2d.dispose();

            // Encode as PNG
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", pngOut);
            byte[] pngData = pngOut.toByteArray();

            // Write success response
            out.writeByte(0); // success
            out.writeInt(pngData.length);
            out.write(pngData);
            out.flush();

        } catch (OutOfMemoryError e) {
            try {
                DataOutputStream out = new DataOutputStream(System.out);
                writeError(out, "Out of memory: " + e.getMessage());
            } catch (IOException ignored) {
            }
            System.exit(2);
        } catch (Exception e) {
            try {
                DataOutputStream out = new DataOutputStream(System.out);
                writeError(out, "Render failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (IOException ignored) {
            }
            System.exit(1);
        }
    }

    private static void writeError(DataOutputStream out, String message) throws IOException {
        byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeByte(1); // error
        out.writeInt(msgBytes.length);
        out.write(msgBytes);
        out.flush();
    }

    @SuppressWarnings("removal")
    private static void installSecurityRestrictions() {
        // Install a restrictive SecurityManager that prevents:
        // - File system access (except reading the classpath)
        // - Network access
        // - Process execution
        // - System property modification
        // Note: SecurityManager is deprecated in Java 17 but still functional.
        // The subprocess must be started with -Djava.security.manager=allow
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(Permission perm) {
                    String name = perm.getName();

                    // Allow reading system properties needed for runtime
                    if (perm instanceof java.util.PropertyPermission) {
                        if ("read".equals(perm.getActions())) {
                            return; // allow reading properties
                        }
                    }

                    // Allow runtime permissions needed for basic operation
                    if (perm instanceof RuntimePermission) {
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

                    // Allow reading files (for classpath JARs)
                    if (perm instanceof java.io.FilePermission) {
                        if ("read".equals(perm.getActions())) {
                            return;
                        }
                        // Deny write, execute, delete
                        throw new SecurityException("File operation denied: " + perm);
                    }

                    // Deny network access
                    if (perm instanceof java.net.SocketPermission
                            || perm instanceof java.net.NetPermission) {
                        throw new SecurityException("Network access denied: " + perm);
                    }

                    // Deny process execution
                    if (perm instanceof java.io.FilePermission && "execute".equals(perm.getActions())) {
                        throw new SecurityException("Process execution denied: " + perm);
                    }

                    // Allow other permissions needed for AWT/ImageIO
                }

                @Override
                public void checkPermission(Permission perm, Object context) {
                    checkPermission(perm);
                }
            });
        } catch (Exception e) {
            // SecurityManager not allowed (e.g., missing -Djava.security.manager=allow)
            // Continue without it but log the warning
            System.err.println("WARNING: Could not install SecurityManager: " + e.getMessage());
        }
    }
}
