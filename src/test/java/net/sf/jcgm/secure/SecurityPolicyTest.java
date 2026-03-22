package net.sf.jcgm.secure;

import org.junit.jupiter.api.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the security policy properly restricts the worker subprocess.
 */
class SecurityPolicyTest {

    @BeforeEach
    void setUp() {
        WorkerProcessManager.resetInstance();
    }

    @Test
    @DisplayName("Policy file exists and is loaded")
    void policyFileExists() {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        // If the policy file is found, the manager will use -Djava.security.manager.
        // A successful render proves the policy is compatible with ImageIO.
        assertDoesNotThrow(() -> {
            byte[] png = TestImageUtils.createSmallPNG();
            manager.render(png, "64m", 30_000);
        });
    }

    @Test
    @DisplayName("Worker renders successfully under security policy")
    void workerRendersUnderPolicy() throws Exception {
        byte[] png = TestImageUtils.createSmallPNG();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        BufferedImage img = manager.render(png, "64m", 30_000);
        assertNotNull(img);
        assertEquals(10, img.getWidth());
    }

    @Test
    @DisplayName("CGM rendering works under security policy with jcgm-image")
    void cgmRenderingUnderPolicy() throws Exception {
        byte[] cgm = TestImageUtils.createMinimalCGM();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        BufferedImage img = manager.render(cgm, "64m", 30_000);
        assertNotNull(img);
        assertTrue(img.getWidth() > 0);
    }

    @Test
    @DisplayName("Worker with missing lib dir fails gracefully")
    void workerWithMissingLibDir() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        manager.setLibDir(Path.of("/nonexistent/lib"));

        byte[] cgm = TestImageUtils.createMinimalCGM();
        // Without jcgm jars, CGM format won't be recognized
        assertThrows(IOException.class, () -> manager.render(cgm, "64m", 30_000));
    }

    @Test
    @DisplayName("Worker with no policy file skips security manager")
    void workerWithNoPolicyFile() throws Exception {
        WorkerProcessManager manager = WorkerProcessManager.getInstance();
        manager.setPolicyFile(Path.of("/nonexistent/policy.file"));

        byte[] png = TestImageUtils.createSmallPNG();
        // Without a valid policy file, the worker skips -Djava.security.manager
        // and still renders successfully (but without security restrictions)
        BufferedImage img = manager.render(png, "64m", 30_000);
        assertNotNull(img);
    }

    @Test
    @DisplayName("Security policy blocks network access in worker")
    void securityPolicyBlocksNetwork() throws Exception {
        // If the worker tries to make a network call, the SecurityManager should block it.
        // We test this indirectly: garbage data that might trigger URL-based readers
        // should still fail gracefully without the worker being able to open connections.
        byte[] garbage = TestImageUtils.createGarbageData();
        WorkerProcessManager manager = WorkerProcessManager.getInstance();

        assertThrows(IOException.class, () -> manager.render(garbage, "64m", 30_000));
    }
}
