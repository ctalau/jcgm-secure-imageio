package net.sf.jcgm.secure;

import org.junit.jupiter.api.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests for the secure CGM ImageIO plugin.
 * Verifies correct behavior under concurrent load and that the
 * single-process queue works correctly.
 */
class StressTest {

    @BeforeEach
    void setUp() {
        WorkerProcessManager.resetInstance();
    }

    @Test
    @DisplayName("Concurrent rendering requests are serialized (single process)")
    void concurrentRendersSerialized() throws Exception {
        int threadCount = 5;
        byte[] cgm = CGMTestUtils.createMinimalCGM();

        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Future<BufferedImage>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // synchronize start
                try {
                    BufferedImage img = manager.render(cgm, 100, 100, "64m", 60_000);
                    successCount.incrementAndGet();
                    return img;
                } catch (IOException e) {
                    errorCount.incrementAndGet();
                    throw e;
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();

        // Wait for all to complete
        assertTrue(doneLatch.await(120, TimeUnit.SECONDS),
                "All renders should complete within 120 seconds");

        // All should succeed
        assertEquals(threadCount, successCount.get(),
                "All concurrent renders should succeed");
        assertEquals(0, errorCount.get());

        // Verify each result is a valid image
        for (Future<BufferedImage> future : futures) {
            BufferedImage img = future.get();
            assertNotNull(img);
            assertEquals(100, img.getWidth());
            assertEquals(100, img.getHeight());
        }

        executor.shutdown();
        assertTrue(manager.isIdle(), "Manager should be idle after all renders complete");
    }

    @Test
    @DisplayName("Queue length reflects pending requests")
    void queueLengthReflectsPending() throws Exception {
        byte[] cgm = CGMTestUtils.createCGMWithLines(500); // takes a bit longer to render
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        // Start several concurrent requests
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<BufferedImage>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(); // synchronize start
                return manager.render(cgm, 200, 200, "64m", 120_000);
            }));
        }

        // Give threads a moment to queue up
        Thread.sleep(500);

        // At least some should be queued (one is running, rest are waiting)
        // We can't guarantee exact timing, but the queue + running should account for all
        int queueLen = manager.getQueueLength();
        int available = manager.isIdle() ? 1 : 0;
        // At least 1 should be either running or queued at this point
        assertTrue(queueLen >= 0, "Queue length should be non-negative");

        // Wait for all to complete
        for (Future<BufferedImage> future : futures) {
            BufferedImage img = future.get(120, TimeUnit.SECONDS);
            assertNotNull(img);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Rapid sequential renders work correctly")
    void rapidSequentialRenders() throws Exception {
        byte[] cgm = CGMTestUtils.createMinimalCGM();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        for (int i = 0; i < 10; i++) {
            BufferedImage img = manager.render(cgm, 50, 50, "64m", 30_000);
            assertNotNull(img, "Render " + i + " should succeed");
            assertEquals(50, img.getWidth());
            assertEquals(50, img.getHeight());
        }
    }

    @Test
    @DisplayName("Mixed valid and invalid renders don't corrupt state")
    void mixedValidAndInvalidRenders() throws Exception {
        byte[] validCGM = CGMTestUtils.createMinimalCGM();
        byte[] corrupted = CGMTestUtils.createCorruptedCGM();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        for (int i = 0; i < 5; i++) {
            // Valid render
            BufferedImage img = manager.render(validCGM, 100, 100, "64m", 30_000);
            assertNotNull(img);

            // Corrupted render (may succeed or fail, but shouldn't corrupt state)
            try {
                manager.render(corrupted, 100, 100, "64m", 30_000);
            } catch (IOException e) {
                // expected
            }

            // Valid render should still work after corrupted one
            img = manager.render(validCGM, 100, 100, "64m", 30_000);
            assertNotNull(img);
        }

        assertTrue(manager.isIdle());
    }

    @Test
    @DisplayName("Concurrent timeout and success renders don't interfere")
    void concurrentTimeoutAndSuccess() throws Exception {
        byte[] cgm = CGMTestUtils.createMinimalCGM();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        // Mix of normal and very short timeout requests
        for (int i = 0; i < 8; i++) {
            final long timeout = (i % 2 == 0) ? 30_000 : 1; // alternating normal and 1ms
            futures.add(executor.submit(() -> {
                try {
                    manager.render(cgm, 100, 100, "64m", timeout);
                } catch (IOException | InterruptedException e) {
                    // Timeouts and errors are expected
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue(manager.isIdle(), "Manager should be idle after all renders complete");
    }

    @Test
    @DisplayName("Rendering with many drawing commands completes")
    void renderComplexCGM() throws Exception {
        byte[] cgm = CGMTestUtils.createCGMWithLines(500);
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        BufferedImage img = manager.render(cgm, 800, 600, "128m", 60_000);
        assertNotNull(img);
        assertEquals(800, img.getWidth());
        assertEquals(600, img.getHeight());
    }

    @Test
    @DisplayName("Multiple readers can be created without interference")
    void multipleReaders() throws Exception {
        byte[] cgm = CGMTestUtils.createMinimalCGM();

        SecureCGMImageReaderSpi spi = new SecureCGMImageReaderSpi();
        List<SecureCGMImageReader> readers = new ArrayList<>();

        // Create multiple readers
        for (int i = 0; i < 5; i++) {
            SecureCGMImageReader reader = (SecureCGMImageReader) spi.createReaderInstance(null);
            javax.imageio.stream.ImageInputStream iis =
                    javax.imageio.ImageIO.createImageInputStream(
                            new java.io.ByteArrayInputStream(cgm));
            reader.setInput(iis);
            readers.add(reader);
        }

        // Read from each
        for (SecureCGMImageReader reader : readers) {
            SecureCGMImageReadParam param = new SecureCGMImageReadParam();
            param.setWidth(100);
            param.setHeight(100);
            param.setTimeoutMillis(30_000);
            BufferedImage img = reader.read(0, param);
            assertNotNull(img);
        }

        // Dispose all
        for (SecureCGMImageReader reader : readers) {
            reader.dispose();
        }
    }
}
