package android.view;

import android.util.DisplayMetrics;
import java.util.ArrayList;
import java.util.List;

/** Metadata and lifetime of a browser-only presentation display. */
public final class Display {
    public static final int DEFAULT_DISPLAY = 0, FLAG_PRESENTATION = 8;
    private final int id, width, height, densityDpi;
    private final String name;
    private final List<Runnable> onRelease = new ArrayList<>();
    private boolean valid = true;

    public Display(int id, String name, int width, int height, int densityDpi) {
        this.id = id; this.name = name; this.width = width; this.height = height; this.densityDpi = densityDpi;
    }
    public int getDisplayId() { return id; }
    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFlags() { return FLAG_PRESENTATION; }
    public synchronized boolean isValid() { return valid; }
    public void getMetrics(DisplayMetrics metrics) {
        metrics.widthPixels = width; metrics.heightPixels = height;
        metrics.densityDpi = densityDpi; metrics.density = densityDpi / 160f;
        metrics.scaledDensity = metrics.density; metrics.xdpi = metrics.ydpi = densityDpi;
    }
    public void getRealMetrics(DisplayMetrics metrics) { getMetrics(metrics); }
    public synchronized void kototoroOnRelease(Runnable callback) {
        if (!valid) throw new IllegalStateException("Display is released");
        onRelease.add(callback);
    }
    public void kototoroRelease() {
        List<Runnable> callbacks;
        synchronized (this) {
            if (!valid) return;
            valid = false;
            callbacks = new ArrayList<>(onRelease);
            onRelease.clear();
        }
        for (Runnable callback : callbacks) callback.run();
    }
}
