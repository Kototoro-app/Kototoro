package android.view;

import android.os.Parcelable;

/** In-memory input metadata for events forwarded to the native browser. */
public abstract class InputEvent implements Parcelable {
    public abstract int getDeviceId();
    public abstract int getSource();
    public abstract long getEventTime();
    public boolean isFromSource(int source) { return (getSource() & source) == source; }
    @Override public int describeContents() { return 0; }
}
