package android.app;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import org.skepsun.kototoro.ios.shims.IosBrowserPresentation;

/** Maps a virtual display's WebView to the native browser host, without an Android window manager. */
public class Presentation extends Dialog {
    private final Display display;
    public Presentation(Context context, Display display) {
        super(context);
        if (display == null || !display.isValid()) throw new IllegalArgumentException("Display is unavailable");
        this.display = display;
        display.kototoroOnRelease(this::dismiss);
    }
    public Display getDisplay() { return display; }
    @Override protected void kototoroShow() {
        if (!display.isValid()) throw new IllegalStateException("Display is released");
        if (kototoroContent() == null) throw new IllegalStateException("Presentation has no content");
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        IosBrowserPresentation.show(kototoroContent(), metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }
    @Override protected void kototoroHide() { IosBrowserPresentation.hide(kototoroContent()); }
}
