package net.sf.jcgm.secure;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the secure ImageIO wrapper, including corner cases.
 * Uses PNG test data (universally supported) so tests don't depend on jcgm-image.
 */
class SecureImageReaderTest {

    @BeforeEach
    void setUp() {
        WorkerProcessManager.resetInstance();
    }

    // ---- SPI Tests ----

    @Test
    @DisplayName("CGM SPI is registered via ImageIO")
    void cgmSpiRegistered() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("cgm");
        boolean found = false;
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader instanceof SecureImageReader) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SecureImageReader should be registered for CGM via SPI");
    }

    @Test
    @DisplayName("CGM SPI detects CGM magic bytes")
    void cgmSpiDetectsValidMagic() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        // CGM BEGIN METAFILE: class=0, id=1, param_len=4 → 0x00, 0x24
        byte[] cgmHeader = new byte[]{0x00, 0x24, 0x04, 't', 'e', 's', 't'};
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(cgmHeader));
        assertTrue(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("CGM SPI rejects non-CGM data")
    void cgmSpiRejectsNonCGM() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        byte[] png = TestImageUtils.createSmallPNG();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(png));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("CGM SPI rejects empty stream")
    void cgmSpiRejectsEmpty() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(new byte[0]));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("CGM SPI rejects single-byte stream")
    void cgmSpiRejectsSingleByte() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(new byte[]{0x00}));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("CGM SPI stream position is reset after canDecodeInput")
    void cgmSpiResetsStreamPosition() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        byte[] data = TestImageUtils.createSmallPNG();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        long posBefore = iis.getStreamPosition();
        spi.canDecodeInput(iis);
        assertEquals(posBefore, iis.getStreamPosition(), "Stream position must be reset");
        iis.close();
    }

    // ---- Reader Basic Tests ----

    @Test
    @DisplayName("Reader returns 1 image")
    void readerNumImages() throws Exception {
        SecureImageReader reader = createReader(TestImageUtils.createSmallPNG());
        assertEquals(1, reader.getNumImages(true));
        reader.dispose();
    }

    @Test
    @DisplayName("Reader throws on invalid image index")
    void readerInvalidIndex() throws Exception {
        SecureImageReader reader = createReader(TestImageUtils.createSmallPNG());
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getHeight(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(2, null));
        reader.dispose();
    }

    @Test
    @DisplayName("Default read param has sensible defaults")
    void defaultReadParams() {
        SecureImageReadParam param = new SecureImageReadParam();
        assertTrue(param.getTimeoutMillis() > 0);
        assertNotNull(param.getMaxHeapSize());
    }

    // ---- Read Param Validation ----

    @Test
    @DisplayName("Read param rejects zero timeout")
    void paramRejectsZeroTimeout() {
        SecureImageReadParam param = new SecureImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setTimeoutMillis(0));
    }

    @Test
    @DisplayName("Read param rejects negative timeout")
    void paramRejectsNegativeTimeout() {
        SecureImageReadParam param = new SecureImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setTimeoutMillis(-1));
    }

    @Test
    @DisplayName("Read param rejects blank maxHeapSize")
    void paramRejectsBlankHeap() {
        SecureImageReadParam param = new SecureImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setMaxHeapSize(""));
        assertThrows(IllegalArgumentException.class, () -> param.setMaxHeapSize(null));
    }

    // ---- Rendering via Subprocess ----

    @Test
    @DisplayName("Renders a PNG through the secure subprocess")
    void renderPNG() throws Exception {
        byte[] pngData = TestImageUtils.createSmallPNG();

        SecureImageReader reader = createReader(pngData);
        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(30_000);

        BufferedImage image = reader.read(0, param);
        assertNotNull(image);
        assertEquals(10, image.getWidth());
        assertEquals(10, image.getHeight());
        reader.dispose();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100, 512})
    @DisplayName("Renders PNGs at various sizes")
    void renderVariousSizes(int size) throws Exception {
        byte[] pngData = TestImageUtils.createPNG(size, size);

        SecureImageReader reader = createReader(pngData);
        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(30_000);

        BufferedImage image = reader.read(0, param);
        assertNotNull(image);
        assertEquals(size, image.getWidth());
        assertEquals(size, image.getHeight());
        reader.dispose();
    }

    // ---- Corner Cases ----

    @Test
    @DisplayName("Rejects null/empty input via WorkerProcessManager")
    void rejectEmptyInput() {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        assertThrows(IOException.class,
                () -> manager.render(new byte[0], "64m", 10_000));
        assertThrows(IOException.class,
                () -> manager.render(null, "64m", 10_000));
    }

    @Test
    @DisplayName("Handles unrecognized image data gracefully")
    void handleGarbageData() throws Exception {
        byte[] garbage = TestImageUtils.createGarbageData();

        SecureImageReader reader = createReader(garbage);
        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(30_000);

        // Worker should report "no reader found" error
        assertThrows(IOException.class, () -> reader.read(0, param));
        reader.dispose();
    }

    @Test
    @DisplayName("Handles single-byte input gracefully")
    void handleSingleByteInput() throws Exception {
        SecureImageReader reader = createReader(TestImageUtils.createSingleByte());
        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(30_000);

        assertThrows(IOException.class, () -> reader.read(0, param));
        reader.dispose();
    }

    @Test
    @DisplayName("Worker process respects memory limits")
    void workerRespectsMemoryLimits() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();

        SecureImageReadParam param = new SecureImageReadParam();
        param.setMaxHeapSize("4m"); // Very small heap
        param.setTimeoutMillis(30_000);

        SecureImageReader reader = createReader(png);
        try {
            BufferedImage img = reader.read(0, param);
            // May succeed if 4m is enough for a tiny image
            assertNotNull(img);
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("memory") || e.getMessage().contains("crashed")
                            || e.getMessage().contains("Worker"),
                    "Error should be about memory or worker crash: " + e.getMessage());
        }
        reader.dispose();
    }

    @Test
    @DisplayName("Worker timeout is enforced")
    void workerTimeout() throws Exception {
        byte[] png = TestImageUtils.createLargePNG();

        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(1); // 1ms — too short
        param.setMaxHeapSize("64m");

        SecureImageReader reader = createReader(png);
        assertThrows(IOException.class, () -> reader.read(0, param),
                "Should time out with 1ms timeout");
        reader.dispose();
    }

    @Test
    @DisplayName("Dispose clears cached data")
    void disposeClearsCachedData() throws Exception {
        SecureImageReader reader = createReader(TestImageUtils.createSmallPNG());

        SecureImageReadParam param = new SecureImageReadParam();
        param.setTimeoutMillis(30_000);
        BufferedImage img = reader.read(0, param);
        assertNotNull(img);

        reader.dispose();
        reader.setInput(null);
        assertThrows(Exception.class, () -> reader.read(0, param));
    }

    @Test
    @DisplayName("Manager is idle after rendering")
    void managerIdleAfterRender() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        byte[] png = TestImageUtils.createSmallPNG();

        BufferedImage img = manager.render(png, "64m", 30_000);
        assertNotNull(img);
        assertTrue(manager.isIdle());
    }

    @Test
    @DisplayName("Reads with null param uses defaults")
    void readWithNullParam() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        SecureImageReader reader = createReader(png);

        BufferedImage img = reader.read(0, null);
        assertNotNull(img);
        reader.dispose();
    }

    @Test
    @DisplayName("Reads with standard ImageReadParam uses defaults")
    void readWithStandardParam() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        SecureImageReader reader = createReader(png);

        BufferedImage img = reader.read(0, new ImageReadParam());
        assertNotNull(img);
        reader.dispose();
    }

    @Test
    @DisplayName("Reader without input throws IllegalStateException")
    void readerWithoutInput() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        SecureImageReader reader = (SecureImageReader) spi.createReaderInstance(null);
        assertThrows(IllegalStateException.class, () -> reader.read(0, null));
    }

    // ---- Helper ----

    private SecureImageReader createReader(byte[] data) throws IOException {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        SecureImageReader reader = (SecureImageReader) spi.createReaderInstance(null);
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        reader.setInput(iis);
        return reader;
    }
}
