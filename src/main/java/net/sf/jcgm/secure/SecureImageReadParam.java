package net.sf.jcgm.secure;

import javax.imageio.ImageReadParam;

/**
 * Parameters controlling the secure image reading subprocess.
 * Format-agnostic — applies to any image format delegated through
 * {@link SecureImageReader}.
 */
public class SecureImageReadParam extends ImageReadParam {

    /** Maximum time in milliseconds to wait for the worker process. */
    private long timeoutMillis = 30_000;

    /** Maximum heap size for the worker process (e.g. "64m", "128m"). */
    private String maxHeapSize = "64m";

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        this.timeoutMillis = timeoutMillis;
    }

    public String getMaxHeapSize() {
        return maxHeapSize;
    }

    public void setMaxHeapSize(String maxHeapSize) {
        if (maxHeapSize == null || maxHeapSize.isBlank()) {
            throw new IllegalArgumentException("maxHeapSize must not be blank");
        }
        this.maxHeapSize = maxHeapSize;
    }
}
