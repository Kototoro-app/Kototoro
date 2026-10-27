package android.hardware.display;

import android.view.Display;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates only displays hosting native browser presentations; capture and mirroring are unsupported. */
public final class DisplayManager {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    public DisplayManager() {}
    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi,
            Surface surface, int flags) {
        if (width <= 0 || height <= 0 || densityDpi <= 0 || surface == null || !surface.isValid())
            throw new IllegalArgumentException("Invalid browser display target");
        if ((flags & ~11) != 0) throw new UnsupportedOperationException("Screen capture and mirroring are unavailable");
        return new VirtualDisplay(new Display(NEXT_ID.getAndIncrement(), name, width, height, densityDpi), surface);
    }
}
