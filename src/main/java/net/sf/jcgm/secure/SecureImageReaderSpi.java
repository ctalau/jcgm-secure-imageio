package net.sf.jcgm.secure;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Abstract SPI base for secure image readers.
 * Subclasses provide format-specific metadata (names, suffixes, MIME types,
 * magic-byte detection) while the actual decoding is always delegated to
 * an isolated subprocess.
 */
public abstract class SecureImageReaderSpi extends ImageReaderSpi {

    protected SecureImageReaderSpi(String vendor, String version,
                                   String[] formatNames, String[] suffixes,
                                   String[] mimeTypes, String readerClassName) {
        super(vendor, version, formatNames, suffixes, mimeTypes,
                readerClassName,
                new Class<?>[]{ImageInputStream.class},
                null, false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public abstract boolean canDecodeInput(Object source) throws IOException;

    @Override
    public ImageReader createReaderInstance(Object extension) throws IOException {
        return new SecureImageReader(this);
    }

    @Override
    public abstract String getDescription(Locale locale);
}
