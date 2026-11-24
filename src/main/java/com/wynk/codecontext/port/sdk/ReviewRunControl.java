package com.wynk.codecontext.port.sdk;

/** Cancellation/deadline adapter for the standalone indexing worker. */
public final class ReviewRunControl {
    private ReviewRunControl() {}
    public static long remainingMillis() { return 120_000L; }
    public static void check() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("indexing worker interrupted");
    }
}
