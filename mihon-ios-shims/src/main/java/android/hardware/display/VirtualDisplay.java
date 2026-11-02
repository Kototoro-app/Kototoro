package android.hardware.display;

import android.view.Display;
import android.view.Surface;

/** Browser presentation lifetime, with no Android GPU or screen-mirroring resources. */
public final class VirtualDisplay {
    private final Display display;
    private final Surface surface;
    public VirtualDisplay(Display display, Surface surface) { this.display = display; this.surface = surface; }
    public Display getDisplay() { return display; }
    public Surface getSurface() { return surface; }
    public void release() { display.kototoroRelease(); }
}
