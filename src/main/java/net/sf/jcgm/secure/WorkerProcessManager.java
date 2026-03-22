package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages worker subprocesses for secure CGM rendering.
 * Enforces at most one active worker process at a time using a semaphore.
 * Additional requests queue up and wait for the semaphore.
 */
public class WorkerProcessManager {

    private static volatile WorkerProcessManager instance;

    private final Semaphore semaphore = new Semaphore(1, true); // fair ordering

    private WorkerProcessManager() {
    }

    public static WorkerProcessManager getInstance() {
        if (instance == null) {
            synchronized (WorkerProcessManager.class) {
                if (instance == null) {
                    instance = new WorkerProcessManager();
                }
            }
        }
        return instance;
    }

    /**
     * For testing: reset the singleton.
     */
    static void resetInstance() {
        synchronized (WorkerProcessManager.class) {
            instance = null;
        }
    }

    /**
     * Returns the number of requests currently queued (waiting for the semaphore).
     */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    /**
     * Returns true if the semaphore has available permits (no active render).
     */
    public boolean isIdle() {
        return semaphore.availablePermits() > 0;
    }

    /**
     * Renders CGM data to a BufferedImage by delegating to an isolated subprocess.
     *
     * @param cgmData     raw CGM file bytes
     * @param width       desired output width
     * @param height      desired output height
     * @param maxHeap     max heap for the worker (e.g., "64m")
     * @param timeoutMs   max time to wait for rendering
     * @return the rendered image
     * @throws IOException          if rendering fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public BufferedImage render(byte[] cgmData, int width, int height,
                                String maxHeap, long timeoutMs)
            throws IOException, InterruptedException {

        if (cgmData == null || cgmData.length == 0) {
            throw new IOException("CGM data is null or empty");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
        }

        boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new IOException("Timed out waiting for worker process (queue full)");
        }

        try {
            return executeWorker(cgmData, width, height, maxHeap, timeoutMs);
        } finally {
            semaphore.release();
        }
    }

    private BufferedImage executeWorker(byte[] cgmData, int width, int height,
                                        String maxHeap, long timeoutMs)
            throws IOException {

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xmx" + maxHeap);
        command.add("-XX:+UseSerialGC");  // predictable memory usage
        command.add("-Djava.security.manager=allow");
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classpath);
        command.add(CGMRenderWorker.class.getName());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        try {
            // Write input to worker
            DataOutputStream workerIn = new DataOutputStream(
                    new BufferedOutputStream(process.getOutputStream()));
            workerIn.writeInt(cgmData.length);
            workerIn.write(cgmData);
            workerIn.writeInt(width);
            workerIn.writeInt(height);
            workerIn.flush();
            workerIn.close();

            // Wait for process with timeout
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Worker process timed out after " + timeoutMs + "ms");
            }

            // Read response
            DataInputStream workerOut = new DataInputStream(
                    new BufferedInputStream(process.getInputStream()));

            int status;
            try {
                status = workerOut.readByte();
            } catch (EOFException e) {
                // Process crashed without producing output
                String stderr = readStderr(process);
                int exitCode = process.exitValue();
                throw new IOException("Worker process crashed (exit code " + exitCode + ")"
                        + (stderr.isEmpty() ? "" : ": " + stderr));
            }

            int dataLength = workerOut.readInt();

            if (status != 0) {
                // Error
                byte[] msgBytes = new byte[dataLength];
                workerOut.readFully(msgBytes);
                throw new IOException("Worker error: " + new String(msgBytes, java.nio.charset.StandardCharsets.UTF_8));
            }

            // Success - read PNG
            byte[] pngData = new byte[dataLength];
            workerOut.readFully(pngData);

            return ImageIO.read(new ByteArrayInputStream(pngData));

        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Worker interrupted", e);
        } finally {
            process.destroyForcibly();
        }
    }

    private String readStderr(Process process) {
        try {
            byte[] stderrBytes = process.getErrorStream().readAllBytes();
            String stderr = new String(stderrBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            // Truncate long stderr
            if (stderr.length() > 500) {
                stderr = stderr.substring(0, 500) + "...";
            }
            return stderr;
        } catch (IOException e) {
            return "";
        }
    }
}
