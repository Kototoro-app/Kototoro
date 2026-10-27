package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/** Opaque browser render target. WKWebView owns rendering; no Android pixel buffers are allocated. */
public final class Surface implements Parcelable {
    private boolean valid = true;

    public Surface() {}
    public synchronized boolean isValid() { return valid; }
    public synchronized void release() { valid = false; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) {
        throw new UnsupportedOperationException("Browser surfaces cannot be parceled");
    }
}
