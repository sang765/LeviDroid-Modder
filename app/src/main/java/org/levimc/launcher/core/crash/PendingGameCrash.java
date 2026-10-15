package org.levimc.launcher.core.crash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the log path of a crash captured while the game runtime was
 * active, so the launcher can show its own dialog after the process comes
 * back. Only ever written from the xCrash callback.
 */
public final class PendingGameCrash {

    private static final String PREFS_NAME = "game_crash_prefs";
    private static final String KEY_LOG_PATH = "log_path";

    private PendingGameCrash() {
    }

    public static void save(Context context, String logPath) {
        // commit() on purpose: the process is about to die and apply() may not flush.
        prefs(context).edit().putString(KEY_LOG_PATH, logPath).commit();
    }

    /** Returns the stored log path and clears the record, or null when there is none. */
    public static String consumeLogPath(Context context) {
        SharedPreferences prefs = prefs(context);
        String logPath = prefs.getString(KEY_LOG_PATH, null);
        if (logPath != null) {
            prefs.edit().remove(KEY_LOG_PATH).apply();
        }
        return logPath;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
