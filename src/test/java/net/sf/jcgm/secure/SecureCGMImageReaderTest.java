package net.sf.jcgm.secure;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the secure CGM ImageIO plugin including corner cases.
 */
class SecureCGMImageReaderTest {

    @BeforeEach
    void setUp() {
        WorkerProcessManager.resetInstance();
    }

    // ---- SPI Registration Tests ----

    @Test
    @DisplayName("SPI is registered and can be found via ImageIO")
    void spiRegistered() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("cgm");
        boolean found = false;
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader instanceof SecureCGMImageReader) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SecureCGMImageReader should be registered via SPI");
    }

    @Test
    @DisplayName("SPI can detect CGM magic bytes")
    void spiCanDecodeValidCGM() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        byte[] cgm = CGMTestUtils.createMinimalCGM();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(cgm));
        assertTrue(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("SPI rejects non-CGM data")
    void spiRejectsNonCGM() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(png));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("SPI rejects empty stream")
    void spiRejectsEmpty() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(new byte[0]));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    @Test
    @DisplayName("SPI rejects single-byte stream")
    void spiRejectsSingleByte() throws Exception {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(new byte[]{0x00}));
        assertFalse(spi.canDecodeInput(iis));
        iis.close();
    }

    // ---- Reader Basic Tests ----

    @Test
    @DisplayName("Reader returns correct number of images")
    void readerNumImages() throws Exception {
        SecureCGMImageReader reader = createReader(CGMTestUtils.createMinimalCGM());
        assertEquals(1, reader.getNumImages(true));
        reader.dispose();
    }

    @Test
    @DisplayName("Reader throws on invalid image index")
    void readerInvalidIndex() throws Exception {
        SecureCGMImageReader reader = createReader(CGMTestUtils.createMinimalCGM());
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getHeight(-1));
        reader.dispose();
    }

    @Test
    @DisplayName("Default read parameters are sensible")
    void defaultReadParams() {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        assertTrue(param.getWidth() > 0);
        assertTrue(param.getHeight() > 0);
        assertTrue(param.getDpi() > 0);
        assertTrue(param.getTimeoutMillis() > 0);
        assertNotNull(param.getMaxHeapSize());
    }

    // ---- Read Param Validation ----

    @Test
    @DisplayName("Read param rejects zero width")
    void paramRejectsZeroWidth() {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setWidth(0));
    }

    @Test
    @DisplayName("Read param rejects negative height")
    void paramRejectsNegativeHeight() {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setHeight(-1));
    }

    @Test
    @DisplayName("Read param rejects zero DPI")
    void paramRejectsZeroDpi() {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setDpi(0));
    }

    @Test
    @DisplayName("Read param rejects zero timeout")
    void paramRejectsZeroTimeout() {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        assertThrows(IllegalArgumentException.class, () -> param.setTimeoutMillis(0));
    }

    // ---- Rendering Tests ----

    @Test
    @DisplayName("Renders a minimal CGM to BufferedImage")
    void renderMinimalCGM() throws Exception {
        byte[] cgmData = CGMTestUtils.createMinimalCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(200);
        param.setHeight(150);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(cgmData);
        BufferedImage image = reader.read(0, param);

        assertNotNull(image);
        assertEquals(200, image.getWidth());
        assertEquals(150, image.getHeight());
        reader.dispose();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100, 1024})
    @DisplayName("Renders at various sizes")
    void renderVariousSizes(int size) throws Exception {
        byte[] cgmData = CGMTestUtils.createMinimalCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(size);
        param.setHeight(size);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(cgmData);
        BufferedImage image = reader.read(0, param);

        assertNotNull(image);
        assertEquals(size, image.getWidth());
        assertEquals(size, image.getHeight());
        reader.dispose();
    }

    // ---- Corner Cases ----

    @Test
    @DisplayName("Rejects null/empty input")
    void rejectEmptyInput() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        assertThrows(IOException.class,
                () -> manager.render(new byte[0], 100, 100, "64m", 10_000));
        assertThrows(IOException.class,
                () -> manager.render(null, 100, 100, "64m", 10_000));
    }

    @Test
    @DisplayName("Rejects invalid dimensions")
    void rejectInvalidDimensions() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        byte[] cgm = CGMTestUtils.createMinimalCGM();
        assertThrows(IllegalArgumentException.class,
                () -> manager.render(cgm, 0, 100, "64m", 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> manager.render(cgm, 100, -1, "64m", 10_000));
    }

    @Test
    @DisplayName("Handles corrupted CGM data gracefully")
    void handleCorruptedCGM() throws Exception {
        byte[] corrupted = CGMTestUtils.createCorruptedCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(100);
        param.setHeight(100);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(corrupted);
        // Should either render (with garbled output) or throw IOException
        // Should NOT hang or crash the JVM
        try {
            BufferedImage img = reader.read(0, param);
            // If it doesn't throw, at least verify it produced an image
            assertNotNull(img);
        } catch (IOException e) {
            // Expected for corrupted data
            assertNotNull(e.getMessage());
        }
        reader.dispose();
    }

    @Test
    @DisplayName("Handles truncated CGM data gracefully")
    void handleTruncatedCGM() throws Exception {
        byte[] truncated = CGMTestUtils.createTruncatedCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(100);
        param.setHeight(100);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(truncated);
        try {
            BufferedImage img = reader.read(0, param);
            assertNotNull(img);
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        reader.dispose();
    }

    @Test
    @DisplayName("Worker process respects memory limits")
    void workerRespectsMemoryLimits() throws Exception {
        // Use a very small heap to test that OOM is handled gracefully
        byte[] cgm = CGMTestUtils.createMinimalCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(100);
        param.setHeight(100);
        param.setMaxHeapSize("4m"); // Very small heap
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(cgm);
        try {
            BufferedImage img = reader.read(0, param);
            // May succeed if 4m is enough for a small image
            assertNotNull(img);
        } catch (IOException e) {
            // Expected if 4m is not enough
            assertTrue(e.getMessage().contains("memory") || e.getMessage().contains("crashed")
                            || e.getMessage().contains("Worker"),
                    "Error should be about memory or worker crash: " + e.getMessage());
        }
        reader.dispose();
    }

    @Test
    @DisplayName("Worker timeout works")
    void workerTimeout() throws Exception {
        // Create a very large CGM to potentially cause a timeout with a very short timeout
        byte[] cgm = CGMTestUtils.createCGMWithLines(1000);

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(4096);
        param.setHeight(4096);
        param.setTimeoutMillis(1); // 1ms timeout - should be too short
        param.setMaxHeapSize("64m");

        SecureCGMImageReader reader = createReader(cgm);
        assertThrows(IOException.class, () -> reader.read(0, param),
                "Should time out with 1ms timeout");
        reader.dispose();
    }

    @Test
    @DisplayName("Reader dispose clears cached data")
    void disposeClearsCachedData() throws Exception {
        SecureCGMImageReader reader = createReader(CGMTestUtils.createMinimalCGM());

        // First read should work
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(50);
        param.setHeight(50);
        param.setTimeoutMillis(30_000);
        BufferedImage img = reader.read(0, param);
        assertNotNull(img);

        // Dispose and verify input is cleared
        reader.dispose();
        reader.setInput(null);
        assertThrows(Exception.class, () -> reader.read(0, param));
    }

    @Test
    @DisplayName("Manager is idle after rendering")
    void managerIdleAfterRender() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        byte[] cgm = CGMTestUtils.createMinimalCGM();

        BufferedImage img = manager.render(cgm, 100, 100, "64m", 30_000);
        assertNotNull(img);
        assertTrue(manager.isIdle(), "Manager should be idle after rendering completes");
    }

    @Test
    @DisplayName("Renders with large dimensions within limits")
    void renderLargeDimensions() throws Exception {
        byte[] cgm = CGMTestUtils.createMinimalCGM();

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(2048);
        param.setHeight(2048);
        param.setMaxHeapSize("128m");
        param.setTimeoutMillis(60_000);

        SecureCGMImageReader reader = createReader(cgm);
        BufferedImage image = reader.read(0, param);
        assertNotNull(image);
        assertEquals(2048, image.getWidth());
        assertEquals(2048, image.getHeight());
        reader.dispose();
    }

    @Test
    @DisplayName("Random garbage bytes handled safely")
    void randomGarbageBytes() throws Exception {
        // Generate random bytes that don't look like CGM
        byte[] garbage = new byte[1024];
        new java.util.Random(42).nextBytes(garbage);

        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(100);
        param.setHeight(100);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(garbage);
        try {
            BufferedImage img = reader.read(0, param);
            // jcgm-core may tolerate garbage and produce an image
            assertNotNull(img);
        } catch (IOException e) {
            // Expected
            assertNotNull(e.getMessage());
        }
        reader.dispose();
    }

    @Test
    @DisplayName("Single byte input handled safely")
    void singleByteInput() throws Exception {
        SecureCGMImageReadParam param = new SecureCGMImageReadParam();
        param.setWidth(100);
        param.setHeight(100);
        param.setTimeoutMillis(30_000);

        SecureCGMImageReader reader = createReader(new byte[]{0x42});
        try {
            reader.read(0, param);
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        reader.dispose();
    }

    // ---- Helper ----

    private SecureCGMImageReader createReader(byte[] cgmData) throws IOException {
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        SecureCGMImageReader reader = (SecureCGMImageReader) spi.createReaderInstance(null);
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(cgmData));
        reader.setInput(iis);
        return reader;
    }
}
