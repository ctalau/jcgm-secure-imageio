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
 * Abstract secure ImageIO reader that delegates actual image decoding to
 * an isolated subprocess. The subprocess uses standard {@code ImageIO.read()}
 * with the real decoder (e.g. jcgm-image) on its classpath.
 *
 * <p>Subclasses only need to exist to register format-specific SPI metadata.
 * No format-specific parsing logic belongs here.
 */
public class SecureImageReader extends ImageReader {

    private byte[] imageData;

    protected SecureImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        // Width is determined by the delegate reader in the subprocess;
        // we cannot know it without decoding. Return -1 per ImageReader contract
        // to indicate "unknown until read".
        return -1;
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return -1;
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
        loadImageData();

        SecureImageReadParam readParam;
        if (param instanceof SecureImageReadParam p) {
            readParam = p;
        } else {
            readParam = new SecureImageReadParam();
        }

        try {
            return WorkerProcessManager.getInstance().render(
                    imageData,
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
        return new SecureImageReadParam();
    }

    private void loadImageData() throws IOException {
        if (imageData != null) return;

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
        imageData = baos.toByteArray();
        if (imageData.length == 0) {
            throw new IOException("Empty image input");
        }
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex must be 0, got " + imageIndex);
        }
    }

    @Override
    public void dispose() {
        imageData = null;
        super.dispose();
    }
}
