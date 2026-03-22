package net.sf.jcgm.secure;

import javax.imageio.stream.ImageInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;

/**
 * SPI registration for the secure CGM image reader.
 * Only provides CGM-specific metadata and magic-byte detection.
 * All decoding is delegated to the jcgm-image library running in
 * an isolated subprocess via {@link SecureImageReaderSpi}.
 */
public class SecureCGMImageReaderSpi extends SecureImageReaderSpi {

    private static final String VENDOR = "jcgm-secure-imageio";
    private static final String VERSION = "1.0";
    private static final String[] FORMAT_NAMES = {"cgm", "CGM"};
    private static final String[] SUFFIXES = {"cgm", "cgmz"};
    private static final String[] MIME_TYPES = {"image/cgm"};

    public SecureCGMImageReaderSpi() {
        super(VENDOR, VERSION, FORMAT_NAMES, SUFFIXES, MIME_TYPES,
                SecureImageReader.class.getName());
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return false;
        }
        stream.mark();
        try {
            byte b0 = stream.readByte();
            byte b1 = stream.readByte();
            // CGM binary encoding (ISO 8632-3): BEGIN METAFILE command
            // element class=0, element id=1
            // Header: (class << 12) | (id << 5) | paramLength
            // → first byte = 0x00, second byte = 0x20 + paramLength(0..30)
            return b0 == 0x00 && (b1 & 0xE0) == 0x20;
        } catch (EOFException e) {
            return false;
        } finally {
            stream.reset();
        }
    }

    @Override
    public String getDescription(Locale locale) {
        return "Secure CGM (Computer Graphics Metafile) Image Reader";
    }
}
