# jcgm-secure-imageio

A security-hardened `javax.imageio` plugin for decoding CGM (Computer Graphics
Metafile) images. It wraps the untrusted [jcgm](https://jcgm.sourceforge.net/)
libraries inside an isolated subprocess so that a malformed or malicious CGM
file cannot compromise the host application.

## Why a subprocess?

Image decoders are a common attack surface. A crafted input can trigger memory
corruption, infinite loops, or excessive resource consumption inside the
decoder. Running the decoder in a child JVM process provides hard isolation:

- the decoder's heap is separate from the host application's heap;
- the child is killed when a configurable timeout expires;
- a Java Security Manager policy restricts what the child can do (file I/O,
  network, process spawning);
- memory usage is capped with `-Xmx`.

## Architecture

### Module roles

| Library | Role |
|---------|------|
| **jcgm-core** | Parses the CGM binary format (ISO 8632-3) into an in-memory object graph of drawing primitives. Pure parsing, no rendering. |
| **jcgm-image** | Converts the jcgm-core object graph into a `BufferedImage` using AWT `Graphics2D`. Registers a `javax.imageio` SPI so `ImageIO.read()` recognises CGM files. |

Both libraries live only on the **worker subprocess classpath**. The host
application never loads them directly.

### Why AWT is needed

jcgm-image rasterises CGM vector graphics using the standard Java 2D API
(`Graphics2D` / `BufferedImage`). There is no CGM-to-raster path in standard
Java that avoids AWT. The worker runs with `-Djava.awt.headless=true` to
disable windowing, but AWT's internal machinery still requires several
`RuntimePermission`s (native library loading, reflection, class loader access)
that are explicitly granted in the security policy.

### Component diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Host Application                                           │
│                                                             │
│  ImageIO.read(stream)                                       │
│       │                                                     │
│       ▼                                                     │
│  SecureCGMImageReaderSpi  ◄── META-INF/services SPI        │
│       │  (magic-byte check: 0x00 0x20+)                    │
│       ▼                                                     │
│  SecureImageReader                                          │
│       │  (reads full image bytes into memory)              │
│       ▼                                                     │
│  WorkerProcessManager  (singleton, fair Semaphore(1))       │
└──────────────────────────────┬──────────────────────────────┘
                               │  spawn (ProcessBuilder)
                               │  stdin  ← [4-byte len][image bytes]
                               │  stdout → [1-byte status][4-byte len][data]
                               ▼
┌─────────────────────────────────────────────────────────────┐
│  Worker Subprocess  (separate JVM)                          │
│                                                             │
│  Flags: -Xmx<N>  -Djava.awt.headless=true                  │
│         -Djava.security.manager                             │
│         -Djava.security.policy==worker-security.policy      │
│         -XX:+UseSerialGC                                    │
│                                                             │
│  ImageRenderWorker (entry point)                            │
│       │  deregisters SecureImageReaderSpi (no recursion)   │
│       │  reads image bytes from stdin                      │
│       ▼                                                     │
│  ImageIO.read()                                             │
│       │                                                     │
│       ▼                                                     │
│  jcgm-image  CGMImageReader  (SPI from lib/)               │
│       │                                                     │
│       ▼                                                     │
│  jcgm-core  CGM binary parser                              │
│       │                                                     │
│       ▼                                                     │
│  AWT Graphics2D / BufferedImage  (headless)                 │
│       │                                                     │
│  ImageIO.write(image, "PNG", out)                           │
│       │  writes PNG bytes to stdout                        │
│       ▼                                                     │
│  exit 0 (success) | exit 1 (error) | exit 2 (OOM)          │
└─────────────────────────────────────────────────────────────┘
```

### IPC protocol

**Parent → Worker (stdin)**

```
[4 bytes, big-endian]  image data length N
[N bytes]              raw image data
```

**Worker → Parent (stdout)**

```
[1 byte]               status  0=success  1=error  2=OOM
[4 bytes, big-endian]  payload length M
[M bytes]              PNG data (success) or UTF-8 error message (error/OOM)
```

### Security boundaries

| Threat | Mitigation |
|--------|-----------|
| Malformed CGM → OOM | `-Xmx` cap; exit code 2 caught by parent |
| Malformed CGM → infinite loop | Timeout + `process.destroyForcibly()` |
| Malformed CGM → file exfiltration | Policy allows only `java.home`, `lib/`, `tmpdir` |
| Network exfiltration | No `SocketPermission` in policy |
| Subprocess spawning | No `FilePermission execute` in policy |
| Recursive wrapping | Worker deregisters `SecureImageReaderSpi` on startup |
| Concurrent resource exhaustion | `Semaphore(1, fair)` serialises all renders |

### Source layout

```
src/main/java/net/sf/jcgm/secure/
  SecureCGMImageReaderSpi.java   SPI registration + CGM magic-byte detection
  SecureImageReaderSpi.java      Abstract SPI base
  SecureImageReader.java         javax.imageio ImageReader implementation
  SecureImageReadParam.java      Per-read config (timeoutMillis, maxHeapSize)
  WorkerProcessManager.java      Subprocess lifecycle, semaphore, classpath
  ImageRenderWorker.java         Subprocess entry point

src/main/resources/
  META-INF/services/javax.imageio.spi.ImageReaderSpi
  worker-security.policy         Restrictive Java Security Manager policy

lib/
  jcgm-core-2.0.5.jar           CGM parser (worker classpath only)
  jcgm-image-0.1.2.jar          CGM renderer (worker classpath only)
```

## Configuration

Pass a `SecureImageReadParam` to `ImageReader.read()`:

```java
SecureImageReadParam param = new SecureImageReadParam();
param.setTimeoutMillis(15_000);   // default 30 000 ms
param.setMaxHeapSize("128m");     // default "64m"

ImageReader reader = ImageIO.getImageReadersByFormatName("cgm").next();
reader.setInput(ImageIO.createImageInputStream(file));
BufferedImage image = reader.read(0, param);
```

## Building

```
mvn package
```

Java 17 or later is required. The jcgm libraries are expected in `lib/` and are
declared with `<scope>system</scope>` in `pom.xml`.

## Testing

```
mvn test
```

The test suite covers SPI registration, rendering correctness, security policy
enforcement, timeout/OOM handling, and concurrent load.
