package com.fourzerofourgb.app.backup;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether the app's UI is on screen: MainActivity between onStart and onStop
 * (a count, so an activity starting before the previous one stopped is
 * covered). Background People matching never runs then; the app's own
 * scanner does the work. Workers run in the app's process, so a static is the
 * truth; a new process starts with no UI.
 */
public final class AppVisibility {

    private static final AtomicInteger started = new AtomicInteger();

    private AppVisibility() {}

    public static void onStart() {
        started.incrementAndGet();
    }

    public static void onStop() {
        started.updateAndGet(n -> Math.max(0, n - 1));
    }

    static boolean isVisible() {
        return started.get() > 0;
    }
}
