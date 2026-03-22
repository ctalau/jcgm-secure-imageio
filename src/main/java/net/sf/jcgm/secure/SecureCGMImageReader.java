package net.sf.jcgm.secure;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * A secure ImageIO reader for CGM files.
 * Delegates actual CGM parsing and rendering to an isolated subprocess
 * managed by {@link WorkerProcessManager}.
 */
public class SecureCGMImageReader extends ImageReader {

    private byte[] cgmData;

    protected SecureCGMImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return getReadParam().getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return getReadParam().getHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(
                BufferedImage.TYPE_INT_ARGB);
        return List.of(type).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        readCGMData();

        SecureCGMImageReadParam readParam;
        if (param instanceof SecureCGMImageReadParam p) {
            readParam = p;
        } else {
            readParam = getReadParam();
        }

        try {
            return WorkerProcessManager.getInstance().render(
                    cgmData,
                    readParam.getWidth(),
                    readParam.getHeight(),
                    readParam.getMaxHeapSize(),
                    readParam.getTimeoutMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Rendering interrupted", e);
        }
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new SecureCGMImageReadParam();
    }

    private SecureCGMImageReadParam getReadParam() {
        return new SecureCGMImageReadParam();
    }

    private void readCGMData() throws IOException {
        if (cgmData != null) return;

        Object inputObj = getInput();
        if (inputObj == null) {
            throw new IllegalStateException("No input set");
        }
        if (!(inputObj instanceof ImageInputStream stream)) {
            throw new IllegalStateException("Input must be an ImageInputStream");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        cgmData = baos.toByteArray();
        if (cgmData.length == 0) {
            throw new IOException("Empty CGM input");
        }
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex must be 0, got " + imageIndex);
        }
    }

    @Override
    public void dispose() {
        cgmData = null;
        super.dispose();
    }
}
