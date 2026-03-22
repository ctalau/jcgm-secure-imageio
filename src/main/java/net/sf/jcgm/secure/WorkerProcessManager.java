package net.sf.jcgm.secure;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages worker subprocesses for secure image rendering.
 * Enforces at most one active worker process at a time using a fair semaphore.
 * Additional requests queue up in FIFO order.
 *
 * <p>The worker subprocess runs with a <b>separate classpath</b> containing only:
 * <ul>
 *   <li>Our own classes (for {@link ImageRenderWorker})</li>
 *   <li>Image decoder jars from the {@code lib/} directory (e.g. jcgm-core, jcgm-image)</li>
 * </ul>
 *
 * <p>The worker is locked down with a {@code worker-security.policy} that restricts
 * file reads to only {@code java.home}, the lib jars, and {@code java.io.tmpdir}.
 * No network access, no process execution, no writes outside tmpdir.
 */
public class WorkerProcessManager {

    private static volatile WorkerProcessManager instance;

    private final Semaphore semaphore = new Semaphore(1, true); // fair FIFO ordering

    /** Directory containing decoder jars (jcgm-core, jcgm-image). */
    private Path libDir;

    /** Path to our own classes or jar (for the worker classpath). */
    private Path classesDir;

    /** Path to the security policy file. */
    private Path policyFile;

    private WorkerProcessManager() {
        resolveDefaults();
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

    /** Override the library directory containing decoder jars. */
    public void setLibDir(Path libDir) {
        this.libDir = libDir;
    }

    /** Override the classes/jar location for the worker. */
    public void setClassesDir(Path classesDir) {
        this.classesDir = classesDir;
    }

    /** Override the security policy file path. */
    public void setPolicyFile(Path policyFile) {
        this.policyFile = policyFile;
    }

    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    public boolean isIdle() {
        return semaphore.availablePermits() > 0;
    }

    /**
     * Renders image data to a BufferedImage by delegating to an isolated subprocess.
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

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        // Build worker-only classpath: our classes + lib/*.jar
        String workerClasspath = buildWorkerClasspath();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xmx" + maxHeap);
        command.add("-XX:+UseSerialGC");
        command.add("-Djava.awt.headless=true");

        // Security manager with policy file
        if (policyFile != null && Files.exists(policyFile)) {
            command.add("-Djava.security.manager");
            command.add("-Djava.security.policy==" + policyFile.toAbsolutePath());
            command.add("-Dworker.lib.dir=" + libDir.toAbsolutePath());
            command.add("-Dworker.classes.dir=" + classesDir.toAbsolutePath());
        }

        command.add("-cp");
        command.add(workerClasspath);
        command.add(ImageRenderWorker.class.getName());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        try {
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

    /**
     * Builds a classpath containing only:
     * 1. Our own classes/jar (for ImageRenderWorker)
     * 2. Decoder jars from lib/ (jcgm-core, jcgm-image, etc.)
     *
     * This is intentionally separate from the parent's classpath.
     */
    private String buildWorkerClasspath() throws IOException {
        List<String> entries = new ArrayList<>();

        // Our own classes
        entries.add(classesDir.toAbsolutePath().toString());

        // All jars in the lib directory
        if (libDir != null && Files.isDirectory(libDir)) {
            try (Stream<Path> jars = Files.list(libDir)) {
                jars.filter(p -> p.toString().endsWith(".jar"))
                        .map(p -> p.toAbsolutePath().toString())
                        .forEach(entries::add);
            }
        }

        return String.join(File.pathSeparator, entries);
    }

    /**
     * Resolves default paths for lib dir, classes dir, and policy file.
     * Tries (in order):
     * 1. System property {@code jcgm.secure.project.dir} (set by Maven surefire)
     * 2. Location of this class's jar/classes
     */
    private void resolveDefaults() {
        // Try system property first (for Maven test runs)
        String projectDir = System.getProperty("jcgm.secure.project.dir");
        if (projectDir != null) {
            Path base = Path.of(projectDir);
            this.libDir = base.resolve("lib");
            this.classesDir = base.resolve("target/classes");
            this.policyFile = this.classesDir.resolve("worker-security.policy");
            return;
        }

        // Resolve from our own class location
        try {
            Path myLocation = Path.of(
                    WorkerProcessManager.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());

            if (Files.isDirectory(myLocation)) {
                // Running from classes/ directory (e.g. IDE)
                this.classesDir = myLocation;
                // lib/ is a sibling of the classes root project
                Path projectRoot = myLocation.getParent().getParent(); // target/classes -> target -> project
                this.libDir = projectRoot.resolve("lib");
                this.policyFile = myLocation.resolve("worker-security.policy");
            } else {
                // Running from a jar
                this.classesDir = myLocation;
                this.libDir = myLocation.getParent().resolve("lib");
                this.policyFile = extractPolicyFile();
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Cannot resolve worker paths", e);
        }
    }

    /**
     * When running from a jar, extract the policy file to a temp location.
     */
    private Path extractPolicyFile() {
        try (InputStream is = getClass().getResourceAsStream("/worker-security.policy")) {
            if (is == null) return null;
            Path tmp = Files.createTempFile("worker-security", ".policy");
            tmp.toFile().deleteOnExit();
            Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            return null;
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
