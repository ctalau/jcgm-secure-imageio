package net.sf.jcgm.secure;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import java.io.IOException;
import java.util.Locale;

/**
 * Service provider for the secure CGM ImageIO reader.
 * Registers this reader with the ImageIO framework so that CGM files
 * can be read via {@code ImageIO.read()}.
 */
public class SecureCGMImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR = "jcgm-secure-imageio";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"cgm", "CGM"};
    private static final String[] SUFFIXES = {"cgm", "cgmz"};
    private static final String[] MIME_TYPES = {"image/cgm"};
    private static final String READER_CLASS_NAME =
            "net.sf.jcgm.secure.SecureCGMImageReader";

    // CGM binary encoding: first two bytes encode the BEGIN METAFILE element
    // Class 0 (delimiter), Element ID 1 (BEGIN METAFILE)
    // The first byte has high nibble = 0x00 (class 0, id 1 → 0x00 | (1 << 5 >> 8)... )
    // Actually CGM binary: element class (4 bits) | element id (7 bits) | parameter list length (5 bits)
    // For BEGIN METAFILE: class=0, id=1 → first two bytes: 0x00, 0x20+length
    private static final byte[] CGM_MAGIC = {0x00, 0x20};

    public SecureCGMImageReaderSpi() {
        super(VENDOR, VERSION, FORMAT_NAMES, SUFFIXES, MIME_TYPES,
                READER_CLASS_NAME,
                new Class<?>[]{javax.imageio.stream.ImageInputStream.class},
                null, false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof javax.imageio.stream.ImageInputStream stream)) {
            return false;
        }
        stream.mark();
        try {
            byte b0 = stream.readByte();
            byte b1 = stream.readByte();
            // CGM binary: BEGIN METAFILE command
            // Element class = 0 (delimiter), Element ID = 1
            // Encoded as: (class << 12) | (id << 5) | parameterListLength
            // class=0, id=1 → top nibble of first byte is 0, bits 5-11 encode id=1
            // So first two bytes: 0x00, 0x20 + (param length 0-30)
            return b0 == 0x00 && (b1 & 0xE0) == 0x20;
        } catch (java.io.EOFException e) {
            return false;
        } finally {
            stream.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) throws IOException {
        return new SecureCGMImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Secure CGM (Computer Graphics Metafile) Image Reader";
    }
}
