package android.media;

import android.os.Handler;
import android.view.Surface;

/**
 * The render sink used by extensions to keep an off-screen WebView active. Native WebKit renders directly, so
 * this reader has no CPU image queue: acquire returns null and no image-available callbacks are fabricated.
 */
public final class ImageReader implements AutoCloseable {
    private final int width, height, format, maxImages;
    private final Surface surface = new Surface();
    private boolean closed;

    private ImageReader(int width, int height, int format, int maxImages) {
        if (width <= 0 || height <= 0 || maxImages <= 0) throw new IllegalArgumentException("Invalid image dimensions");
        if (format != 1) throw new UnsupportedOperationException("Only browser RGBA render targets are supported");
        this.width = width; this.height = height; this.format = format; this.maxImages = maxImages;
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader(width, height, format, maxImages);
    }
    public synchronized Surface getSurface() { requireOpen(); return surface; }
    public synchronized int getWidth() { requireOpen(); return width; }
    public synchronized int getHeight() { requireOpen(); return height; }
    public synchronized int getImageFormat() { requireOpen(); return format; }
    public synchronized int getMaxImages() { requireOpen(); return maxImages; }
    public synchronized Image acquireLatestImage() { requireOpen(); return null; }
    public synchronized Image acquireNextImage() { requireOpen(); return null; }
    public synchronized void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        requireOpen();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        surface.release();
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("ImageReader is closed"); }
    public interface OnImageAvailableListener { void onImageAvailable(ImageReader reader); }
}
