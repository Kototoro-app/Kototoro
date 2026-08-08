package com.github.catvod.spider.merge.d0;

/**
 * Compatibility replacement for Yoursmile's delayed host-integrity task.
 *
 * The original task runs after the spider is already usable and deliberately
 * terminates or crashes hosts whose APK/Dex layout differs from TVBox. It does
 * not initialize any spider state, so the TVBox runtime shadows only this task.
 */
public final class RunnableC0901i implements Runnable {

    public static final RunnableC0901i a = new RunnableC0901i();

    private RunnableC0901i() {
    }

    @Override
    public void run() {
        // Host integrity checks are unrelated to Spider ABI behavior.
    }
}
