package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages worker subprocesses for secure image rendering.
 * Enforces at most one active worker process at a time using a fair semaphore.
 * Additional requests queue up in FIFO order.
 *
 * <p>Format-agnostic — the subprocess uses standard {@link ImageIO#read}
 * with whatever readers are on the classpath.
 */
public class WorkerProcessManager {

    private static volatile WorkerProcessManager instance;

    private final Semaphore semaphore = new Semaphore(1, true); // fair FIFO ordering

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

    /** For testing: reset the singleton. */
    static void resetInstance() {
        synchronized (WorkerProcessManager.class) {
            instance = null;
        }
    }

    /** Returns the number of requests currently queued (waiting for the semaphore). */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    /** Returns true if no render is currently active. */
    public boolean isIdle() {
        return semaphore.availablePermits() > 0;
    }

    /**
     * Renders image data to a BufferedImage by delegating to an isolated subprocess.
     *
     * @param imageData   raw image file bytes (any format supported by ImageIO readers on classpath)
     * @param maxHeap     max heap for the worker (e.g., "64m")
     * @param timeoutMs   max time to wait for rendering (includes queue wait + processing)
     * @return the rendered image
     * @throws IOException          if rendering fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public BufferedImage render(byte[] imageData, String maxHeap, long timeoutMs)
            throws IOException, InterruptedException {

        if (imageData == null || imageData.length == 0) {
            throw new IOException("Image data is null or empty");
        }

        boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new IOException("Timed out waiting for worker process (queue full)");
        }

        try {
            return executeWorker(imageData, maxHeap, timeoutMs);
        } finally {
            semaphore.release();
        }
    }

    private BufferedImage executeWorker(byte[] imageData, String maxHeap, long timeoutMs)
            throws IOException {

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xmx" + maxHeap);
        command.add("-XX:+UseSerialGC");
        command.add("-Djava.security.manager=allow");
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classpath);
        command.add(ImageRenderWorker.class.getName());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        try {
            // Send image data to worker
            DataOutputStream workerIn = new DataOutputStream(
                    new BufferedOutputStream(process.getOutputStream()));
            workerIn.writeInt(imageData.length);
            workerIn.write(imageData);
            workerIn.flush();
            workerIn.close();

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
                String stderr = readStderr(process);
                int exitCode = process.exitValue();
                throw new IOException("Worker process crashed (exit code " + exitCode + ")"
                        + (stderr.isEmpty() ? "" : ": " + stderr));
            }

            int dataLength = workerOut.readInt();

            if (status != 0) {
                byte[] msgBytes = new byte[dataLength];
                workerOut.readFully(msgBytes);
                throw new IOException("Worker error: " + new String(msgBytes, StandardCharsets.UTF_8));
            }

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
            String stderr = new String(stderrBytes, StandardCharsets.UTF_8).trim();
            if (stderr.length() > 500) {
                stderr = stderr.substring(0, 500) + "...";
            }
            return stderr;
        } catch (IOException e) {
            return "";
        }
    }
}
