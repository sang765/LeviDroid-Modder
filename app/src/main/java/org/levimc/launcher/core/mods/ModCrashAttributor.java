package org.levimc.launcher.core.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort crash attribution: flags enabled mods whose display name, file
 * name or id appears anywhere in the crash log. shortcut: pure text match —
 * only as good as the log (native backtraces usually contain the lib name,
 * scripting errors the pack name); upgrade to a real stack parser when the
 * false-positive rate becomes a problem.
 */
public final class ModCrashAttributor {

    private static final int MIN_TOKEN_LENGTH = 3;

    private ModCrashAttributor() {
    }

    public static List<Mod> attribute(String logText, List<Mod> mods) {
        List<Mod> flagged = new ArrayList<>();
        if (logText == null || logText.isEmpty() || mods == null) return flagged;
        String haystack = logText.toLowerCase(Locale.US);
        for (Mod mod : mods) {
            if (!mod.isEnabled()) continue;
            if (containsToken(haystack, mod.getDisplayName())
                    || containsToken(haystack, mod.getFileName())
                    || containsToken(haystack, mod.getId())) {
                flagged.add(mod);
            }
        }
        return flagged;
    }

    private static boolean containsToken(String haystack, String token) {
        if (token == null) return false;
        String needle = token.trim().toLowerCase(Locale.US);
        return needle.length() >= MIN_TOKEN_LENGTH && haystack.contains(needle);
    }
}
