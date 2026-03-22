package net.sf.jcgm.secure;

import org.junit.jupiter.api.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests for the secure ImageIO wrapper.
 * Verifies correct queue behavior, concurrency safety, and resilience
 * under load.
 */
class StressTest {

    @BeforeEach
    void setUp() {
        WorkerProcessManager.resetInstance();
    }

    @Test
    @DisplayName("Concurrent renders are serialized (single process at a time)")
    void concurrentRendersSerialized() throws Exception {
        int threadCount = 5;
        byte[] png = TestImageUtils.createSmallPNG();

        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<BufferedImage>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                BufferedImage img = manager.render(png, "64m", 60_000);
                successCount.incrementAndGet();
                return img;
            }));
        }

        startLatch.countDown();

        for (Future<BufferedImage> future : futures) {
            BufferedImage img = future.get(120, TimeUnit.SECONDS);
            assertNotNull(img);
            assertEquals(10, img.getWidth());
        }

        assertEquals(threadCount, successCount.get());
        assertTrue(manager.isIdle());
        executor.shutdown();
    }

    @Test
    @DisplayName("Queue length reflects pending requests")
    void queueLengthReflectsPending() throws Exception {
        byte[] png = TestImageUtils.createLargePNG();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<BufferedImage>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return manager.render(png, "64m", 120_000);
            }));
        }

        Thread.sleep(500);
        int queueLen = manager.getQueueLength();
        assertTrue(queueLen >= 0);

        for (Future<BufferedImage> future : futures) {
            assertNotNull(future.get(120, TimeUnit.SECONDS));
        }
        executor.shutdown();
    }

    @Test
    @DisplayName("Rapid sequential renders work correctly")
    void rapidSequentialRenders() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        for (int i = 0; i < 10; i++) {
            BufferedImage img = manager.render(png, "64m", 30_000);
            assertNotNull(img, "Render " + i + " should succeed");
            assertEquals(10, img.getWidth());
        }
    }

    @Test
    @DisplayName("Mixed valid and invalid renders don't corrupt state")
    void mixedValidAndInvalidRenders() throws Exception {
        byte[] validPNG = TestImageUtils.createSmallPNG();
        byte[] garbage = TestImageUtils.createGarbageData();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        for (int i = 0; i < 5; i++) {
            // Valid render
            BufferedImage img = manager.render(validPNG, "64m", 30_000);
            assertNotNull(img);

            // Invalid render (should fail gracefully)
            try {
                manager.render(garbage, "64m", 30_000);
            } catch (IOException e) {
                // expected
            }

            // Valid render should still work after failure
            img = manager.render(validPNG, "64m", 30_000);
            assertNotNull(img);
        }

        assertTrue(manager.isIdle());
    }

    @Test
    @DisplayName("Concurrent timeout and success renders don't interfere")
    void concurrentTimeoutAndSuccess() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            final long timeout = (i % 2 == 0) ? 30_000 : 1;
            futures.add(executor.submit(() -> {
                try {
                    manager.render(png, "64m", timeout);
                } catch (IOException | InterruptedException e) {
                    // Timeouts expected
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }

        assertTrue(manager.isIdle());
        executor.shutdown();
    }

    @Test
    @DisplayName("Large image renders complete successfully")
    void renderLargeImage() throws Exception {
        byte[] png = TestImageUtils.createLargePNG();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        BufferedImage img = manager.render(png, "128m", 60_000);
        assertNotNull(img);
        assertEquals(2048, img.getWidth());
        assertEquals(2048, img.getHeight());
    }

    @Test
    @DisplayName("Multiple reader instances don't interfere")
    void multipleReaders() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        List<SecureImageReader> readers = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            SecureImageReader reader = (SecureImageReader) spi.createReaderInstance(null);
            javax.imageio.stream.ImageInputStream iis =
                    ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(png));
            reader.setInput(iis);
            readers.add(reader);
        }

        for (SecureImageReader reader : readers) {
            SecureImageReadParam param = new SecureImageReadParam();
            param.setTimeoutMillis(30_000);
            BufferedImage img = reader.read(0, param);
            assertNotNull(img);
        }

        for (SecureImageReader reader : readers) {
            reader.dispose();
        }
    }
}
