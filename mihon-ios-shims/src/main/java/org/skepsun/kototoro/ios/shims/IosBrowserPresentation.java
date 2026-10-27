package org.skepsun.kototoro.ios.shims;

import android.view.View;

/** Native attachment boundary; the platform adapter supplies the WKWebView implementation. */
public final class IosBrowserPresentation {
    public interface Host {
        void show(View view, int width, int height, int densityDpi);
        void hide(View view);
    }
    private static volatile Host host;
    private IosBrowserPresentation() {}
    public static void setHost(Host value) { host = value; }
    public static void show(View view, int width, int height, int densityDpi) {
        Host current = host;
        if (current == null) throw new UnsupportedOperationException("Native browser presentation is unavailable");
        current.show(view, width, height, densityDpi);
    }
    public static void hide(View view) {
        Host current = host;
        if (current != null) current.hide(view);
    }
}
