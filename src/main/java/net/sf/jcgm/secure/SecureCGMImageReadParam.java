package net.sf.jcgm.secure;

import javax.imageio.ImageReadParam;

/**
 * Parameters for the secure CGM image reader.
 */
public class SecureCGMImageReadParam extends ImageReadParam {

    private int width = 800;
    private int height = 600;
    private int dpi = 96;

    /** Maximum time in milliseconds to wait for the worker process. */
    private long timeoutMillis = 30_000;

    /** Maximum heap size for the worker process (e.g. "64m", "128m"). */
    private String maxHeapSize = "64m";

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        if (width <= 0) throw new IllegalArgumentException("width must be positive");
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
        this.height = height;
    }

    public int getDpi() {
        return dpi;
    }

    public void setDpi(int dpi) {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be positive");
        this.dpi = dpi;
    }

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
        this.maxHeapSize = maxHeapSize;
    }
}
