package android.view;

import android.os.Parcel;

/** Extension-generated pointer events; no Android native input subsystem is involved. */
public final class MotionEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
    public static final int ACTION_MASK = 255, TOOL_TYPE_FINGER = 1;
    private final long downTime, eventTime;
    private final int action, deviceId, source;
    private final PointerCoords[] coords;

    private MotionEvent(long downTime, long eventTime, int action, int deviceId, int source, PointerCoords[] coords) {
        this.downTime = downTime;
        this.eventTime = eventTime;
        this.action = action;
        this.deviceId = deviceId;
        this.source = source;
        this.coords = new PointerCoords[coords.length];
        for (int i = 0; i < coords.length; i++) this.coords[i] = new PointerCoords(coords[i]);
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
            PointerProperties[] properties, PointerCoords[] coords, int metaState, int buttonState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) {
        if (pointerCount <= 0 || pointerCount != coords.length || pointerCount != properties.length)
            throw new IllegalArgumentException("Invalid pointer count");
        return new MotionEvent(downTime, eventTime, action, deviceId, source, coords);
    }

    public int getAction() { return action; }
    public int getActionMasked() { return action & ACTION_MASK; }
    public float getX() { return coords[0].x; }
    public float getY() { return coords[0].y; }
    public long getDownTime() { return downTime; }
    @Override public long getEventTime() { return eventTime; }
    @Override public int getDeviceId() { return deviceId; }
    @Override public int getSource() { return source; }
    public int getPointerCount() { return coords.length; }
    public void recycle() {}
    @Override public void writeToParcel(Parcel destination, int flags) {
        throw new UnsupportedOperationException("Browser input events cannot be parceled");
    }

    public static final class PointerProperties {
        public int id, toolType;
        public PointerProperties() {}
    }

    public static final class PointerCoords {
        public float x, y, pressure, size, touchMajor, touchMinor, toolMajor, toolMinor, orientation;
        public PointerCoords() {}
        public PointerCoords(PointerCoords other) {
            x = other.x; y = other.y; pressure = other.pressure; size = other.size;
            touchMajor = other.touchMajor; touchMinor = other.touchMinor;
            toolMajor = other.toolMajor; toolMinor = other.toolMinor; orientation = other.orientation;
        }
    }
}
