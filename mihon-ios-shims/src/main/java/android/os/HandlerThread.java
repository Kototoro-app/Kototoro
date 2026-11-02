package android.os;

/** A real compatibility Looper thread for extension callbacks, with deterministic shutdown. */
public class HandlerThread extends Thread {
    private Looper looper;
    private boolean finished;

    public HandlerThread(String name) { super(name); setDaemon(true); }
    public HandlerThread(String name, int priority) { this(name); }

    @Override public void run() {
        try {
            Looper.prepare();
            synchronized (this) {
                looper = Looper.myLooper();
                notifyAll();
            }
            onLooperPrepared();
            Looper.loop();
        } finally {
            synchronized (this) { finished = true; notifyAll(); }
        }
    }
    protected void onLooperPrepared() {}
    public synchronized Looper getLooper() {
        boolean interrupted = false;
        while (isAlive() && looper == null && !finished) {
            try { wait(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return isAlive() && !finished ? looper : null;
    }
    public boolean quit() {
        Looper current = getLooper();
        if (current == null) return false;
        current.quit();
        return true;
    }
    public boolean quitSafely() {
        Looper current = getLooper();
        if (current == null) return false;
        current.quitSafely();
        return true;
    }
}
