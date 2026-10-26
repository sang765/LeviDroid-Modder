package org.levimc.launcher.core.logging;

import android.os.Process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process log capture backing the Logcat overlay's "App-Level Logging"
 * option. libloghook inline-hooks __android_log_buf_write, which both
 * android.util.Log and native __android_log_print funnel into, so every line
 * from the launcher and the game is captured here even when the device's
 * logd drops app writes (MIUI/HyperOS) and logcat stays empty.
 */
public final class AppLevelLogSink {

    public interface Listener {
        void onLogLine(String line);
    }

    private static final int MAX_LINES = 2000;
    private static final ArrayDeque<String> ring = new ArrayDeque<>(MAX_LINES);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean installFailed;
    private static volatile boolean installed;

    static {
        System.loadLibrary("loghook");
    }

    private AppLevelLogSink() {}

    public static boolean ensureInstalled() {
        if (installFailed) return false;
        try {
            if (installNative() != 0) installFailed = true;
        } catch (Throwable ignored) {
            installFailed = true;
        }
        installed = !installFailed;
        return !installFailed;
    }

    // True only while the native hook is actually installed. The overlay
    // checks this instead of installing on its own: shadowhook's init hooks
    // the linker's do_dlopen for the rest of the process lifetime, which has
    // deadlocked NMDK mod dlopens. Installation must stay in the launch flow,
    // after all game and mod libraries are loaded.
    public static boolean isReady() {
        return installed;
    }

    public static void teardown() {
        try {
            uninstallNative();
        } catch (Throwable ignored) {
        }
        installed = false;
        synchronized (ring) {
            ring.clear();
        }
    }

    // Unhook while third-party game/mod .so files are dlopened: their
    // constructors run arbitrary code and an active log hook in that window
    // has caused launch hangs on device.
    public static void suspendCapture() {
        try {
            uninstallNative();
        } catch (Throwable ignored) {
        }
        installed = false;
    }

    public static boolean resumeCapture() {
        installFailed = false;
        return ensureInstalled();
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static List<String> snapshotRing() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    // Called from the native hook on arbitrary threads. Must never log:
    // shadowhook already guards proxy re-entry, but a Log call here would
    // still feed the line straight back into the ring.
    private static void onNativeLog(int prio, long tid, String tag, String msg) {
        String line = formatLine(prio, tid, tag, msg);
        synchronized (ring) {
            if (ring.size() >= MAX_LINES) ring.pollFirst();
            ring.addLast(line);
        }
        for (Listener listener : listeners) listener.onLogLine(line);
    }

    // threadtime layout so LogcatOverlay's parser and colorizer work unchanged.
    private static String formatLine(int prio, long tid, String tag, String msg) {
        char level = switch (prio) {
            case 2 -> 'V';
            case 3 -> 'D';
            case 4 -> 'I';
            case 5 -> 'W';
            case 6 -> 'E';
            case 7 -> 'F';
            default -> '?';
        };
        if (tag == null) tag = "";
        if (msg == null) msg = "";
        return String.format(Locale.US, "%1$tm-%1$td %1$tT.%1$tL %2$d %3$d %4$c %5$s: %6$s",
                new Date(), Process.myPid(), tid, level, tag, msg);
    }

    private static native int installNative();

    private static native boolean uninstallNative();
}
