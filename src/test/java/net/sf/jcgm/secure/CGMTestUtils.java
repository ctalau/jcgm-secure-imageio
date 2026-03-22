package net.sf.jcgm.secure;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Utilities for creating minimal valid CGM binary data for testing.
 * CGM Binary Encoding (ISO 8632-3) uses 16-bit command headers:
 * bits 15-12: element class
 * bits 11-5: element id
 * bits 4-0: parameter list length (0-30, or 31 = long form)
 */
public final class CGMTestUtils {

    private CGMTestUtils() {
    }

    /**
     * Creates a minimal valid CGM binary file.
     * Contains: BEGIN METAFILE, BEGIN PICTURE, BEGIN PICTURE BODY,
     * END PICTURE, END METAFILE.
     */
    public static byte[] createMinimalCGM() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        // BEGIN METAFILE (class=0, id=1) with short name
        String name = "test";
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        writeCommand(dos, 0, 1, new byte[]{(byte) nameBytes.length}, nameBytes);

        // METAFILE VERSION (class=1, id=1) = 1
        writeCommand(dos, 1, 1, shortToBytes(1));

        // METAFILE ELEMENT LIST (class=1, id=11) - drawing set
        writeCommand(dos, 1, 11, shortToBytes(1), shortToBytes(-1));

        // VDC TYPE (class=1, id=3) = integer(0)
        writeCommand(dos, 1, 3, shortToBytes(0));

        // BEGIN PICTURE (class=0, id=3) with name
        String picName = "pic1";
        byte[] picNameBytes = picName.getBytes(StandardCharsets.US_ASCII);
        writeCommand(dos, 0, 3, new byte[]{(byte) picNameBytes.length}, picNameBytes);

        // VDC EXTENT (class=2, id=6) = (0,0)-(32767,32767)
        writeCommand(dos, 2, 6,
                shortToBytes(0), shortToBytes(0),
                shortToBytes(32767), shortToBytes(32767));

        // BEGIN PICTURE BODY (class=0, id=4)
        writeCommand(dos, 0, 4);

        // POLYLINE (class=4, id=1) - a simple line from (0,0) to (32767,32767)
        writeCommand(dos, 4, 1,
                shortToBytes(0), shortToBytes(0),
                shortToBytes(32767), shortToBytes(32767));

        // END PICTURE (class=0, id=5)
        writeCommand(dos, 0, 5);

        // END METAFILE (class=0, id=2)
        writeCommand(dos, 0, 2);

        return out.toByteArray();
    }

    /**
     * Creates a CGM with a specified amount of drawing commands to increase complexity.
     */
    public static byte[] createCGMWithLines(int lineCount) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        // BEGIN METAFILE
        String name = "stress";
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        writeCommand(dos, 0, 1, new byte[]{(byte) nameBytes.length}, nameBytes);

        writeCommand(dos, 1, 1, shortToBytes(1));
        writeCommand(dos, 1, 11, shortToBytes(1), shortToBytes(-1));
        writeCommand(dos, 1, 3, shortToBytes(0));

        // BEGIN PICTURE
        String picName = "p";
        writeCommand(dos, 0, 3, new byte[]{(byte) 1}, picName.getBytes(StandardCharsets.US_ASCII));

        // VDC EXTENT
        writeCommand(dos, 2, 6,
                shortToBytes(0), shortToBytes(0),
                shortToBytes(32767), shortToBytes(32767));

        // BEGIN PICTURE BODY
        writeCommand(dos, 0, 4);

        // Draw multiple polylines
        for (int i = 0; i < lineCount; i++) {
            short x1 = (short) (i * 100 % 32767);
            short y1 = (short) (i * 73 % 32767);
            short x2 = (short) ((i * 100 + 500) % 32767);
            short y2 = (short) ((i * 73 + 500) % 32767);
            writeCommand(dos, 4, 1,
                    shortToBytes(x1), shortToBytes(y1),
                    shortToBytes(x2), shortToBytes(y2));
        }

        // END PICTURE
        writeCommand(dos, 0, 5);

        // END METAFILE
        writeCommand(dos, 0, 2);

        return out.toByteArray();
    }

    /**
     * Creates intentionally corrupted CGM data.
     */
    public static byte[] createCorruptedCGM() {
        return new byte[]{0x00, 0x25, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x7F, (byte) 0xFF};
    }

    /**
     * Creates a truncated CGM (starts valid but ends abruptly).
     */
    public static byte[] createTruncatedCGM() throws IOException {
        byte[] full = createMinimalCGM();
        byte[] truncated = new byte[full.length / 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private static void writeCommand(DataOutputStream dos, int elementClass, int elementId,
                                     byte[]... paramParts) throws IOException {
        // Calculate total parameter length
        int paramLength = 0;
        for (byte[] part : paramParts) {
            paramLength += part.length;
        }

        if (paramLength <= 30) {
            // Short form command header
            int header = (elementClass << 12) | (elementId << 5) | paramLength;
            dos.writeShort(header);
            for (byte[] part : paramParts) {
                dos.write(part);
            }
            // Pad to even number of bytes
            if (paramLength % 2 != 0) {
                dos.writeByte(0);
            }
        } else {
            // Long form: header with length=31, then 16-bit actual length
            int header = (elementClass << 12) | (elementId << 5) | 31;
            dos.writeShort(header);
            dos.writeShort(paramLength);
            for (byte[] part : paramParts) {
                dos.write(part);
            }
            if (paramLength % 2 != 0) {
                dos.writeByte(0);
            }
        }
    }

    private static byte[] shortToBytes(int value) {
        return new byte[]{
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }
}
